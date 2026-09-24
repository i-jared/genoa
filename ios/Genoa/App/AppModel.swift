import AVFoundation
import AuthenticationServices
import Network
import SwiftUI
import UserNotifications

@MainActor
final class AppModel: ObservableObject {
  static let production = URL(string: "https://genoa-api.hp-accounts.workers.dev")!
  @Published var credentials: Credentials?
  @Published var person: Person?
  @Published var conversations: [Conversation] = []
  @Published var drafts: [Draft] = []
  @Published var error: String?
  @Published var notice: String?
  @Published var busy = false
  @Published var path: [Conversation] = []
  @Published var pendingInvite: String? = UserDefaults.standard.string(forKey: "pendingInvite")
  @Published var pendingTarget: (conversation: String, message: String)? {
    didSet {
      UserDefaults.standard.set(pendingTarget?.conversation, forKey: "notificationConversation")
      UserDefaults.standard.set(pendingTarget?.message, forKey: "notificationMessage")
    }
  }
  @Published var tab = 0
  @Published var isCapturing = false
  let playback = PlaybackController()
  let transfer = BackgroundTransfer()
  let thumbnails = ThumbnailStore()
  let diagnostics = DiagnosticsCollector()
  private(set) var store: LocalStore?
  private(set) var outbox: Outbox?
  var api: API? { credentials.map { API(endpoint: $0.endpoint, token: $0.token) } }
  private var monitor = NWPathMonitor()
  private var refreshTask: Task<Void, Never>?
  private var sessionID = UUID()
  private var registeredPush: String?
  var endpoint: URL {
    #if DEBUG
      if let value = ProcessInfo.processInfo.environment["GENOA_API_URL"],
        let url = URL(string: value), ["localhost", "127.0.0.1"].contains(url.host ?? "")
      {
        return url
      }
    #endif
    return Self.production
  }
  init() {
    monitor.pathUpdateHandler = { [weak self] status in
      if status.status == .satisfied { Task { @MainActor in await self?.refresh() } }
    }
    monitor.start(queue: DispatchQueue(label: "genoa.connectivity"))
    diagnostics.start()
  }
  func restore() async {
    do {
      #if DEBUG
        if ["localhost", "127.0.0.1"].contains(endpoint.host ?? ""),
          ProcessInfo.processInfo.environment["GENOA_UI_RESET"] == "1"
        {
          try SessionVault.clear()
        }
      #endif
      if let conversation = UserDefaults.standard.string(forKey: "notificationConversation"),
        let message = UserDefaults.standard.string(forKey: "notificationMessage")
      {
        pendingTarget = (conversation, message)
      }
      if let c = try SessionVault.read() {
        try await activate(c)
        await refresh()
        if let rows: [Reminder] = try? await api?.call("/api/reminders") {
          await syncReminderAlerts(rows)
        }
      }
    } catch { self.error = error.localizedDescription }
  }
  private func activate(_ c: Credentials) async throws {
    let store = try LocalStore(account: c.account)
    self.store = store
    self.credentials = c
    sessionID = UUID()
    let cached = await store.snapshot()
    person = cached.person
    conversations = cached.conversations
    drafts = cached.drafts.filter { $0.state != .discarded }
    let api = API(endpoint: c.endpoint, token: c.token)
    let outbox = Outbox(api: api, store: store, transfer: transfer)
    self.outbox = outbox
    await outbox.onChange { [weak self] in await self?.reloadLocal() }
    playback.configure(api: api, store: store) { [weak self] in await self?.reloadLocal() }
    diagnostics.attach { body in try? await store.enqueueDiagnostic(body) }
    for draft in cached.drafts where draft.state == .sent {
      try? await store.releaseAcceptedMedia(draft.id)
    }
    // A terminated capture is never silently sent. Recover finalized playable files only.
    for var draft in cached.drafts where draft.state == .recording {
      let file = await store.file(draft.filename)
      if let duration = try? await AVURLAsset(url: file).load(.duration),
        duration.seconds.isFinite && duration.seconds > 0
      {
        draft.durationMs = Int64(duration.seconds * 1000)
        draft.state = draft.live ? .queued : .saved
        draft.liveEnded = draft.live
      } else {
        draft.state = .failed
        draft.error = "Recording was interrupted. The local file is preserved."
      }
      try await store.save(draft)
    }
    await reloadLocal()
  }
  func signIn(email: String, password: String, name: String?, invite: String) async {
    guard !busy else { return }
    error = nil
    busy = true
    defer { busy = false }
    do {
      let c = try await API(endpoint: endpoint).signIn(
        email: email, password: password, name: name, invite: invite)
      try SessionVault.save(c)
      try await activate(c)
      await refresh()
      if let invitation = pendingInvite { try await join(invitation) }
    } catch { self.error = error.localizedDescription }
  }
  @Published var loginProviders: [String] = []
  /// False until the server has an email provider; the reset link is hidden until then.
  @Published var passwordReset = false
  func loadLoginProviders() async {
    struct Providers: Decodable {
      let providers: [String]
      let passwordReset: Bool?
    }
    if let result: Providers = try? await API(endpoint: endpoint).call("/auth/mobile/providers") {
      loginProviders = result.providers.filter { ["google", "apple"].contains($0) }
      passwordReset = result.passwordReset == true
    }
  }
  /// The server answers identically for unknown emails, so the confirmation stays neutral.
  func requestPasswordReset(email: String) async throws {
    try await API(endpoint: endpoint).mutate(
      "/auth/request-password-reset",
      body: ["email": email, "redirectTo": page("reset-password").absoluteString])
  }
  /// Worker-hosted public pages: terms, privacy, support, reset-password.
  func page(_ name: String) -> URL { endpoint.appendingPathComponent(name) }
  func discard(_ draft: Draft) async throws {
    guard let outbox else { return }
    try await outbox.discard(draft.id)
    await reloadLocal()
  }
  func socialSignIn(provider: String, flow: SocialSignIn) async {
    guard !busy else { return }
    busy = true
    error = nil
    defer { busy = false }
    do {
      let credentials = try await flow.signIn(provider: provider, endpoint: endpoint)
      try SessionVault.save(credentials)
      try await activate(credentials)
      await refresh()
      if let invitation = pendingInvite { try await join(invitation) }
    } catch {
      let failure = error as NSError
      if failure.domain != ASWebAuthenticationSessionError.errorDomain
        || failure.code != ASWebAuthenticationSessionError.canceledLogin.rawValue
      {
        self.error = error.localizedDescription
      }
    }
  }
  func reloadLocal() async {
    guard let store else { return }
    let id = sessionID
    let state = await store.snapshot()
    guard id == sessionID else { return }
    drafts = state.drafts.filter { $0.state != .discarded }
    conversations = state.conversations
  }
  func refresh() async {
    guard let api, let store, !isCapturing else { return }
    let id = sessionID
    do {
      let me: Person = try await api.call("/api/me")
      guard id == sessionID else { return }
      person = me
      try await store.cache(person: me)
      await outbox?.run()
      let rows: [Conversation] = try await api.call("/api/conversations")
      guard id == sessionID else { return }
      conversations = rows
      if let playing = playback.message, !rows.contains(where: { $0.id == playing.conversationId })
      {
        playback.stop()
      }
      try await store.cache(conversations: rows)
      notice = nil
      await reloadLocal()
      await registerDeviceIfNeeded()
      await routePendingTarget()
      await DiagnosticReport.flush(store: store, api: api)
    } catch let e as APIError where e.status == 401 {
      await expireSession()
    } catch let e as APIError where e.suspended {
      await expireSession(message: e.message)
    } catch { notice = "Connection paused. Your saved messages are safe. Pull to refresh." }
  }
  func setActive(_ active: Bool) {
    refreshTask?.cancel()
    if active {
      refreshTask = Task { [weak self] in
        while !Task.isCancelled {
          await self?.refresh()
          try? await Task.sleep(for: .seconds(5))
        }
      }
    } else {
      playback.persist()
      AppDelegate.scheduleSync()
    }
  }
  func perform(_ operation: () async throws -> Void) async {
    do { try await operation() } catch is CancellationError {} catch let e as APIError
      where e.suspended && credentials != nil
    {
      await expireSession(message: e.message)
    } catch {
      self.error = error.localizedDescription
    }
  }
  func signOut() async {
    guard !isCapturing else {
      error = "Finish recording before signing out."
      return
    }
    do {
      if let token = UserDefaults.standard.string(forKey: "apnsToken"), let api {
        try await api.mutate("/api/devices/current", method: "DELETE", body: ["token": token])
      }
      try await api?.mutate("/auth/sign-out")
      try SessionVault.clear()
      await store?.dropDiagnostics()
      await clearSession()
    } catch { self.error = "Couldn’t finish signing out: \(error.localizedDescription)" }
  }
  /// Also used for suspension: drafts stay on this iPhone, nothing is shown as sent, and the
  /// server's explanation appears on the sign-in screen.
  func expireSession(
    message: String = "Your session expired. Sign in again to recover your saved drafts."
  ) async {
    playback.stop()
    diagnostics.detach()
    await clearReminderAlerts()
    await outbox?.stop()
    await transfer.cancelAll()
    await thumbnails.clear()
    credentials = nil
    person = nil
    conversations = []
    drafts = []
    path = []
    store = nil
    outbox = nil
    sessionID = UUID()
    error = message
  }
  func clearSession() async {
    playback.stop()
    diagnostics.detach()
    await clearReminderAlerts()
    await outbox?.stop()
    await transfer.cancelAll()
    await thumbnails.clear()
    credentials = nil
    person = nil
    conversations = []
    drafts = []
    path = []
    store = nil
    outbox = nil
    sessionID = UUID()
    tab = 0
  }
  func receive(url: URL) {
    if url.scheme == "genoa", url.host == "auth" { return }  // ASWebAuthenticationSession owns this callback.
    guard ["genoa", "gather"].contains(url.scheme ?? ""), url.host == "join",
      let token = url.pathComponents.last, UUID(uuidString: token) != nil
    else {
      error = "This invitation link is invalid."
      return
    }
    pendingInvite = token
    UserDefaults.standard.set(token, forKey: "pendingInvite")
  }
  func contacts() async throws -> [Contact] {
    guard let api else { return [] }
    return try await api.call("/api/people")
  }
  func startConversation(id: String, members: [String], invite: String) async throws -> URL {
    guard let api else { throw APIError(status: 401, message: "Sign in to start a conversation.") }
    struct Started: Decodable {
      let id: String
      let url: URL
    }
    let result: Started = try await api.call(
      "/api/conversations", method: "POST",
      body: ["id": id, "member_ids": members, "invite_id": invite])
    await refresh()
    return result.url
  }
  func rename(_ conversation: String, to title: String) async throws {
    try await api?.mutate(
      "/api/conversations/\(conversation)", method: "PATCH",
      body: ["title": title.trimmingCharacters(in: .whitespacesAndNewlines)])
    await refresh()
  }
  // APNs is not configured on every server, so this iPhone schedules its own alert for
  // each server reminder. The identifier matches the server's APNs collapse ID.
  private static let reminderPrefix = "reminder:"
  func alertForReminder(
    id: String, conversation: String, message: String, due: Date, prompt: Bool = true
  ) async -> Bool {
    let center = UNUserNotificationCenter.current()
    if prompt, await center.notificationSettings().authorizationStatus == .notDetermined {
      _ = try? await center.requestAuthorization(options: [.alert, .badge, .sound])
    }
    guard [.authorized, .provisional, .ephemeral].contains(
      await center.notificationSettings().authorizationStatus)
    else { return false }
    let content = UNMutableNotificationContent()
    content.title = "Time to reply"
    content.body = "Your saved reminder is ready"
    content.sound = .default
    content.userInfo = ["conversation_id": conversation, "message_id": message]
    let trigger = UNTimeIntervalNotificationTrigger(
      timeInterval: max(1, due.timeIntervalSinceNow), repeats: false)
    do {
      try await center.add(
        UNNotificationRequest(
          identifier: Self.reminderPrefix + id, content: content, trigger: trigger))
      return true
    } catch { return false }
  }
  func removeReminderAlert(_ id: String) {
    UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [
      Self.reminderPrefix + id
    ])
  }
  /// Matches pending alerts to the server list, covering reminders set or removed elsewhere.
  func syncReminderAlerts(_ reminders: [Reminder]) async {
    let center = UNUserNotificationCenter.current()
    let pending = Set(
      await center.pendingNotificationRequests().map(\.identifier).filter {
        $0.hasPrefix(Self.reminderPrefix)
      })
    let wanted = reminders.filter { $0.sent == 0 && Double($0.dueAt) / 1000 > Date().timeIntervalSince1970 }
    let keep = Set(wanted.map { Self.reminderPrefix + $0.id })
    center.removePendingNotificationRequests(withIdentifiers: Array(pending.subtracting(keep)))
    for r in wanted where !pending.contains(Self.reminderPrefix + r.id) {
      _ = await alertForReminder(
        id: r.id, conversation: r.conversationId, message: r.messageId,
        due: Date(timeIntervalSince1970: Double(r.dueAt) / 1000), prompt: false)
    }
  }
  private func clearReminderAlerts() async {
    let center = UNUserNotificationCenter.current()
    let ids = await center.pendingNotificationRequests().map(\.identifier).filter {
      $0.hasPrefix(Self.reminderPrefix)
    }
    center.removePendingNotificationRequests(withIdentifiers: ids)
  }
  func join(_ input: String) async throws {
    guard let api else { return }
    let token =
      URL(string: input)?.pathComponents.last.flatMap { UUID(uuidString: $0)?.uuidString } ?? input
    let result: IDResponse = try await api.call(
      "/api/join", method: "POST", body: ["token": token.lowercased()])
    pendingInvite = nil
    UserDefaults.standard.removeObject(forKey: "pendingInvite")
    await refresh()
    if let c = conversations.first(where: { $0.id == result.id }) { path = [c] }
  }
  func routePendingTarget() async {
    guard !isCapturing, let target = pendingTarget, let api else { return }
    do {
      let _: WatchPage = try await api.call(
        "/api/conversations/\(target.conversation)/watch?message=\(target.message)")
      if let c = conversations.first(where: { $0.id == target.conversation }) {
        path = [c]
        tab = 0
      }
    } catch let e as APIError where e.accessDenied {
      pendingTarget = nil
      error = "This message is no longer available."
    } catch { notice = "Your notification will open when the connection returns." }
  }
  func text(_ text: String, conversation: String, reply: String?, urgent: Bool) async throws {
    guard let store, let credentials else { return }
    let draft = Draft(
      conversationId: conversation, owner: credentials.account, filename: "", kind: "text",
      mime: "text/plain", replyTo: reply, text: text, urgent: urgent)
    try await store.save(draft)
    await reloadLocal()
    try await outbox?.send(draft.id)
  }
  func registerDeviceIfNeeded() async {
    guard let api, person?.capabilities?.apns == true,
      let token = UserDefaults.standard.string(forKey: "apnsToken")
    else { return }
    #if DEBUG
      let platform = "ios-sandbox"
    #else
      let platform = "ios"
    #endif
    let key = (credentials?.account ?? "") + "|" + token
    guard registeredPush != key else { return }
    do {
      try await api.mutate(
        "/api/devices", method: "PUT", body: ["token": token, "platform": platform])
      registeredPush = key
    } catch { notice = "Push registration is waiting to retry." }
  }
}

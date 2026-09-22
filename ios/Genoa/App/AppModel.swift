import AVFoundation
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
    drafts = cached.drafts
    let api = API(endpoint: c.endpoint, token: c.token)
    let outbox = Outbox(api: api, store: store, transfer: transfer)
    self.outbox = outbox
    await outbox.onChange { [weak self] in await self?.reloadLocal() }
    playback.configure(api: api, store: store) { [weak self] in await self?.reloadLocal() }
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
    } catch { self.error = error.localizedDescription }
  }
  func reloadLocal() async {
    guard let store else { return }
    let id = sessionID
    let state = await store.snapshot()
    guard id == sessionID else { return }
    drafts = state.drafts
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
    } catch let e as APIError where e.status == 401 {
      await expireSession()
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
    do { try await operation() } catch is CancellationError {} catch {
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
      await clearSession()
    } catch { self.error = "Couldn’t finish signing out: \(error.localizedDescription)" }
  }
  func expireSession() async {
    playback.stop()
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
    error = "Your session expired. Sign in again to recover your saved drafts."
  }
  func clearSession() async {
    playback.stop()
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
    guard ["genoa", "gather"].contains(url.scheme ?? ""), url.host == "join",
      let token = url.pathComponents.last, UUID(uuidString: token) != nil
    else {
      error = "This invitation link is invalid."
      return
    }
    pendingInvite = token
    UserDefaults.standard.set(token, forKey: "pendingInvite")
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

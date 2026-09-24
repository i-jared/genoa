import CryptoKit
import Foundation

actor LocalStore {
  struct State: Codable {
    var drafts: [Draft] = []
    var progress: [WatchProgress] = []
    var segments: [LiveSegment] = []
    var conversations: [Conversation] = []
    var pages: [String: WatchPage] = [:]
    var person: Person?
  }
  let directory: URL
  let account: String
  private var state: State
  init(account: String, root: URL? = nil) throws {
    self.account = account
    let hash = SHA256.hash(data: Data(account.utf8)).map { String(format: "%02x", $0) }.joined()
    directory =
      (root
      ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("Genoa")).appendingPathComponent(hash)
    try FileManager.default.createDirectory(
      at: directory, withIntermediateDirectories: true,
      attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
    let file = directory.appendingPathComponent("state.json")
    state =
      FileManager.default.fileExists(atPath: file.path)
      ? try JSONDecoder().decode(State.self, from: Data(contentsOf: file)) : State()
  }
  func snapshot() -> State { state }
  func file(_ name: String) -> URL { directory.appendingPathComponent(name) }
  private func commit(_ change: (inout State) -> Void) throws {
    var next = state
    change(&next)
    try JSONEncoder().encode(next).write(
      to: directory.appendingPathComponent("state.json"),
      options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    state = next
  }
  /// Thrown when late work tries to write a draft the person already discarded.
  struct DraftDiscarded: Error {}
  func save(_ draft: Draft) throws {
    guard draft.owner == account else {
      throw APIError(status: 0, message: "This draft belongs to a different account.")
    }
    // Writers hold whole-draft snapshots; a tombstone must never be overwritten by one.
    if state.drafts.contains(where: { $0.id == draft.id && $0.state == .discarded }) {
      throw DraftDiscarded()
    }
    try commit { s in
      s.drafts.removeAll { $0.id == draft.id }
      s.drafts.append(draft)
    }
  }
  func draft(_ id: String) -> Draft? { state.drafts.first { $0.id == id } }
  func isDiscarded(_ id: String) -> Bool {
    state.drafts.contains { $0.id == id && $0.state == .discarded }
  }
  /// Persists the terminal discard before any cleanup, so a crash, late network response,
  /// or background transfer cannot bring the draft back. Returns true when the server must
  /// still confirm deletion of the stable message ID.
  @discardableResult func discard(_ id: String) throws -> Bool {
    guard var draft = draft(id) else { return false }
    if draft.state == .discarded { return draft.discardRemote == true }
    guard draft.mayDiscard else {
      throw APIError(status: 0, message: "Finish live sharing before discarding.")
    }
    let remote = draft.mayExistOnServer
    draft.state = .discarded
    draft.discardRemote = remote
    draft.error = nil
    let names = localFiles(id, draft: draft)
    try commit { s in
      s.drafts.removeAll { $0.id == id }
      if remote { s.drafts.append(draft) }
      s.segments.removeAll { $0.messageId == id }
    }
    for name in names { try? FileManager.default.removeItem(at: file(name)) }
    return remote
  }
  /// Called only after the server confirmed that no message with this ID remains.
  func purgeDiscarded(_ id: String) throws {
    guard isDiscarded(id) else { return }
    let names = localFiles(id, draft: nil)
    try commit { $0.drafts.removeAll { $0.id == id && $0.state == .discarded } }
    for name in names { try? FileManager.default.removeItem(at: file(name)) }
  }
  private func localFiles(_ id: String, draft: Draft?) -> [String] {
    var names = state.segments.filter { $0.messageId == id }.map(\.filename)
    if let draft, !draft.filename.isEmpty { names.append(draft.filename) }
    names += ((try? FileManager.default.contentsOfDirectory(atPath: directory.path)) ?? []).filter {
      $0.hasPrefix(id + "-part-")
    }
    return names
  }
  func importFile(_ source: URL, draft: Draft) throws {
    let target = file(draft.filename)
    if source != target { try FileManager.default.copyItem(at: source, to: target) }
    try save(draft)
  }
  func cache(person: Person) throws { try commit { $0.person = person } }
  func cache(conversations: [Conversation]) throws {
    try commit { $0.conversations = conversations }
  }
  func cache(page: WatchPage, conversation: String) throws {
    try commit { $0.pages[conversation] = page }
  }
  func revoke(_ conversation: String) throws {
    try commit { s in
      s.pages[conversation] = nil
      s.conversations.removeAll { $0.id == conversation }
      s.progress.removeAll { $0.conversationId == conversation }
    }
  }
  func progress(message: Message, position: Int64, watched: Bool) throws {
    let previous = state.progress.first { $0.messageId == message.id }
    let finished = watched || message.watched == 1 || previous?.watched == true
    let next = WatchProgress(
      messageId: message.id, conversationId: message.conversationId,
      positionMs: max(0, min(86_400_000, position)), watched: finished)
    try commit { s in
      s.progress.removeAll { $0.messageId == message.id }
      s.progress.append(next)
      if finished && message.watched == 0 && previous?.watched != true
        && message.authorId != s.person?.id,
        let i = s.conversations.firstIndex(where: { $0.id == message.conversationId })
      {
        s.conversations[i].unread = max(0, s.conversations[i].unread - 1)
        if ["video", "audio"].contains(message.kind) {
          s.conversations[i].unwatched = max(0, s.conversations[i].unwatched - 1)
        }
      }
    }
  }
  func acknowledge(_ progress: WatchProgress) throws {
    try commit { s in
      if let i = s.progress.firstIndex(where: {
        $0.messageId == progress.messageId && $0.revision == progress.revision
      }) {
        s.progress[i].dirty = false
      }
    }
  }
  func removeProgress(_ id: String) throws {
    try commit { $0.progress.removeAll { $0.messageId == id } }
  }
  func apply(_ messages: [Message], offline: Bool = false) -> [Message] {
    messages.map { original in
      var m = original
      if let p = state.progress.first(where: { $0.messageId == m.id }), p.dirty || offline {
        m.positionMs = p.positionMs
        m.watched = p.watched || m.watched == 1 ? 1 : 0
      }
      return m
    }
  }
  /// Fragments waiting for upload; bounds live buffering during a slow connection.
  func pendingSegments(_ messageId: String) -> (count: Int, bytes: Int) {
    let rows = state.segments.filter { $0.messageId == messageId && !$0.uploaded }
    return (rows.count, rows.reduce(0) { $0 + ($1.bytes ?? 0) })
  }
  func segment(_ segment: LiveSegment, data: Data) throws {
    guard !isDiscarded(segment.messageId) else { throw DraftDiscarded() }
    try data.write(
      to: file(segment.filename),
      options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    try commit { $0.segments.append(segment) }
  }
  func acknowledgeSegment(_ segment: LiveSegment) throws {
    try commit { s in
      if let i = s.segments.firstIndex(where: { $0.id == segment.id }) {
        s.segments[i].uploaded = true
      }
    }
  }
  func chunk(_ draft: Draft, number: Int, size: Int) throws -> URL {
    guard !isDiscarded(draft.id) else { throw DraftDiscarded() }
    guard (1...8 * 1024 * 1024).contains(size), number > 0 else {
      throw APIError(status: 0, message: "Invalid upload chunk size.")
    }
    let input = try FileHandle(forReadingFrom: file(draft.filename))
    defer { try? input.close() }
    try input.seek(toOffset: UInt64((number - 1) * size))
    guard let data = try input.read(upToCount: size), !data.isEmpty else {
      throw APIError(status: 0, message: "Recording file is incomplete.")
    }
    let url = file("\(draft.id)-part-\(number)")
    try data.write(
      to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    return url
  }
  func releaseAcceptedMedia(_ id: String) throws {
    guard let accepted = draft(id), accepted.state == .sent else { return }
    var names = state.segments.filter { $0.messageId == id }.map(\.filename)
    if !accepted.filename.isEmpty { names.append(accepted.filename) }
    names += try FileManager.default.contentsOfDirectory(atPath: directory.path).filter {
      $0.hasPrefix(id + "-part-")
    }
    for name in names where FileManager.default.fileExists(atPath: file(name).path) {
      try FileManager.default.removeItem(at: file(name))
    }
    try commit { s in
      s.segments.removeAll { $0.messageId == id }
      if let i = s.drafts.firstIndex(where: { $0.id == id }) { s.drafts[i].filename = "" }
      let retired = s.drafts.filter { $0.state == .sent && $0.filename.isEmpty }.sorted {
        $0.createdAt > $1.createdAt
      }.dropFirst(100)
      let ids = Set(retired.map(\.id))
      s.drafts.removeAll { ids.contains($0.id) }
    }
  }
  // Diagnostics live in separate files so large reports never slow state.json commits.
  private var diagnosticsDirectory: URL { directory.appendingPathComponent("diagnostics") }
  func enqueueDiagnostic(_ body: Data) throws {
    guard !body.isEmpty, body.count <= DiagnosticReport.maxBody else { return }
    try FileManager.default.createDirectory(
      at: diagnosticsDirectory, withIntermediateDirectories: true,
      attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
    // Names sort oldest first, so the stamp must strictly increase even within one millisecond.
    let now = Int64(Date().timeIntervalSince1970 * 1000)
    let last = pendingDiagnostics().last.flatMap { Int64($0.lastPathComponent.prefix(15)) } ?? 0
    let stamp = String(format: "%015lld", max(now, last + 1))
    try body.write(
      to: diagnosticsDirectory.appendingPathComponent("\(stamp)-\(UUID().uuidString).json"),
      options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
    for old in pendingDiagnostics().dropLast(DiagnosticReport.queueLimit) {
      try? FileManager.default.removeItem(at: old)
    }
  }
  /// Oldest first.
  func pendingDiagnostics() -> [URL] {
    let names =
      (try? FileManager.default.contentsOfDirectory(atPath: diagnosticsDirectory.path)) ?? []
    return names.filter { $0.hasSuffix(".json") }.sorted().map {
      diagnosticsDirectory.appendingPathComponent($0)
    }
  }
  func removeDiagnostic(_ file: URL) {
    guard file.deletingLastPathComponent().standardizedFileURL == diagnosticsDirectory.standardizedFileURL
    else { return }
    try? FileManager.default.removeItem(at: file)
  }
  func dropDiagnostics() { try? FileManager.default.removeItem(at: diagnosticsDirectory) }
  func eraseAccount() throws {
    try FileManager.default.removeItem(at: directory)
    state = State()
  }

}

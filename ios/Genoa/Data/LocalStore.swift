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
  func save(_ draft: Draft) throws {
    guard draft.owner == account else {
      throw APIError(status: 0, message: "This draft belongs to a different account.")
    }
    try commit { s in
      s.drafts.removeAll { $0.id == draft.id }
      s.drafts.append(draft)
    }
  }
  func draft(_ id: String) -> Draft? { state.drafts.first { $0.id == id } }
  func remove(_ id: String) throws {
    guard let draft = draft(id), draft.mayDiscard else {
      throw APIError(status: 0, message: "Wait for sending to finish before discarding.")
    }
    try commit { $0.drafts.removeAll { $0.id == id } }
    if !draft.filename.isEmpty { try? FileManager.default.removeItem(at: file(draft.filename)) }
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
  func segment(_ segment: LiveSegment, data: Data) throws {
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
  func eraseAccount() throws {
    try FileManager.default.removeItem(at: directory)
    state = State()
  }

}

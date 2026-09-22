import Foundation

@MainActor
final class WatchModel: ObservableObject {
  @Published var messages: [Message] = []
  @Published var selected: String?
  @Published var hasOlder = false
  @Published var hasNewer = false
  @Published var loading = false
  @Published var error: String?
  @Published var finished = false
  private var api: API?
  private var store: LocalStore?
  private var cid = ""
  private var socket: URLSessionWebSocketTask?
  private var events: Task<Void, Never>?
  var current: Message? { messages.first { $0.id == selected } }
  func configure(conversation: String, api: API, store: LocalStore) {
    self.cid = conversation
    self.api = api
    self.store = store
  }
  func load(target: String? = nil) async {
    guard let api, let store, !loading else { return }
    loading = true
    defer { loading = false }
    do {
      var query = ""
      if let target {
        query = "?message=\(target)"
      } else if let current {
        query = "?around=\(current.seq)"
      }
      let page: WatchPage = try await api.call("/api/conversations/\(cid)/watch\(query)")
      let fresh = await store.apply(page.messages)
      let prior = selected
      messages.removeAll { $0.seq >= page.startSeq && $0.seq <= page.endSeq }
      messages.append(contentsOf: fresh)
      messages.sort { $0.seq < $1.seq }
      // Keep bounded in-memory history while preserving the focus neighborhood.
      if messages.count > 250, let index = messages.firstIndex(where: { $0.id == prior }) {
        messages = Array(messages[max(0, index - 100)..<min(messages.count, index + 101)])
      }
      hasOlder = page.hasOlder
      hasNewer = page.hasNewer
      if target != nil || selected == nil {
        selected = page.focusId ?? messages.last?.id
      } else if !messages.contains(where: { $0.id == selected }) {
        selected = nil
        error = "This message was deleted."
      }
      try await store.cache(page: page, conversation: cid)
      if error != "This message was deleted." { error = nil }
    } catch let e as APIError where e.accessDenied {
      messages = []
      selected = nil
      error = e.message
      if e.status != 401 { try? await store.revoke(cid) }
    } catch {
      if messages.isEmpty, let cached = await store.snapshot().pages[cid] {
        messages = await store.apply(cached.messages, offline: true)
        selected = cached.focusId
      }
      self.error = "Connection paused. Pull to refresh or try again."
    }
  }
  func page(older: Bool) async {
    guard let api, let store, !loading, let bound = older ? messages.first?.seq : messages.last?.seq
    else { return }
    loading = true
    defer { loading = false }
    do {
      let page: WatchPage = try await api.call(
        "/api/conversations/\(cid)/watch?\(older ? "before" : "after")=\(bound)")
      let ids = Set(messages.map(\.id))
      messages += await store.apply(page.messages).filter { !ids.contains($0.id) }
      messages.sort { $0.seq < $1.seq }
      if older { hasOlder = page.hasOlder } else { hasNewer = page.hasNewer }
    } catch { self.error = error.localizedDescription }
  }
  func select(_ message: Message) {
    selected = message.id
    finished = false
  }
  func advance() async -> Message? {
    guard let i = messages.firstIndex(where: { $0.id == selected }) else { return nil }
    if i + 1 == messages.count && hasNewer { await page(older: false) }
    if i + 1 < messages.count {
      selected = messages[i + 1].id
      return messages[i + 1]
    }
    finished = true
    return nil
  }
  func connect() {
    guard let api, let request = try? api.request("/api/conversations/\(cid)/events") else {
      return
    }
    socket = api.session.webSocketTask(with: request)
    socket?.resume()
    events = Task { [weak self] in
      while !Task.isCancelled, let self, let socket = self.socket {
        do {
          _ = try await socket.receive()
          await self.load()
        } catch { break }
      }
    }
  }
  func disconnect() {
    events?.cancel()
    socket?.cancel(with: .goingAway, reason: nil)
    socket = nil
  }
}

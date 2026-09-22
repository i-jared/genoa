import Foundation

// Persist each fragment independently of network speed. Only one uploader drains
// the durable list, so a weak connection cannot grow an in-memory video queue.
final class LiveSegmentSink: @unchecked Sendable {
  private let lock = NSLock()
  private var saving: Task<Void, Never>?
  private let store: LocalStore
  private let messageId: String
  private let uploader: LiveSegmentUploader
  private let status: @Sendable (String?) async -> Void
  init(
    store: LocalStore, outbox: Outbox, messageId: String,
    status: @escaping @Sendable (String?) async -> Void
  ) {
    self.store = store
    self.messageId = messageId
    self.status = status
    uploader = LiveSegmentUploader(
      store: store, outbox: outbox, messageId: messageId, status: status)
  }
  func append(_ data: Data, number: Int, duration: Double) {
    lock.lock()
    defer { lock.unlock() }
    let previous = saving
    let store = store
    let messageId = messageId
    let uploader = uploader
    let status = status
    saving = Task {
      await previous?.value
      do {
        let segment = LiveSegment(
          messageId: messageId, number: number,
          filename: "\(messageId)-\(number).\(number == -1 ? "mp4" : "m4s")", duration: duration)
        try await store.segment(segment, data: data)
        Task { await uploader.run() }
      } catch {
        await status(
          "Couldn’t save a live fragment. The full local recording is still being saved.")
      }
    }
  }
  func drain() async {
    let pending = lock.withLock { saving }
    await pending?.value
  }
}
private actor LiveSegmentUploader {
  let store: LocalStore
  let outbox: Outbox
  let messageId: String
  let status: @Sendable (String?) async -> Void
  private var running = false
  init(
    store: LocalStore, outbox: Outbox, messageId: String,
    status: @escaping @Sendable (String?) async -> Void
  ) {
    self.store = store
    self.outbox = outbox
    self.messageId = messageId
    self.status = status
  }
  func run() async {
    guard !running else { return }
    running = true
    defer { running = false }
    do {
      while await store.snapshot().segments.contains(where: {
        $0.messageId == messageId && !$0.uploaded
      }) {
        try Task.checkCancellation()
        try await outbox.uploadSegments(messageId)
        await status(nil)
      }
    } catch {
      await status("Live connection paused. Your recording is saved locally and will retry.")
    }
  }
}

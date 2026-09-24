import Foundation

/// What the recorder shows about live delivery. The full local recording continues in every
/// case; only the live fragments viewers watch are affected.
enum LiveDelivery: Equatable {
  case sharing
  /// Transient: fragments are saved and will retry.
  case paused(String)
  /// Fragments are no longer published for this recording (revoked access, ended stream,
  /// backlog or storage bound). The person should stop; the full replay sends afterwards.
  case stopped(String)
  var message: String? {
    switch self {
    case .sharing: return nil
    case .paused(let text), .stopped(let text): return text
    }
  }
}

// Persist each fragment independently of network speed. Only one uploader drains
// the durable list, so a weak connection cannot grow an in-memory video queue.
// Disk use is bounded: the server playlist only plays a contiguous prefix, so once the
// backlog or free-space bound is reached, later fragments could never be watched live and
// are not stored. The separate full MP4 remains the authoritative replay.
final class LiveSegmentSink: @unchecked Sendable {
  static let maxPendingFragments = 90  // about three minutes of two-second fragments
  static let maxPendingBytes = 192 * 1024 * 1024
  static let minimumFreeBytes: Int64 = 1024 * 1024 * 1024
  private let lock = NSLock()
  private var saving: Task<Void, Never>?
  private var halted = false
  private let store: LocalStore
  private let messageId: String
  private let uploader: LiveSegmentUploader
  private let status: @Sendable (LiveDelivery) async -> Void
  private let freeSpace: @Sendable () -> Int64?
  init(
    store: LocalStore, outbox: Outbox, messageId: String,
    freeSpace: @escaping @Sendable () -> Int64? = { LiveSegmentSink.availableCapacity() },
    status: @escaping @Sendable (LiveDelivery) async -> Void
  ) {
    self.store = store
    self.messageId = messageId
    self.status = status
    self.freeSpace = freeSpace
    uploader = LiveSegmentUploader(
      store: store, outbox: outbox, messageId: messageId, status: status)
  }
  static func availableCapacity() -> Int64? {
    let url = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    return try? url.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey])
      .volumeAvailableCapacityForImportantUsage
  }
  func append(_ data: Data, number: Int, duration: Double) {
    lock.lock()
    defer { lock.unlock() }
    let previous = saving
    let store = store
    let messageId = messageId
    let uploader = uploader
    let status = status
    let freeSpace = freeSpace
    saving = Task {
      await previous?.value
      if self.lock.withLock({ self.halted }) { return }
      if await uploader.stopped { return self.halt() }
      let pending = await store.pendingSegments(messageId)
      if number != -1,
        pending.count >= Self.maxPendingFragments
          || pending.bytes + data.count > Self.maxPendingBytes
      {
        await status(
          .stopped(
            "Live viewers can’t keep up with this connection, so live sharing paused. Your full recording is still being saved and will send when you finish."
          ))
        return self.halt()
      }
      if let free = freeSpace(), free < Self.minimumFreeBytes {
        await status(
          .stopped(
            "This iPhone is low on storage, so live sharing paused. Your full recording is still being saved; finish soon."
          ))
        return self.halt()
      }
      do {
        let segment = LiveSegment(
          messageId: messageId, number: number,
          filename: "\(messageId)-\(number).\(number == -1 ? "mp4" : "m4s")", duration: duration,
          bytes: data.count)
        try await store.segment(segment, data: data)
        Task { await uploader.run() }
      } catch {
        await status(
          .paused("Couldn’t save a live fragment. The full local recording is still being saved."))
      }
    }
  }
  private func halt() { lock.withLock { halted = true } }
  func drain() async {
    let pending = lock.withLock { saving }
    await pending?.value
  }
}
private actor LiveSegmentUploader {
  let store: LocalStore
  let outbox: Outbox
  let messageId: String
  let status: @Sendable (LiveDelivery) async -> Void
  private var running = false
  private(set) var stopped = false
  init(
    store: LocalStore, outbox: Outbox, messageId: String,
    status: @escaping @Sendable (LiveDelivery) async -> Void
  ) {
    self.store = store
    self.outbox = outbox
    self.messageId = messageId
    self.status = status
  }
  func run() async {
    guard !running, !stopped else { return }
    running = true
    defer { running = false }
    do {
      while await store.snapshot().segments.contains(where: {
        $0.messageId == messageId && !$0.uploaded
      }) {
        try Task.checkCancellation()
        try await outbox.uploadSegments(messageId)
        await status(.sharing)
      }
    } catch {
      if let e = error as? APIError, Self.terminal.contains(e.status) {
        // Retrying cannot succeed: access was revoked, the stream ended, or the server
        // rejected the fragment. Stop and let the person finish; the replay still sends.
        stopped = true
        let reason = e.message.trimmingCharacters(in: CharacterSet(charactersIn: ". "))
        await status(
          .stopped(
            "Live sharing stopped: \(reason). Your recording is still saved on this iPhone."))
      } else {
        await status(
          .paused("Live connection paused. Your recording is saved locally and will retry."))
      }
    }
  }
  static let terminal: Set<Int> = [401, 403, 404, 409, 410, 413, 422]
}

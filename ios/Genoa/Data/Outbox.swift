import Foundation
import UIKit

/// How a failed send step should be handled. Only transient failures retry automatically;
/// server rejections of the content wait for the person to retry or discard.
enum SendFailure: Equatable {
  case retryLater
  case signIn
  case blocked
  case needsDecision
  init(_ error: Error) {
    guard let e = error as? APIError else {
      self = .retryLater
      return
    }
    switch e.status {
    case 401: self = .signIn
    case 403 where e.suspended: self = .signIn
    case 403, 404, 410: self = .blocked
    case 408, 425, 429, 500...599: self = .retryLater
    // 0 is a local problem (empty/incomplete file); 400/409/413/422 are content rejections,
    // including storage_quota and unsupported_media.
    default: self = .needsDecision
    }
  }
}

actor Outbox {
  let api: API
  let store: LocalStore
  let transfer: BackgroundTransfer?
  private var running = false
  private var stopped = false
  /// The draft whose requests may currently be in flight. Its remote deletion waits until
  /// those requests finish, because a late setup request can still create the message.
  private var active: String?
  var didChange: (@Sendable () async -> Void)?
  init(api: API, store: LocalStore, transfer: BackgroundTransfer? = nil) {
    self.api = api
    self.store = store
    self.transfer = transfer
  }
  func onChange(_ callback: @escaping @Sendable () async -> Void) { didChange = callback }
  func stop() { stopped = true }
  func check() throws { if stopped || Task.isCancelled { throw CancellationError() } }
  /// Precedes every request for a draft, so no new request starts after a discard.
  private func check(_ id: String) async throws {
    try check()
    if await store.isDiscarded(id) { throw LocalStore.DraftDiscarded() }
  }
  func send(_ id: String) async throws {
    guard var draft = await store.draft(id), draft.owner == store.account else { return }
    guard ![.recording, .sent, .discarded].contains(draft.state) else { return }
    draft.state = .queued
    draft.error = nil
    try await store.save(draft)
    await didChange?()
    await run()
  }
  /// Tombstones first, then removes local media, cancels transfers, and reconciles the stable
  /// message ID with the server until it confirms deletion (also after relaunch).
  func discard(_ id: String) async throws {
    let remote = try await store.discard(id)
    await didChange?()
    guard remote else { return }
    await transfer?.cancel(draft: id)
    await reconcileDiscards()
  }
  func reconcileDiscards() async {
    for draft in await store.snapshot().drafts where draft.state == .discarded {
      guard !stopped else { return }
      guard draft.id != active else { continue }  // retried when that upload step returns
      do {
        try await api.mutate("/api/messages/\(draft.id)", method: "DELETE")
      } catch let e as APIError where e.status == 404 || e.status == 403 && !e.suspended {
        // 404 with none of our requests in flight: nothing can still create the message.
        // 403: the ID is not ours, so none of our content remains under it.
      } catch {
        continue  // Kept durably; the next foreground, connectivity, or background run retries.
      }
      try? await store.purgeDiscarded(draft.id)
      await didChange?()
    }
  }
  func run() async {
    guard !running, !stopped else { return }
    running = true
    defer { running = false }
    let background = await MainActor.run {
      UIApplication.shared.beginBackgroundTask(withName: "Send saved messages")
    }
    defer {
      Task { @MainActor in
        if background != .invalid { UIApplication.shared.endBackgroundTask(background) }
      }
    }
    do {
      try await syncProgress()
    } catch {
      // Durable revisions remain pending for the next foreground/background opportunity.
    }
    let drafts = await store.snapshot().drafts
    for draft in drafts where [.queued, .uploading, .processing].contains(draft.state) {
      do {
        try check()
        try await upload(draft)
      } catch is CancellationError { break } catch is LocalStore.DraftDiscarded {
        continue
      } catch {
        guard var current = await store.draft(draft.id), current.state != .discarded else {
          continue
        }
        let failure = SendFailure(error)
        let settling = current.live && (error as? APIError)?.status == 409
        switch failure {
        case .retryLater, .signIn: current.state = .queued
        case .blocked: current.state = .blocked
        // The server may still be ending a disconnected live stream; check again later.
        case .needsDecision: current.state = settling ? .queued : .failed
        }
        current.error =
          failure == .signIn
          ? "Sign in again to resume. Your recording is saved." : error.localizedDescription
        try? await store.save(current)
        await didChange?()
      }
    }
    await reconcileDiscards()
  }
  func syncProgress() async throws {
    for p in await store.snapshot().progress where p.dirty {
      try check()
      do {
        try await api.mutate(
          "/api/messages/\(p.messageId)/progress", method: "PUT",
          body: ["position_ms": p.positionMs, "watched": p.watched])
        try await store.acknowledge(p)
      } catch let e as APIError where [403, 404].contains(e.status) {
        try await store.removeProgress(p.messageId)
      }
    }
  }
  func uploadSegments(_ id: String) async throws {
    for s in await store.snapshot().segments.filter({ $0.messageId == id && !$0.uploaded }).sorted(
      by: { $0.number < $1.number })
    {
      try await check(id)
      let path =
        s.number == -1
        ? "/api/live/\(id)/init.mp4"
        : "/api/live/\(id)/segments/\(s.number)?duration=\(s.duration)&format=fmp4"
      try await api.put(path, file: store.file(s.filename))
      try await store.acknowledgeSegment(s)
    }
  }
  private func upload(_ initial: Draft) async throws {
    active = initial.id
    defer { active = nil }
    try await check(initial.id)
    var draft = initial
    if draft.live {
      do { try await uploadSegments(draft.id) } catch let error as APIError
        where error.status == 409 && draft.liveEnded
      {
        let page: WatchPage = try await api.call(
          "/api/conversations/\(draft.conversationId)/watch?message=\(draft.id)")
        guard page.messages.contains(where: { $0.id == draft.id && $0.state == "ready" }) else {
          throw error
        }
        // The server may have ended a disconnected stream. Publish the intact
        // local replay through the ordinary idempotent upload instead.
      }
      guard draft.liveEnded else { return }
      try await check(draft.id)
      try await api.mutate("/api/live/\(draft.id)/end")
    }
    if draft.kind == "text" {
      var body: [String: Any] = ["id": draft.id, "text": draft.text ?? "", "urgent": draft.urgent]
      if let reply = draft.replyTo { body["reply_to"] = reply }
      try await check(draft.id)
      try await api.mutate("/api/conversations/\(draft.conversationId)/messages", body: body)
    } else {
      let file = await store.file(draft.filename)
      let bytes = try file.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
      guard bytes > 0 else {
        throw APIError(status: 400, message: "The local recording is empty. Record a new message.")
      }
      draft.state = .uploading
      draft.error = nil
      try await store.save(draft)
      await didChange?()
      var body: [String: Any] = [
        "id": draft.id, "conversation_id": draft.conversationId, "kind": draft.kind,
        "mime": draft.mime, "bytes": bytes, "duration_ms": draft.durationMs,
      ]
      if let reply = draft.replyTo { body["reply_to"] = reply }
      try await check(draft.id)
      let setup: UploadSetup = try await api.call("/api/uploads", method: "POST", body: body)
      try await check(draft.id)
      if setup.state != "complete" {
        guard (1...8 * 1024 * 1024).contains(setup.chunkSize) else {
          throw APIError(status: 400, message: "Unsupported upload size.")
        }
        let status: UploadStatus = try await api.call("/api/uploads/\(draft.id)")
        let uploaded = Set(status.parts.map(\.number))
        let count = (bytes + setup.chunkSize - 1) / setup.chunkSize
        for number in 1...count {
          try await check(draft.id)
          if !uploaded.contains(number) {
            let chunk = try await store.chunk(draft, number: number, size: setup.chunkSize)
            if let transfer {
              var request = try api.request(
                "/api/uploads/\(draft.id)/parts/\(number)", method: "PUT")
              request.httpBody = nil
              request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
              try await transfer.upload(request: request, file: chunk, draft: draft.id)
            } else {
              try await api.put("/api/uploads/\(draft.id)/parts/\(number)", file: chunk)
            }
            try? FileManager.default.removeItem(at: chunk)
          }
          draft.percent = min(bytes, number * setup.chunkSize) * 100 / bytes
          try await store.save(draft)
          await didChange?()
        }
        try await check(draft.id)
        draft.state = .processing
        try await store.save(draft)
        await didChange?()
        try await api.mutate("/api/uploads/\(draft.id)/complete")
      }
    }
    draft.state = .sent
    draft.percent = 100
    draft.error = nil
    try await store.save(draft)
    try? await store.releaseAcceptedMedia(draft.id)
    await didChange?()
  }
}

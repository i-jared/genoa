import Foundation
import UIKit

actor Outbox {
  let api: API
  let store: LocalStore
  let transfer: BackgroundTransfer?
  private var running = false
  private var stopped = false
  var didChange: (@Sendable () async -> Void)?
  init(api: API, store: LocalStore, transfer: BackgroundTransfer? = nil) {
    self.api = api
    self.store = store
    self.transfer = transfer
  }
  func onChange(_ callback: @escaping @Sendable () async -> Void) { didChange = callback }
  func stop() { stopped = true }
  func check() throws { if stopped || Task.isCancelled { throw CancellationError() } }
  func send(_ id: String) async throws {
    guard var draft = await store.draft(id), draft.owner == store.account else { return }
    guard ![.recording, .sent].contains(draft.state) else { return }
    draft.state = .queued
    draft.error = nil
    try await store.save(draft)
    await didChange?()
    await run()
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
    for draft in drafts
    where [.queued, .uploading, .processing].contains(draft.state)
      || (draft.live && draft.liveEnded && draft.state == .failed)
    {
      do {
        try check()
        try await upload(draft)
      } catch is CancellationError { break } catch {
        guard var current = await store.draft(draft.id) else { continue }
        let status = (error as? APIError)?.status
        current.state =
          [403, 404].contains(status ?? 0)
          ? .blocked : (status == nil || status == 429 || (status ?? 0) >= 500 ? .queued : .failed)
        current.error =
          status == 401
          ? "Sign in again to resume. Your recording is saved." : error.localizedDescription
        try? await store.save(current)
        await didChange?()
      }
    }
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
      try check()
      let path =
        s.number == -1
        ? "/api/live/\(id)/init.mp4"
        : "/api/live/\(id)/segments/\(s.number)?duration=\(s.duration)&format=fmp4"
      try await api.put(path, file: store.file(s.filename))
      try await store.acknowledgeSegment(s)
    }
  }
  private func upload(_ initial: Draft) async throws {
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
      try await api.mutate("/api/live/\(draft.id)/end")
    }
    if draft.kind == "text" {
      var body: [String: Any] = ["id": draft.id, "text": draft.text ?? "", "urgent": draft.urgent]
      if let reply = draft.replyTo { body["reply_to"] = reply }
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
      let setup: UploadSetup = try await api.call("/api/uploads", method: "POST", body: body)
      try check()
      if setup.state != "complete" {
        guard (1...8 * 1024 * 1024).contains(setup.chunkSize) else {
          throw APIError(status: 400, message: "Unsupported upload size.")
        }
        let status: UploadStatus = try await api.call("/api/uploads/\(draft.id)")
        let uploaded = Set(status.parts.map(\.number))
        let count = (bytes + setup.chunkSize - 1) / setup.chunkSize
        for number in 1...count {
          try check()
          if !uploaded.contains(number) {
            let chunk = try await store.chunk(draft, number: number, size: setup.chunkSize)
            if let transfer {
              var request = try api.request(
                "/api/uploads/\(draft.id)/parts/\(number)", method: "PUT")
              request.httpBody = nil
              request.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
              try await transfer.upload(request: request, file: chunk)
            } else {
              try await api.put("/api/uploads/\(draft.id)/parts/\(number)", file: chunk)
            }
            try? FileManager.default.removeItem(at: chunk)
          }
          draft.percent = min(bytes, number * setup.chunkSize) * 100 / bytes
          try await store.save(draft)
          await didChange?()
        }
        try check()
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

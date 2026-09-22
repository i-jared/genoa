import AVFoundation
import UIKit
import XCTest

@testable import Genoa

final class IntegrationTests: XCTestCase {
  let endpoint = URL(string: "http://localhost:18888")!
  var api: API!
  var root: URL!
  override func setUp() async throws {
    let configuration = URLSessionConfiguration.ephemeral
    configuration.httpAdditionalHeaders = ["X-Forwarded-For": "192.0.2.\(Int.random(in: 1...250))"]
    let c = try await API(endpoint: endpoint, session: URLSession(configuration: configuration))
      .signIn(
        email: "\(UUID().uuidString)@example.test", password: "local-test-password-123",
        name: "iOS Integration", invite: "local-genoa-beta")
    api = API(endpoint: endpoint, token: c.token)
    XCTAssertEqual(api.mediaCookies.count, 1)
    XCTAssertFalse(api.mediaCookies[0].isSecure)
    root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
  }
  override func tearDown() async throws {
    try await api?.mutate("/api/account/delete", body: ["confirmation": "DELETE"])
    if let root { try? FileManager.default.removeItem(at: root) }
  }
  @MainActor
  func testNativeAPIUploadPlaybackProgressAndDeletion() async throws {
    let me: Person = try await api.call("/api/me")
    let cid = UUID().uuidString.lowercased()
    try await api.mutate("/api/conversations", body: ["id": cid, "title": "Native iOS test"])
    let rows: [Conversation] = try await api.call("/api/conversations")
    XCTAssertEqual(rows.first?.title, "Native iOS test")
    let store = try LocalStore(account: "test|\(me.id)", root: root)
    let draft = Draft(
      conversationId: cid, owner: "test|\(me.id)", filename: "native.mp4", kind: "video",
      mime: "video/mp4", durationMs: 3000, state: .saved)
    let file = await store.file(draft.filename)
    try await SyntheticVideo.write(file: file)
    try await store.save(draft)
    let outbox = Outbox(api: api, store: store)
    try await outbox.send(draft.id)
    let sent = await store.draft(draft.id)
    XCTAssertEqual(sent?.state, .sent, sent?.error ?? "")
    try await outbox.send(draft.id)
    let page: WatchPage = try await api.call("/api/conversations/\(cid)/watch")
    XCTAssertEqual(page.messages.count, 1)
    let message = try XCTUnwrap(page.messages.first)
    XCTAssertEqual(message.id, draft.id)
    let playback = PlaybackController()
    playback.configure(api: api, store: store) {}
    playback.select(message)
    for _ in 0..<100 {
      if playback.ready || playback.error != nil { break }
      try await Task.sleep(for: .milliseconds(100))
    }
    XCTAssertTrue(playback.ready, playback.error ?? "Playback did not become ready")
    playback.play()
    try await Task.sleep(for: .milliseconds(500))
    playback.pause()
    XCTAssertGreaterThan(playback.position, 0)
    playback.setRate(2)
    XCTAssertEqual(playback.rate, 2)
    try await store.progress(message: message, position: 3000, watched: true)
    try await outbox.syncProgress()
    let watched: WatchPage = try await api.call("/api/conversations/\(cid)/watch")
    XCTAssertEqual(watched.messages.first?.watched, 1)
    playback.stop()
    try await api.mutate("/api/messages/\(message.id)", method: "DELETE")
    do {
      _ = try await api.data(message.mediaPath!)
      XCTFail("Deleted media remained accessible")
    } catch let e as APIError { XCTAssertEqual(e.status, 404) }
  }
  @MainActor
  func testAppleSegmentedWriterPlaysThroughAuthorizedHLS() async throws {
    let cid = UUID().uuidString.lowercased()
    let mid = UUID().uuidString.lowercased()
    try await api.mutate("/api/conversations", body: ["id": cid, "title": "Apple live test"])
    try await api.mutate("/api/conversations/\(cid)/live", body: ["id": mid, "rotation": 0])
    let collector = SegmentCollector()
    try await SyntheticVideo.write(file: nil, delegate: collector)
    let segments = collector.snapshot()
    XCTAssertGreaterThanOrEqual(segments.count, 2)
    var number = 0
    let fixture = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("SyntheticHLS")
    try FileManager.default.createDirectory(at: fixture, withIntermediateDirectories: true)
    var playlist =
      "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:EVENT\n#EXT-X-MAP:URI=\"init.mp4\"\n"
    for segment in segments {
      let file = root.appendingPathComponent(UUID().uuidString)
      try segment.data.write(to: file)
      try segment.data.write(
        to: fixture.appendingPathComponent(segment.initialization ? "init.mp4" : "\(number).m4s"))
      if !segment.initialization { playlist += "#EXTINF:\(segment.duration),\n\(number).m4s\n" }
      let path =
        segment.initialization
        ? "/api/live/\(mid)/init.mp4"
        : "/api/live/\(mid)/segments/\(number)?duration=\(segment.duration)&format=fmp4"
      try await api.put(path, file: file)
      if !segment.initialization { number += 1 }
    }
    playlist += "#EXT-X-ENDLIST\n"
    try playlist.write(
      to: fixture.appendingPathComponent("playlist.m3u8"), atomically: true, encoding: .utf8)
    try await api.mutate("/api/live/\(mid)/end")
    let page: WatchPage = try await api.call("/api/conversations/\(cid)/watch")
    let message = try XCTUnwrap(page.messages.first)
    let store = try LocalStore(account: "live-test", root: root)
    let playback = PlaybackController()
    playback.configure(api: api, store: store) {}
    let layer = AVPlayerLayer(player: playback.player)
    layer.frame = CGRect(x: 0, y: 0, width: 160, height: 240)
    let window = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first?
      .windows.first
    window?.layer.addSublayer(layer)
    defer { layer.removeFromSuperlayer() }
    playback.select(message)
    for _ in 0..<150 {
      if playback.ready || playback.error != nil { break }
      try await Task.sleep(for: .milliseconds(100))
    }
    XCTAssertTrue(playback.ready, playback.error ?? "HLS did not become ready")
    playback.play()
    for _ in 0..<80 {
      if playback.position > 0.1 { break }
      try await Task.sleep(for: .milliseconds(100))
    }
    print(
      "HLS before pause", playback.player.timeControlStatus.rawValue,
      playback.player.reasonForWaitingToPlay?.rawValue ?? "none",
      playback.player.currentTime().seconds, "layer", layer.isReadyForDisplay)
    playback.pause()
    print(
      "HLS status", playback.player.timeControlStatus.rawValue, "reason",
      playback.player.reasonForWaitingToPlay?.rawValue ?? "none", "rate", playback.player.rate,
      "duration", playback.player.currentItem?.duration.seconds ?? -1)
    print(
      "HLS errors",
      playback.player.currentItem?.errorLog()?.events.map {
        "\($0.errorStatusCode):\($0.errorComment ?? "")"
      } ?? [])
    XCTAssertGreaterThan(playback.position, 0)
    playback.stop()
  }
}
final class SegmentCollector: NSObject, AVAssetWriterDelegate {
  struct Segment {
    let data: Data
    let initialization: Bool
    let duration: Double
  }
  private let lock = NSLock()
  private var values: [Segment] = []
  func assetWriter(
    _ writer: AVAssetWriter, didOutputSegmentData data: Data, segmentType: AVAssetSegmentType,
    segmentReport: AVAssetSegmentReport?
  ) {
    lock.lock()
    defer { lock.unlock() }
    values.append(
      Segment(
        data: Data(data), initialization: segmentType == .initialization,
        duration: max(0.01, segmentReport?.trackReports.first?.duration.seconds ?? 2)))
  }
  func snapshot() -> [Segment] {
    lock.lock()
    defer { lock.unlock() }
    return values
  }
}
enum SyntheticVideo {
  static func write(file: URL?, delegate: AVAssetWriterDelegate? = nil) async throws {
    let writer: AVAssetWriter
    if let file {
      writer = try AVAssetWriter(outputURL: file, fileType: .mp4)
    } else {
      writer = AVAssetWriter(contentType: .mpeg4Movie)
      writer.outputFileTypeProfile = .mpeg4AppleHLS
      writer.preferredOutputSegmentInterval = CMTime(seconds: 1, preferredTimescale: 600)
      writer.initialSegmentStartTime = .zero
      writer.delegate = delegate
    }
    let input = AVAssetWriterInput(
      mediaType: .video,
      outputSettings: [
        AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 160, AVVideoHeightKey: 240,
        AVVideoCompressionPropertiesKey: [
          AVVideoMaxKeyFrameIntervalDurationKey: 1, AVVideoAllowFrameReorderingKey: false,
        ],
      ])
    writer.add(input)
    let adaptor = AVAssetWriterInputPixelBufferAdaptor(
      assetWriterInput: input,
      sourcePixelBufferAttributes: [
        kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB,
        kCVPixelBufferWidthKey as String: 160, kCVPixelBufferHeightKey as String: 240,
      ])
    XCTAssertTrue(writer.startWriting())
    writer.startSession(atSourceTime: .zero)
    for frame in 0..<90 {
      while !input.isReadyForMoreMediaData {
        if writer.status == .failed { throw writer.error! }
        try await Task.sleep(for: .milliseconds(5))
      }
      var buffer: CVPixelBuffer?
      CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &buffer)
      let pixel = buffer!
      CVPixelBufferLockBaseAddress(pixel, [])
      memset(CVPixelBufferGetBaseAddress(pixel), Int32(40 + frame), CVPixelBufferGetDataSize(pixel))
      CVPixelBufferUnlockBaseAddress(pixel, [])
      XCTAssertTrue(
        adaptor.append(pixel, withPresentationTime: CMTime(value: Int64(frame), timescale: 30)))
    }
    input.markAsFinished()
    await writer.finishWriting()
    if let error = writer.error { throw error }
    XCTAssertEqual(writer.status, .completed)
  }
}

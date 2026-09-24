import XCTest

@testable import Genoa

final class LocalStoreTests: XCTestCase {
  var root: URL!
  override func setUpWithError() throws {
    root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
  }
  override func tearDownWithError() throws { try FileManager.default.removeItem(at: root) }
  func message() throws -> Message {
    try API.decoder.decode(
      Message.self,
      from: Data(
        #"{"id":"message","seq":1,"conversation_id":"conversation","author_id":"other","author_name":"Rosa","kind":"video","text":"","state":"ready","asset_id":"asset","mime":"video/mp4","duration_ms":6000,"created_at":1,"position_ms":0,"watched":0,"bookmarked":0,"reply_to":null,"urgent":0,"reactions":[],"transcript":null,"summary":null,"ai_status":"none"}"#
          .utf8))
  }
  func testDraftSurvivesRestartAndOtherAccountCannotReadIt() async throws {
    let store = try LocalStore(account: "server|alice", root: root)
    let draft = Draft(
      conversationId: "conversation", owner: "server|alice", filename: "recording.mp4",
      kind: "video", mime: "video/mp4", replyTo: "source")
    let file = await store.file(draft.filename)
    try Data([1, 2, 3]).write(to: file)
    try await store.save(draft)
    let reopened = try LocalStore(account: "server|alice", root: root)
    let recovered = await reopened.draft(draft.id)
    XCTAssertEqual(recovered, draft)
    let other = try LocalStore(account: "server|bob", root: root)
    let otherDrafts = await other.snapshot().drafts
    XCTAssertTrue(otherDrafts.isEmpty)
    do {
      try await other.save(draft)
      XCTFail("Cross-account write accepted")
    } catch {}
  }
  func testStaleProgressAcknowledgmentCannotLoseNewPositionAndCompletionIsMonotonic() async throws {
    let store = try LocalStore(account: "alice", root: root)
    let m = try message()
    try await store.progress(message: m, position: 1000, watched: false)
    let old = try unwrap(awaitValue: await store.snapshot().progress.first)
    try await store.progress(message: m, position: 6000, watched: true)
    try await store.acknowledge(old)
    let dirty = try unwrap(awaitValue: await store.snapshot().progress.first)
    XCTAssertTrue(dirty.dirty)
    XCTAssertTrue(dirty.watched)
    XCTAssertEqual(dirty.positionMs, 6000)
    try await store.progress(message: m, position: 0, watched: false)
    let current = try unwrap(awaitValue: await store.snapshot().progress.first)
    XCTAssertTrue(current.watched)
    XCTAssertEqual(current.positionMs, 0)
    let reopened = try LocalStore(account: "alice", root: root)
    let restored = await reopened.snapshot().progress.first
    XCTAssertEqual(restored, current)
  }
  func testActiveLiveDraftCannotBeDiscardedAndSavedDraftDeletesFile() async throws {
    let store = try LocalStore(account: "alice", root: root)
    var draft = Draft(
      conversationId: "conversation", owner: "alice", filename: "clip.mp4", kind: "video",
      mime: "video/mp4", state: .uploading, live: true)
    let file = await store.file(draft.filename)
    try Data([1]).write(to: file)
    try await store.save(draft)
    do {
      try await store.discard(draft.id)
      XCTFail("Discarded a live stream recipients may be watching")
    } catch {}
    XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
    draft.state = .saved
    draft.live = false
    try await store.save(draft)
    try await store.discard(draft.id)
    XCTAssertFalse(FileManager.default.fileExists(atPath: file.path))
  }
  func testInterruptedMultipartUploadResumesAtServerConfirmedPartAndFinalResponseCanBeRetried()
    async throws
  {
    let store = try LocalStore(account: "alice", root: root)
    let draft = Draft(
      conversationId: "conversation", owner: "alice", filename: "clip.mp4", kind: "video",
      mime: "video/mp4", state: .queued)
    let file = await store.file(draft.filename)
    try Data(repeating: 0x42, count: 12).write(to: file)
    try await store.save(draft)
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [StubProtocol.self]
    let api = API(
      endpoint: URL(string: "https://test.invalid")!, token: "test",
      session: URLSession(configuration: config))
    var parts: [String] = []
    var finalized = false
    StubProtocol.handler = { request in
      let path = request.url!.path
      if path == "/api/uploads" {
        return (
          200,
          "{\"id\":\"\(draft.id)\",\"chunk_size\":8,\"state\":\"\(finalized ? "complete" : "open")\"}"
        )
      }
      if path == "/api/uploads/\(draft.id)" {
        return (200, #"{"state":"open","parts":[{"number":1,"bytes":8}]}"#)
      }
      if path.contains("/parts/") {
        parts.append(path)
        return (200, #"{"ok":true}"#)
      }
      if path.hasSuffix("/complete") {
        finalized = true
        throw URLError(.networkConnectionLost)
      }
      return (200, #"{"ok":true}"#)
    }
    let first = Outbox(api: api, store: store)
    await first.run()
    let uncertain = await store.draft(draft.id)
    XCTAssertEqual(uncertain?.state, .queued)
    XCTAssertEqual(parts, ["/api/uploads/\(draft.id)/parts/2"])
    let reopened = try LocalStore(account: "alice", root: root)
    await Outbox(api: api, store: reopened).run()
    let accepted = await reopened.draft(draft.id)
    XCTAssertEqual(accepted?.state, .sent)
    XCTAssertEqual(parts.count, 1)
  }
  func testRevokedUploadKeepsLocalRecordingAndDoesNotRetryForever() async throws {
    let store = try LocalStore(account: "alice", root: root)
    let draft = Draft(
      conversationId: "conversation", owner: "alice", filename: "clip.mp4", kind: "video",
      mime: "video/mp4", state: .queued)
    let file = await store.file(draft.filename)
    try Data([1]).write(to: file)
    try await store.save(draft)
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [StubProtocol.self]
    let api = API(
      endpoint: URL(string: "https://test.invalid")!, token: "test",
      session: URLSession(configuration: config))
    var calls = 0
    StubProtocol.handler = { _ in
      calls += 1
      return (403, #"{"error":"Membership removed"}"#)
    }
    let outbox = Outbox(api: api, store: store)
    await outbox.run()
    await outbox.run()
    let blocked = await store.draft(draft.id)
    XCTAssertEqual(blocked?.state, .blocked)
    XCTAssertEqual(calls, 1)
    XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
  }
  func testLiveFragmentsPersistWhileNetworkUploadIsBlocked() async throws {
    let store = try LocalStore(account: "alice", root: root)
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [StubProtocol.self]
    let api = API(
      endpoint: URL(string: "https://test.invalid")!, token: "test",
      session: URLSession(configuration: config))
    let gate = DispatchSemaphore(value: 0)
    let started = expectation(description: "Upload started")
    StubProtocol.handler = { request in
      if request.url?.path.hasSuffix("init.mp4") == true {
        started.fulfill()
        gate.wait()
      }
      return (200, #"{"ok":true}"#)
    }
    let outbox = Outbox(api: api, store: store)
    let sink = LiveSegmentSink(store: store, outbox: outbox, messageId: "live") { _ in }
    sink.append(Data([1, 2, 3]), number: -1, duration: 2)
    await fulfillment(of: [started], timeout: 2)
    sink.append(Data([4, 5, 6]), number: 0, duration: 2)
    let saved = expectation(description: "Second fragment saved before upload finishes")
    Task {
      await sink.drain()
      saved.fulfill()
    }
    await fulfillment(of: [saved], timeout: 2)
    let segments = await store.snapshot().segments
    XCTAssertEqual(segments.count, 2)
    XCTAssertFalse(segments.contains(where: \.uploaded))
    gate.signal()
    for _ in 0..<100 {
      if await store.snapshot().segments.allSatisfy(\.uploaded) { break }
      try await Task.sleep(for: .milliseconds(20))
    }
    let uploaded = await store.snapshot().segments
    XCTAssertTrue(uploaded.allSatisfy(\.uploaded))
    await outbox.stop()
  }
  func testMalformedStateIsNotSilentlyOverwritten() async throws {
    let store = try LocalStore(account: "alice", root: root)
    let file = await store.file("state.json")
    try Data("broken".utf8).write(to: file)
    XCTAssertThrowsError(try LocalStore(account: "alice", root: root))
    XCTAssertEqual(try String(contentsOf: file, encoding: .utf8), "broken")
  }
}
private func unwrap<T>(awaitValue value: T?, file: StaticString = #filePath, line: UInt = #line)
  throws -> T
{ try XCTUnwrap(value, file: file, line: line) }
final class StubProtocol: URLProtocol {
  static var handler: ((URLRequest) throws -> (Int, String))?
  override class func canInit(with request: URLRequest) -> Bool { true }
  override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
  override func startLoading() {
    do {
      let (status, body) = try Self.handler!(request)
      let response = HTTPURLResponse(
        url: request.url!, statusCode: status, httpVersion: nil,
        headerFields: ["Content-Type": "application/json"])!
      client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
      client?.urlProtocol(self, didLoad: Data(body.utf8))
      client?.urlProtocolDidFinishLoading(self)
    } catch { client?.urlProtocol(self, didFailWithError: error) }
  }
  override func stopLoading() {}
}

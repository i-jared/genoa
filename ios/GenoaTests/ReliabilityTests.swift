import XCTest

@testable import Genoa

/// Requests seen by the stub server, readable from the test after the work completes.
private final class Requests: @unchecked Sendable {
  private let lock = NSLock()
  private var rows: [String] = []
  func add(_ request: URLRequest) {
    lock.withLock { rows.append("\(request.httpMethod ?? "GET") \(request.url!.path)") }
  }
  var all: [String] { lock.withLock { rows } }
}

final class ReliabilityTests: XCTestCase {
  var root: URL!
  override func setUpWithError() throws {
    root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
    try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
  }
  override func tearDownWithError() throws {
    StubProtocol.handler = nil
    GatedProtocol.handler = nil
    GatedProtocol.gate = nil
    try FileManager.default.removeItem(at: root)
  }
  func api() -> API {
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [StubProtocol.self]
    return API(
      endpoint: URL(string: "https://test.invalid")!, token: "test",
      session: URLSession(configuration: config))
  }
  func queuedVideo(_ store: LocalStore, state: DraftState = .queued) async throws -> Draft {
    let draft = Draft(
      conversationId: "conversation", owner: "alice", filename: "clip.mp4", kind: "video",
      mime: "video/mp4", state: state)
    try Data(repeating: 7, count: 12).write(to: await store.file(draft.filename))
    try await store.save(draft)
    return draft
  }

  // MARK: Discard

  func testDiscardDuringFinalizationWaitsForLateServerAcceptanceThenDeletesRemotely()
    async throws
  {
    let store = try LocalStore(account: "alice", root: root)
    let draft = try await queuedVideo(store)
    let seen = Requests()
    let finalizing = expectation(description: "Finalization request in flight")
    let release = DispatchSemaphore(value: 0)
    GatedProtocol.gate = { $0.url!.path.hasSuffix("/complete") ? release : nil }
    GatedProtocol.handler = { request in
      seen.add(request)
      let path = request.url!.path
      if path == "/api/uploads" { return (201, #"{"id":"x","chunk_size":8,"state":"open"}"#) }
      if path == "/api/uploads/\(draft.id)" { return (200, #"{"state":"open","parts":[]}"#) }
      if path.hasSuffix("/complete") {
        finalizing.fulfill()
        // The server publishes after the person has already discarded.
        return (200, #"{"state":"ready"}"#)
      }
      return (200, #"{"ok":true}"#)
    }
    let config = URLSessionConfiguration.ephemeral
    config.protocolClasses = [GatedProtocol.self]
    let gated = API(
      endpoint: URL(string: "https://test.invalid")!, token: "test",
      session: URLSession(configuration: config))
    let outbox = Outbox(api: gated, store: store)
    let sending = Task { await outbox.run() }
    await fulfillment(of: [finalizing], timeout: 5)
    try await outbox.discard(draft.id)
    let tombstone = await store.draft(draft.id)
    XCTAssertEqual(tombstone?.state, .discarded)
    let reopenedDuringUpload = try LocalStore(account: "alice", root: root)
    let persisted = await reopenedDuringUpload.draft(draft.id)
    XCTAssertEqual(persisted?.state, .discarded, "Tombstone must be durable before cleanup")
    XCTAssertFalse(
      seen.all.contains("DELETE /api/messages/\(draft.id)"),
      "A 404 now would not prove the in-flight finalization cannot create the message")
    let clip = await store.file("clip.mp4")
    XCTAssertFalse(FileManager.default.fileExists(atPath: clip.path))
    release.signal()
    await sending.value
    let requests = seen.all
    let completeIndex = try XCTUnwrap(requests.firstIndex { $0.hasSuffix("/complete") })
    let deleteIndex = try XCTUnwrap(requests.firstIndex(of: "DELETE /api/messages/\(draft.id)"))
    XCTAssertGreaterThan(deleteIndex, completeIndex)
    let remaining = await store.snapshot().drafts
    XCTAssertTrue(remaining.isEmpty, "Late acceptance must not resurrect the draft as Sent")
    let reopened = try LocalStore(account: "alice", root: root)
    let afterRestart = await reopened.snapshot().drafts
    XCTAssertTrue(afterRestart.isEmpty)
  }

  func testDiscardWhileOfflineSurvivesRelaunchAndRetriesUntilServerConfirms() async throws {
    let store = try LocalStore(account: "alice", root: root)
    let draft = try await queuedVideo(store, state: .failed)
    StubProtocol.handler = { _ in throw URLError(.notConnectedToInternet) }
    try await Outbox(api: api(), store: store).discard(draft.id)
    let reopened = try LocalStore(account: "alice", root: root)
    let pending = await reopened.draft(draft.id)
    XCTAssertEqual(pending?.state, .discarded)
    // Neither a retry nor a stale writer can bring it back.
    let outbox = Outbox(api: api(), store: reopened)
    try await outbox.send(draft.id)
    do {
      try await reopened.save(draft)
      XCTFail("Stale snapshot overwrote the tombstone")
    } catch is LocalStore.DraftDiscarded {}
    let seen = Requests()
    StubProtocol.handler = { request in
      seen.add(request)
      return (404, #"{"error":"Message unavailable"}"#)
    }
    await outbox.run()
    XCTAssertEqual(seen.all, ["DELETE /api/messages/\(draft.id)"])
    let remaining = await reopened.snapshot().drafts
    XCTAssertTrue(remaining.isEmpty)
  }

  func testDiscardingNeverSentDraftDoesNotContactServer() async throws {
    let store = try LocalStore(account: "alice", root: root)
    let draft = try await queuedVideo(store, state: .saved)
    let seen = Requests()
    StubProtocol.handler = { request in
      seen.add(request)
      return (200, "{}")
    }
    try await Outbox(api: api(), store: store).discard(draft.id)
    XCTAssertTrue(seen.all.isEmpty)
    let remaining = await store.snapshot().drafts
    XCTAssertTrue(remaining.isEmpty)
    let clip = await store.file("clip.mp4")
    XCTAssertFalse(FileManager.default.fileExists(atPath: clip.path))
  }

  // MARK: Terminal server errors

  func testFailureClassification() {
    func kind(_ status: Int, _ code: String? = nil) -> SendFailure {
      SendFailure(APIError(status: status, message: "", code: code))
    }
    XCTAssertEqual(kind(413, "storage_quota"), .needsDecision)
    XCTAssertEqual(kind(422, "unsupported_media"), .needsDecision)
    XCTAssertEqual(kind(403, "account_suspended"), .signIn)
    XCTAssertEqual(kind(401), .signIn)
    XCTAssertEqual(kind(403), .blocked)
    XCTAssertEqual(kind(410), .blocked)
    XCTAssertEqual(kind(429), .retryLater)
    XCTAssertEqual(kind(503), .retryLater)
    XCTAssertEqual(SendFailure(URLError(.networkConnectionLost)), .retryLater)
  }

  func testStorageQuotaStopsRetryingAndKeepsServerMessage() async throws {
    let store = try LocalStore(account: "alice", root: root)
    let draft = try await queuedVideo(store)
    let seen = Requests()
    StubProtocol.handler = { request in
      seen.add(request)
      return (413, #"{"error":"Your Genoa storage is full.","code":"storage_quota"}"#)
    }
    let outbox = Outbox(api: api(), store: store)
    await outbox.run()
    await outbox.run()
    let failed = await store.draft(draft.id)
    XCTAssertEqual(failed?.state, .failed)
    XCTAssertEqual(failed?.error, "Your Genoa storage is full.")
    XCTAssertEqual(failed?.mayDiscard, true)
    XCTAssertEqual(seen.all.count, 1, "A quota rejection must wait for Retry or Discard")
  }

  func testUnsupportedMediaAtFinalizationIsTerminal() async throws {
    let store = try LocalStore(account: "alice", root: root)
    let draft = try await queuedVideo(store)
    StubProtocol.handler = { request in
      let path = request.url!.path
      if path == "/api/uploads" { return (201, #"{"id":"x","chunk_size":8,"state":"open"}"#) }
      if path == "/api/uploads/\(draft.id)" { return (200, #"{"state":"open","parts":[]}"#) }
      if path.hasSuffix("/complete") {
        return (422, #"{"error":"This file couldn't be played.","code":"unsupported_media"}"#)
      }
      return (200, #"{"ok":true}"#)
    }
    await Outbox(api: api(), store: store).run()
    let failed = await store.draft(draft.id)
    XCTAssertEqual(failed?.state, .failed)
    XCTAssertEqual(failed?.error, "This file couldn't be played.")
  }

  // MARK: Live fragments

  func testLiveFragmentsStopWhenBacklogBoundIsReached() async throws {
    let store = try LocalStore(account: "alice", root: root)
    StubProtocol.handler = { _ in (503, #"{"error":"Unavailable"}"#) }
    let states = Requests()
    let sink = LiveSegmentSink(
      store: store, outbox: Outbox(api: api(), store: store), messageId: "live",
      freeSpace: { Int64.max }
    ) { state in
      if case .stopped = state { states.add(URLRequest(url: URL(string: "https://stopped")!)) }
    }
    for number in -1..<(LiveSegmentSink.maxPendingFragments + 10) {
      sink.append(Data([1]), number: number, duration: 2)
    }
    await sink.drain()
    let pending = await store.pendingSegments("live")
    XCTAssertEqual(pending.count, LiveSegmentSink.maxPendingFragments)
    XCTAssertFalse(states.all.isEmpty, "The recorder must be told live sharing stopped")
  }

  func testLowStorageStopsLiveFragments() async throws {
    let store = try LocalStore(account: "alice", root: root)
    StubProtocol.handler = { _ in (200, #"{"ok":true}"#) }
    let stops = Requests()
    let sink = LiveSegmentSink(
      store: store, outbox: Outbox(api: api(), store: store), messageId: "live",
      freeSpace: { 10 * 1024 * 1024 }
    ) { state in
      if case .stopped = state { stops.add(URLRequest(url: URL(string: "https://stopped")!)) }
    }
    sink.append(Data([1]), number: 0, duration: 2)
    sink.append(Data([2]), number: 1, duration: 2)
    await sink.drain()
    let segments = await store.snapshot().segments
    XCTAssertTrue(segments.isEmpty)
    XCTAssertEqual(stops.all.count, 1)
  }

  func testRevokedLiveAccessStopsRetrying() async throws {
    let store = try LocalStore(account: "alice", root: root)
    let seen = Requests()
    let rejected = expectation(description: "Fragment rejected")
    StubProtocol.handler = { request in
      seen.add(request)
      return (403, #"{"error":"Membership removed"}"#)
    }
    let messages = Requests()
    let sink = LiveSegmentSink(
      store: store, outbox: Outbox(api: api(), store: store), messageId: "live",
      freeSpace: { Int64.max }
    ) { state in
      if case .stopped(let text) = state {
        messages.add(URLRequest(url: URL(string: "https://x/\(text.count)")!))
        rejected.fulfill()
      }
    }
    sink.append(Data([1]), number: -1, duration: 2)
    await fulfillment(of: [rejected], timeout: 5)
    for number in 0..<5 { sink.append(Data([1]), number: number, duration: 2) }
    await sink.drain()
    try await Task.sleep(for: .milliseconds(200))
    XCTAssertEqual(seen.all.count, 1, "Terminal errors must not be retried")
  }

  // MARK: Diagnostics

  func testDiagnosticsQueueIsBoundedDurableAndRetriesAfterTransientFailure() async throws {
    let store = try LocalStore(account: "alice", root: root)
    for i in 0..<(DiagnosticReport.queueLimit + 5) {
      try await store.enqueueDiagnostic(Data(#"{"n":\#(i)}"#.utf8))
    }
    try await store.enqueueDiagnostic(Data(count: DiagnosticReport.maxBody + 1))
    let reopened = try LocalStore(account: "alice", root: root)
    let queued = await reopened.pendingDiagnostics()
    XCTAssertEqual(queued.count, DiagnosticReport.queueLimit)
    XCTAssertEqual(try Data(contentsOf: queued[0]), Data(#"{"n":5}"#.utf8), "Oldest are dropped")
    var calls = 0
    StubProtocol.handler = { _ in
      calls += 1
      return calls == 1 ? (202, #"{"ok":true}"#) : (503, #"{"error":"busy"}"#)
    }
    await DiagnosticReport.flush(store: reopened, api: api())
    let remaining = await reopened.pendingDiagnostics()
    XCTAssertEqual(remaining.count, DiagnosticReport.queueLimit - 1)
    XCTAssertEqual(calls, 2, "A transient failure stops this attempt and keeps the report")
    StubProtocol.handler = { _ in (404, #"{"error":"Not found"}"#) }
    await DiagnosticReport.flush(store: reopened, api: api())
    let unrouted = await reopened.pendingDiagnostics()
    XCTAssertEqual(unrouted.count, DiagnosticReport.queueLimit - 1, "A server without the route keeps reports")
    await reopened.dropDiagnostics()
    let dropped = await reopened.pendingDiagnostics()
    XCTAssertTrue(dropped.isEmpty)
  }

  func testDiagnosticBodyRedactsSensitiveTextAndFitsServerLimit() throws {
    let frames = (0..<20_000).map { ["address": $0, "binaryName": "Genoa"] as [String: Any] }
    let payload: [String: Any] = [
      "diagnosticMetaData": [
        "exceptionReason": [
          "composedMessage": "Failed https://genoa.test/api/media/secret",
          "arguments": ["user text"], "exceptionName": "NSRangeException",
        ],
        "signal": "SIGABRT", "note": "Bearer abc",
      ],
      "callStackTree": ["callStacks": frames],
    ]
    let body = try XCTUnwrap(
      DiagnosticReport.body(
        kind: "crash", payload: payload, appVersion: "0.1.1", build: "2", osVersion: "iOS 26"))
    XCTAssertLessThanOrEqual(body.count, DiagnosticReport.maxBody)
    let text = String(decoding: body, as: UTF8.self)
    XCTAssertFalse(text.contains("https://"))
    XCTAssertFalse(text.contains("Bearer"))
    XCTAssertFalse(text.contains("user text"))
    XCTAssertTrue(text.contains("NSRangeException"))
    let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
    XCTAssertEqual(json["kind"] as? String, "crash")
    XCTAssertEqual(json["platform"] as? String, "ios")
    let huge: [String: Any] = ["other": String(repeating: "a", count: 2048)]
    let wide = Dictionary(uniqueKeysWithValues: (0..<200).map { ("k\($0)", huge) })
    XCTAssertNil(
      DiagnosticReport.body(
        kind: "hang", payload: wide, appVersion: "0.1.1", build: "2", osVersion: "iOS 26"),
      "Reports that cannot fit even without call stacks are omitted")
  }
}

/// Like StubProtocol, but a gated response is delivered later without blocking other requests,
/// so a request sent while the gated one is in flight is observable.
final class GatedProtocol: URLProtocol {
  static var handler: ((URLRequest) throws -> (Int, String))?
  static var gate: ((URLRequest) -> DispatchSemaphore?)?
  override class func canInit(with request: URLRequest) -> Bool { true }
  override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
  override func startLoading() {
    let result = Result { try Self.handler!(request) }
    let deliver = { [self] in
      switch result {
      case .success(let (status, body)):
        let response = HTTPURLResponse(
          url: request.url!, statusCode: status, httpVersion: nil,
          headerFields: ["Content-Type": "application/json"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(body.utf8))
        client?.urlProtocolDidFinishLoading(self)
      case .failure(let error): client?.urlProtocol(self, didFailWithError: error)
      }
    }
    if let semaphore = Self.gate?(request) {
      DispatchQueue.global().async {
        semaphore.wait()
        deliver()
      }
    } else {
      deliver()
    }
  }
  override func stopLoading() {}
}

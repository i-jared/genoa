import XCTest

@testable import Genoa

final class WatchSelectionTests: XCTestCase {
  func message(_ id: String, author: String = "friend", watched: Int = 0, position: Int64 = 0)
    throws -> Message
  {
    let json: [String: Any] = [
      "id": id, "seq": 1, "conversation_id": "conversation", "author_id": author,
      "author_name": author, "kind": "video", "state": "ready", "duration_ms": 6000,
      "created_at": 1, "position_ms": position, "watched": watched, "bookmarked": 0, "urgent": 0,
      "reactions": [],
    ]
    return try API.decoder.decode(Message.self, from: JSONSerialization.data(withJSONObject: json))
  }
  func testRecordingReturnStaysAtCameraUnlessAnIncomingMessageNeedsWatching() throws {
    let old = try message("old", watched: 1)
    let mine = try message("recorded", author: "me")
    let incoming = try message("new")
    XCTAssertNil(nextIncomingMessage([old, mine], userId: "me"))
    XCTAssertEqual(nextIncomingMessage([old, mine, incoming], userId: "me")?.id, "new")
    XCTAssertEqual(
      nextIncomingMessage([old, mine, incoming], userId: "me", after: "old")?.id, "new")
  }
  func conversation(members: Int, title: String = "", names: [String]?) throws -> Conversation {
    var json: [String: Any] = [
      "id": "c", "title": title.isEmpty ? "Ana" : title, "member_count": members, "unread": 0,
      "unwatched": 0, "favorite": 0, "muted": 0, "named": title.isEmpty ? 0 : 1,
    ]
    if let names { json["member_names"] = names }
    return try API.decoder.decode(
      Conversation.self, from: JSONSerialization.data(withJSONObject: json))
  }
  func testNamedTwoPersonConversationIsAGroupAndPreviewCountsHiddenMembers() throws {
    XCTAssertFalse(try conversation(members: 2, names: ["Ana"]).isGroup)
    let named = try conversation(members: 2, title: "Trail crew", names: ["Ana"])
    XCTAssertTrue(named.isGroup)
    XCTAssertEqual(named.memberSummary, "Ana")
    let large = try conversation(
      members: 7, title: "Family", names: ["Ana Ruiz", "Ben", "Cal", "Dee"])
    XCTAssertEqual(large.memberSummary, "Ana, Ben, Cal, Dee +2")
    XCTAssertEqual(
      try conversation(members: 1, title: "Waiting", names: []).memberSummary, "Only you so far")
    // Rows cached before the server sent member names still decode.
    XCTAssertTrue(try conversation(members: 3, names: nil).isGroup)
  }
  func testPartiallyWatchedMessageStaysInIncomingQueue() throws {
    let partial = try message("partial", position: 2000)
    XCTAssertEqual(nextIncomingMessage([partial], userId: "me")?.positionMs, 2000)
  }
  func testEncodingPreservesPortraitAndLandscapeSampleAspectWithoutSecondRotation() {
    for live in [false, true] {
      let portrait = encodedVideoSize(width: 1080, height: 1920, live: live)
      let landscape = encodedVideoSize(width: 1920, height: 1080, live: live)
      XCTAssertGreaterThan(portrait.height, portrait.width)
      XCTAssertGreaterThan(landscape.width, landscape.height)
      XCTAssertEqual(portrait.width, landscape.height)
      XCTAssertEqual(portrait.height, landscape.width)
      XCTAssertEqual(portrait.height, live ? 1280 : 1920)
    }
  }
}

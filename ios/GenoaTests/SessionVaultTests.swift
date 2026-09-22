import XCTest

@testable import Genoa

final class SessionVaultTests: XCTestCase {
  func testSessionPersistsInKeychainAndIsRemovedOnSignOut() throws {
    let saved = Credentials(
      endpoint: URL(string: "http://localhost:18888")!, userId: "synthetic-test-user",
      token: "synthetic-test-session")
    defer { try? SessionVault.clear() }
    try SessionVault.save(saved)
    XCTAssertEqual(try SessionVault.read(), saved)
    try SessionVault.clear()
    XCTAssertNil(try SessionVault.read())
  }
}

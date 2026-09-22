import XCTest

final class GenoaFlowTests: XCTestCase {
  @MainActor
  func testSignInConversationWatchProfileAndRecorderPreflight() async throws {
    let endpoint = "http://localhost:18888"
    let email = "\(UUID().uuidString)@example.test"
    let password = "local-test-password-123"
    func request(_ path: String, body: [String: Any], token: String? = nil) async throws -> (
      Data, HTTPURLResponse
    ) {
      var r = URLRequest(url: URL(string: endpoint + path)!)
      r.httpMethod = "POST"
      r.setValue(endpoint, forHTTPHeaderField: "Origin")
      r.setValue("application/json", forHTTPHeaderField: "Content-Type")
      r.setValue("local-genoa-beta", forHTTPHeaderField: "X-Invite-Code")
      if let token { r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
      r.httpBody = try JSONSerialization.data(withJSONObject: body)
      let (data, response) = try await URLSession.shared.data(for: r)
      return (data, response as! HTTPURLResponse)
    }
    let (_, auth) = try await request(
      "/auth/sign-up/email", body: ["email": email, "password": password, "name": "Rosa iOS"])
    XCTAssertEqual(auth.statusCode, 200)
    let token = try XCTUnwrap(auth.value(forHTTPHeaderField: "set-auth-token"))
    let cid = UUID().uuidString.lowercased()
    _ = try await request(
      "/api/conversations", body: ["id": cid, "title": "Family iOS"], token: token)
    _ = try await request(
      "/api/conversations/\(cid)/messages",
      body: ["id": UUID().uuidString.lowercased(), "text": "Hello from the native iOS test."],
      token: token)
    let app = XCUIApplication()
    app.launchEnvironment["GENOA_API_URL"] = endpoint
    app.launchEnvironment["GENOA_UI_RESET"] = "1"
    app.launch()
    if app.textFields["auth.email"].waitForExistence(timeout: 10) {
      app.textFields["auth.email"].tap()
      app.textFields["auth.email"].typeText(email)
      app.secureTextFields["auth.password"].tap()
      app.secureTextFields["auth.password"].typeText(password)
      app.buttons["auth.submit"].tap()
    }
    XCTAssertTrue(app.buttons["home.record"].waitForExistence(timeout: 15))
    let conversation = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Family iOS"))
      .firstMatch
    XCTAssertTrue(conversation.waitForExistence(timeout: 10))
    conversation.tap()
    XCTAssertTrue(app.staticTexts["Hello from the native iOS test."].waitForExistence(timeout: 10))
    attach(app, "Watch")
    app.buttons["Back"].tap()
    app.buttons["Profile"].tap()
    XCTAssertTrue(app.staticTexts["Rosa iOS"].waitForExistence(timeout: 5))
    attach(app, "Profile")
    app.buttons["Home"].tap()
    attach(app, "Home")
    app.buttons["home.record"].tap()
    XCTAssertTrue(app.navigationBars["Record for…"].waitForExistence(timeout: 5))
    app.buttons["recipient.\(cid)"].tap()
    XCTAssertTrue(app.staticTexts["recorder.status"].waitForExistence(timeout: 5))
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    for _ in 0..<2 {
      if springboard.alerts.buttons["Allow"].waitForExistence(timeout: 2) {
        springboard.alerts.buttons["Allow"].tap()
      }
    }
    attach(app, "Recorder")
    app.terminate()
    var verify = URLRequest(url: URL(string: endpoint + "/api/conversations/\(cid)/watch")!)
    verify.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
    let (history, _) = try await URLSession.shared.data(for: verify)
    let page = try XCTUnwrap(JSONSerialization.jsonObject(with: history) as? [String: Any])
    XCTAssertEqual(
      (page["messages"] as? [Any])?.count, 1, "Preflight must never publish a live message")
    _ = try await request("/api/account/delete", body: ["confirmation": "DELETE"], token: token)
  }
  @MainActor func attach(_ app: XCUIApplication, _ name: String) {
    let attachment = XCTAttachment(screenshot: app.screenshot())
    attachment.name = name
    attachment.lifetime = .keepAlways
    add(attachment)
  }
}

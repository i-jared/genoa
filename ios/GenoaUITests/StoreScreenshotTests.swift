import XCTest

/// Run explicitly after scripts/release/seed-screenshots.py on an isolated simulator.
final class StoreScreenshotTests: XCTestCase {
  @MainActor
  func testStoreScreenshots() throws {
    guard ProcessInfo.processInfo.environment["GENOA_STORE_SCREENSHOTS"] == "1" else {
      throw XCTSkip("Store capture requires the dedicated local screenshot fixture")
    }
    let app = XCUIApplication()
    app.launchEnvironment["GENOA_API_URL"] = "http://localhost:18888"
    app.launchEnvironment["GENOA_UI_RESET"] = "1"
    app.launchArguments = ["-AppleInterfaceStyle", "Light"]
    app.launch()
    XCTAssertTrue(app.textFields["auth.email"].waitForExistence(timeout: 15))
    app.textFields["auth.email"].tap()
    app.textFields["auth.email"].typeText("alex@store.example.test")
    app.secureTextFields["auth.password"].tap()
    app.secureTextFields["auth.password"].typeText("local-store-demo-password-123")
    app.buttons["auth.submit"].tap()
    XCTAssertTrue(app.buttons["home.record"].waitForExistence(timeout: 15))
    let family = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Family"))
      .firstMatch
    XCTAssertTrue(family.waitForExistence(timeout: 10))
    capture(app, "01-home")
    family.tap()
    XCTAssertTrue(app.buttons["Conversation details"].waitForExistence(timeout: 10))
    // The server supplies the real synthetic photo; allow its image decode to finish.
    sleep(3)
    capture(app, "02-watch")
    app.buttons["Conversation details"].tap()
    XCTAssertTrue(app.navigationBars["Conversation details"].waitForExistence(timeout: 10))
    XCTAssertTrue(app.staticTexts["Maya Morgan"].waitForExistence(timeout: 10))
    for _ in 0..<3 {
      if app.buttons["Private scratchpad"].exists && app.buttons["Private scratchpad"].isHittable {
        break
      }
      app.collectionViews.firstMatch.swipeUp()
    }
    capture(app, "03-people")
    app.buttons["Private scratchpad"].tap()
    XCTAssertTrue(app.textViews["Private note"].waitForExistence(timeout: 10))
    sleep(1)
    capture(app, "04-scratchpad")
    app.terminate()
  }

  @MainActor private func capture(_ app: XCUIApplication, _ name: String) {
    let attachment = XCTAttachment(screenshot: app.screenshot())
    attachment.name = name
    attachment.lifetime = .keepAlways
    add(attachment)
  }
}

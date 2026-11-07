import XCTest

/// One run against a real account, on a real network, through the real UI.
///
/// This answers questions the unit tests cannot: does a twenty-thousand-play
/// repo actually reach the screen, does the read report progress while it
/// works, does it finish, and does the next launch open from the saved copy
/// without asking for the handle again?
///
/// It types a public handle, so it needs no secrets and no fixtures. It is a
/// separate scheme (`FathomAccount`) because it takes minutes and cannot pass
/// offline, which is not something the default test run should inherit.
final class AccountRunTests: XCTestCase {

    private let handle = "ewancroft.uk"

    func testOpensAnAccountAndFinishesReadingItsHistory() {
        let app = XCUIApplication()
        app.launch()

        let field = app.textFields["handleField"]
        XCTAssertTrue(field.waitForExistence(timeout: 15), "the connect screen should appear")
        field.tap()
        field.typeText(handle)

        let open = app.buttons["connectButton"]
        XCTAssertTrue(open.isEnabled, "a handle should enable the button")
        open.tap()

        // The first page is on screen long before the read ends; that is the
        // point of publishing as it goes.
        let statsTab = app.tabBars.buttons["Stats"]
        XCTAssertTrue(statsTab.waitForExistence(timeout: 30), "the dashboard should open on the first page")
        statsTab.tap()

        let reading = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "plays so far")
        ).firstMatch
        XCTAssertTrue(reading.waitForExistence(timeout: 30), "the read should report how far it has got")

        // Ten minutes: a guard-limited read of fifty thousand plays is about
        // five on the connection this was measured against.
        let finished = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"),
            object: reading
        )
        XCTAssertEqual(
            XCTWaiter().wait(for: [finished], timeout: 600),
            .completed,
            "the read should reach the end of the history, or the runaway guard"
        )

        let shot = XCTAttachment(screenshot: app.screenshot())
        shot.name = "stats-after-read"
        shot.lifetime = .keepAlways
        add(shot)

        // The second launch must paint from the saved copy: no connect screen,
        // no typing, the tabs immediately.
        app.terminate()
        app.launch()

        XCTAssertTrue(
            app.tabBars.buttons["Stats"].waitForExistence(timeout: 8),
            "the saved copy should open the dashboard without reading the repo first"
        )
        XCTAssertFalse(app.textFields["handleField"].exists)

        // Not just "a dashboard appeared": the saved plays must be on screen
        // before the refresh's first empty page can blank them. This is the
        // regression the first run of this test could not see.
        let saved = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "plays so far")
        ).firstMatch
        XCTAssertTrue(saved.waitForExistence(timeout: 5), "the saved copy should repaint while refreshing")
        XCTAssertFalse(
            saved.label.contains("— 0 plays"),
            "the saved history was blanked by the first page: \(saved.label)"
        )
    }
}

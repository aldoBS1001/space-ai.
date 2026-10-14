import XCTest

final class LaunchUITests: XCTestCase {
    func testHomeShowsRingAndAnalyzeButton() {
        let app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(es-MX)", "-AppleLocale", "es_MX"]
        app.launch()
        XCTAssertTrue(app.staticTexts["SPACE AI"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.buttons["ANALIZAR MI TELÉFONO"].exists)
        // Captura como evidencia en el reporte de pruebas.
        let shot = XCTAttachment(screenshot: app.screenshot()); shot.name = "home-es-MX"; shot.lifetime = .keepAlways; add(shot)
    }
}

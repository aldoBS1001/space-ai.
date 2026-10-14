// swift-tools-version:5.9
import PackageDescription

// Motor central de SPACE AI para iOS: misma lógica y mismas pruebas que el motor Kotlin de Android.
// No depende de UIKit/Photos: se prueba con `swift test` en macOS (y Linux).
let package = Package(
    name: "SpaceAICore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "SpaceAICore", targets: ["SpaceAICore"])],
    targets: [
        .target(name: "SpaceAICore"),
        .testTarget(name: "SpaceAICoreTests", dependencies: ["SpaceAICore"]),
    ]
)

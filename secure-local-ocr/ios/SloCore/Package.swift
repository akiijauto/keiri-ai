// swift-tools-version:5.9
import PackageDescription

/// SLO の共有コア（iOS / iPadOS 用）。
///
/// Android 版 :core および Web 版 slo-core.js と同じ規則を実装する。
/// UIKit/SwiftUI に依存しないので、macOS 上で `swift test` だけでも検証できる。
let package = Package(
    name: "SloCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "SloCore", targets: ["SloCore"])
    ],
    targets: [
        .target(name: "SloCore"),
        .testTarget(name: "SloCoreTests", dependencies: ["SloCore"])
    ]
)

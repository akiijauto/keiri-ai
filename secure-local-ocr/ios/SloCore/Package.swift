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
    dependencies: [
        // Linux でのみ使う。Apple プラットフォームでは CryptoKit をそのまま使い、
        // このパッケージはリンクされない（下の condition を参照）。
        // 目的は共有ベクタを CI（Linux）で流せるようにすること。
        // これが無いと Swift 実装だけ検証されないまま残る。
        .package(url: "https://github.com/apple/swift-crypto.git", from: "3.0.0")
    ],
    targets: [
        .target(
            name: "SloCore",
            dependencies: [
                .product(name: "Crypto", package: "swift-crypto", condition: .when(platforms: [.linux]))
            ]
        ),
        .testTarget(name: "SloCoreTests", dependencies: ["SloCore"])
    ]
)

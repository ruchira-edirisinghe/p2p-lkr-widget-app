// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "P2PKit",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "P2PKit", targets: ["P2PKit"]),
    ],
    targets: [
        .target(name: "P2PKit"),
        .testTarget(
            name: "P2PKitTests",
            dependencies: ["P2PKit"],
            resources: [.copy("Fixtures")]
        ),
    ]
)

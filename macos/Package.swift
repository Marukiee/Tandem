// swift-tools-version: 6.2
import Foundation
import PackageDescription

// The Rust core is built into Libs/ by scripts/build-core-macos.sh.
let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path

let package = Package(
    name: "Tandem",
    platforms: [.macOS("26.0")],
    products: [
        .executable(name: "Tandem", targets: ["Tandem"]),
    ],
    dependencies: [
        // The terminal of the built in ssh window.
        .package(url: "https://github.com/migueldeicaza/SwiftTerm.git", exact: "1.5.0"),
    ],
    targets: [
        // The C header and module map that UniFFI generates for the Rust core.
        .target(
            name: "tandem_coreFFI",
            path: "Sources/tandem_coreFFI",
            publicHeadersPath: "include"
        ),
        // The generated Swift bindings.
        .target(
            name: "TandemCore",
            dependencies: ["tandem_coreFFI"],
            path: "Sources/TandemCore",
            swiftSettings: [.swiftLanguageMode(.v5)],
            linkerSettings: [
                .unsafeFlags(["-L", "\(root)/Libs"]),
                .linkedLibrary("tandem_core"),
                .linkedLibrary("resolv"),
                .linkedFramework("CoreFoundation"),
                .linkedFramework("Security"),
                .linkedFramework("SystemConfiguration"),
            ]
        ),
        .executableTarget(
            name: "Tandem",
            dependencies: ["TandemCore", .product(name: "SwiftTerm", package: "SwiftTerm")],
            path: "Sources/Tandem",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)

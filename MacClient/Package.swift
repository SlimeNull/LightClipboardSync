// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "LightClipboardSyncMac",
    platforms: [.macOS(.v13)],
    products: [.executable(name: "LightClipboardSyncMac", targets: ["LightClipboardSyncMac"])],
    targets: [.executableTarget(name: "LightClipboardSyncMac")]
)

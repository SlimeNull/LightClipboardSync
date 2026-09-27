import AppKit
import SwiftUI

@MainActor
final class AppLifecycle: NSObject, NSApplicationDelegate {
    func applicationDidFinishLaunching(_ notification: Notification) {
        NSApp.setActivationPolicy(.accessory)
        SyncModel.shared.start()
    }
}

struct MenuContent: View {
    @ObservedObject var model: SyncModel

    var body: some View {
        Text(model.status)
        Divider()
        Button("设置…") { SettingsWindowController.shared.show(model: model) }
        Button("退出") { NSApp.terminate(nil) }
    }
}

@main
struct LightClipboardSyncApp: App {
    @NSApplicationDelegateAdaptor(AppLifecycle.self) private var lifecycle
    @StateObject private var model = SyncModel.shared

    var body: some Scene {
        MenuBarExtra("剪贴板同步", systemImage: "doc.on.clipboard") {
            MenuContent(model: model)
        }
    }
}

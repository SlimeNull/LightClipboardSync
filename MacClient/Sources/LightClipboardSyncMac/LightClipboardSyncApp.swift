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
        if #available(macOS 14, *) {
            SettingsMenuButton()
        } else {
            Button("设置…") {
                SettingsWindowController.shared.show {
                    NSApp.sendAction(Selector(("showSettingsWindow:")), to: nil, from: nil)
                }
            }
        }
        Button("退出") { NSApp.terminate(nil) }
    }
}

@available(macOS 14, *)
private struct SettingsMenuButton: View {
    @Environment(\.openSettings) private var openSettings

    var body: some View {
        Button("设置…") {
            SettingsWindowController.shared.show { openSettings() }
        }
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
        Settings {
            SettingsView(model: model)
                .background(SettingsWindowReader())
        }
        .windowResizability(.contentSize)
    }
}

import AppKit
import SwiftUI

@MainActor
final class SettingsWindowController {
    static let shared = SettingsWindowController()

    private var window: NSWindow?

    func show(model: SyncModel) {
        let settingsWindow: NSWindow
        if let window {
            settingsWindow = window
        } else {
            let created = NSWindow(
                contentRect: NSRect(x: 0, y: 0, width: 540, height: 390),
                styleMask: [.titled, .closable, .miniaturizable, .resizable],
                backing: .buffered,
                defer: false
            )
            created.title = "剪贴板同步设置"
            created.contentView = NSHostingView(rootView: SettingsView(model: model))
            created.isReleasedWhenClosed = false
            created.center()
            window = created
            settingsWindow = created
        }

        NSApp.activate(ignoringOtherApps: true)
        settingsWindow.makeKeyAndOrderFront(nil)
    }
}

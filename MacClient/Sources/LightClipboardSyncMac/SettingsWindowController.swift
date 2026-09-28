import AppKit
import SwiftUI

@MainActor
final class SettingsWindowController {
    static let shared = SettingsWindowController()

    private weak var window: NSWindow?
    private var presentationRequested = false
    private var activationObserver: NSObjectProtocol?
    private var closeObserver: NSObjectProtocol?

    private init() {
        activationObserver = NotificationCenter.default.addObserver(
            forName: NSApplication.didBecomeActiveNotification, object: NSApp, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.orderWindow() }
        }
    }

    func show(openSettings: @escaping @MainActor () -> Void) {
        presentationRequested = true
        // Menu tracking must finish before application activation can take effect.
        RunLoop.main.perform(inModes: [.default]) { [weak self] in
            Task { @MainActor in
                NSApp.setActivationPolicy(.regular)
                openSettings()
                if #available(macOS 14, *) {
                    NSApp.activate()
                } else {
                    NSApp.activate(ignoringOtherApps: true)
                }
                self?.orderWindow()
            }
        }
    }

    func register(_ window: NSWindow) {
        if self.window !== window {
            self.window = window
            if let closeObserver { NotificationCenter.default.removeObserver(closeObserver) }
            closeObserver = NotificationCenter.default.addObserver(
                forName: NSWindow.willCloseNotification, object: window, queue: .main
            ) { [weak self] _ in
                Task { @MainActor in
                    self?.presentationRequested = false
                    NSApp.setActivationPolicy(.accessory)
                }
            }
        }
        window.collectionBehavior = [.moveToActiveSpace, .fullScreenAuxiliary]
        orderWindow()
    }

    private func orderWindow() {
        guard presentationRequested, let window else { return }
        if window.isMiniaturized { window.deminiaturize(nil) }
        window.makeKeyAndOrderFront(nil)
        window.orderFrontRegardless()
        if NSApp.isActive { presentationRequested = false }
    }
}

struct SettingsWindowReader: NSViewRepresentable {
    func makeNSView(context: Context) -> WindowReaderView {
        WindowReaderView(frame: .zero)
    }

    func updateNSView(_ nsView: WindowReaderView, context: Context) {
        if let window = nsView.window { SettingsWindowController.shared.register(window) }
    }

    final class WindowReaderView: NSView {
        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            if let window { SettingsWindowController.shared.register(window) }
        }
    }
}

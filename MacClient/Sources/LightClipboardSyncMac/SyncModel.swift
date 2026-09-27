import AppKit
import Combine
import Foundation

@MainActor
final class SyncModel: ObservableObject {
    static let shared = SyncModel()

    @Published private(set) var status = "连接中"
    @Published private(set) var settings: SyncSettings
    private let sync: ClipboardSync
    private var started = false

    private init() {
        let settings = SyncSettings.load()
        self.settings = settings
        sync = ClipboardSync(settings: settings)
        sync.onStatusChange = { [weak self] value in self?.status = value }
    }

    func start() {
        guard !started else { return }
        started = true
        sync.start()
    }

    func save(server: String, userID: String) -> Bool {
        guard let updated = SyncSettings.validated(server: server, userID: userID,
                                                   clientID: settings.clientID) else { return false }
        settings = updated
        status = "连接中"
        sync.update(settings: updated)
        return true
    }

    func copyID(_ value: String) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(value, forType: .string)
        sync.ignoreCurrentClipboardChange()
    }
}

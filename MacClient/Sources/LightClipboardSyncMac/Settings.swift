import Foundation

struct SyncSettings {
    var serverURL: URL
    var userID: UUID
    let clientID: UUID

    static func load() -> SyncSettings {
        let defaults = UserDefaults.standard
        let userID = UUID(uuidString: defaults.string(forKey: "userID") ?? "") ?? UUID()
        let clientID = UUID(uuidString: defaults.string(forKey: "clientID") ?? "") ?? UUID()
        let server = defaults.string(forKey: "serverURL") ?? "http://127.0.0.1:5078"
        let settings = validated(server: server, userID: userID.uuidString, clientID: clientID)
            ?? SyncSettings(serverURL: URL(string: "http://127.0.0.1:5078")!,
                            userID: userID, clientID: clientID)
        settings.persist()
        return settings
    }

    static func validated(server: String, userID: String, clientID: UUID) -> SyncSettings? {
        let trimmed = server.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let components = URLComponents(string: trimmed),
              let scheme = components.scheme?.lowercased(),
              scheme == "http" || scheme == "https",
              components.host != nil,
              components.user == nil,
              components.password == nil,
              components.query == nil,
              components.fragment == nil,
              let url = components.url,
              let uuid = UUID(uuidString: userID.trimmingCharacters(in: .whitespacesAndNewlines))
        else { return nil }
        return SyncSettings(serverURL: url, userID: uuid, clientID: clientID)
    }

    func persist() {
        let defaults = UserDefaults.standard
        defaults.set(serverURL.absoluteString, forKey: "serverURL")
        defaults.set(userID.uuidString, forKey: "userID")
        defaults.set(clientID.uuidString, forKey: "clientID")
    }

    func request(path: String) -> URLRequest {
        var request = URLRequest(url: serverURL.appendingPathComponent(path))
        let credential = Data("\(userID.uuidString.lowercased()):".utf8).base64EncodedString()
        request.setValue("Basic \(credential)", forHTTPHeaderField: "Authorization")
        request.setValue(clientID.uuidString, forHTTPHeaderField: "X-Client-ID")
        return request
    }
}

import AppKit
import Foundation

private struct ClipboardEvent: Decodable {
    let id: Int64
    let type: String
    let content: String?
    let timestamp: Int64
}

private struct PushReceipt: Decodable {
    let id: Int64
    let timestamp: Int64
}

private struct ClipboardPosition: Comparable {
    let timestamp: Int64
    let id: Int64

    static func < (lhs: ClipboardPosition, rhs: ClipboardPosition) -> Bool {
        if lhs.timestamp != rhs.timestamp { return lhs.timestamp < rhs.timestamp }
        return lhs.id < rhs.id
    }
}

private struct ClipboardDownload {
    let data: Data
    let mimeType: String
    let id: Int64
    let timestamp: Int64
    let type: String
}

private enum ClipboardSyncError: Error {
    case http(Int)
    case invalidText
    case unsupportedType
}

private enum ClipboardContent {
    case text(String)
    case image(Data, String)
}

@MainActor
final class ClipboardSync {
    var onStatusChange: ((String) -> Void)?
    var onLog: ((String) -> Void)?
    private(set) var settings: SyncSettings
    private var seenChangeCount = 0
    private var generation = 0
    private var pollTimer: Timer?
    private var eventTask: Task<Void, Never>?
    private var pushTask: Task<Void, Never>?
    private var wakeObserver: NSObjectProtocol?
    private var newestRemote: ClipboardPosition?
    private var localCopyAt: Int64 = 0

    init(settings: SyncSettings) {
        self.settings = settings
    }

    func start() {
        seenChangeCount = NSPasteboard.general.changeCount
        // NSPasteboard exposes changeCount but no public general change callback.
        pollTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.pollClipboard() }
        }
        wakeObserver = NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.restartEvents() }
        }
        onStatusChange?("正在连接")
        connect()
    }

    func update(settings: SyncSettings) {
        self.settings = settings
        settings.persist()
        generation += 1
        eventTask?.cancel()
        pushTask?.cancel()
        newestRemote = nil
        seenChangeCount = NSPasteboard.general.changeCount
        onStatusChange?("正在连接")
        connect()
    }

    private func restartEvents() {
        generation += 1
        eventTask?.cancel()
        onStatusChange?("正在连接")
        connect()
    }

    func ignoreCurrentClipboardChange() {
        seenChangeCount = NSPasteboard.general.changeCount
    }

    private func connect() {
        let currentGeneration = generation
        eventTask = Task { [weak self] in
            await self?.readEvents(generation: currentGeneration)
        }
    }

    private func pollClipboard() {
        let pasteboard = NSPasteboard.general
        guard pasteboard.changeCount != seenChangeCount else { return }
        seenChangeCount = pasteboard.changeCount
        localCopyAt = Self.currentTimestamp()

        let content: ClipboardContent
        if let image = pasteboard.readObjects(forClasses: [NSImage.self])?.first as? NSImage,
           let tiff = image.tiffRepresentation,
           let bitmap = NSBitmapImageRep(data: tiff),
           let png = bitmap.representation(using: .png, properties: [:]) {
            if png.count <= 25 * 1024 * 1024 {
                content = .image(png, "image/png")
            } else if let jpeg = bitmap.representation(using: .jpeg,
                        properties: [.compressionFactor: 0.8]) {
                content = .image(jpeg, "image/jpeg")
            } else {
                log("图片编码失败")
                return
            }
        } else if let text = pasteboard.string(forType: .string) {
            content = .text(text)
        } else {
            log("剪贴板内容不支持")
            return
        }

        let previous = pushTask
        let currentGeneration = generation
        let currentSettings = settings
        pushTask = Task { [weak self] in
            await previous?.value
            guard !Task.isCancelled, self?.generation == currentGeneration else { return }
            await self?.push(content, using: currentSettings, generation: currentGeneration)
        }
    }

    private func push(_ content: ClipboardContent, using settings: SyncSettings,
                      generation currentGeneration: Int) async {
        var request = settings.request(path: "push")
        request.httpMethod = "POST"
        switch content {
        case .text(let text):
            request.url?.append(queryItems: [URLQueryItem(name: "type", value: "text")])
            request.setValue("text/plain; charset=utf-8", forHTTPHeaderField: "Content-Type")
            request.httpBody = Data(text.utf8)
        case .image(let data, let contentType):
            request.url?.append(queryItems: [URLQueryItem(name: "type", value: "image")])
            request.setValue(contentType, forHTTPHeaderField: "Content-Type")
            request.httpBody = data
        }

        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                log("发送失败：无效响应")
                return
            }
            guard http.statusCode == 201 else {
                log("发送失败：HTTP \(http.statusCode)")
                return
            }
            guard generation == currentGeneration else { return }
            if let receipt = try? JSONDecoder().decode(PushReceipt.self, from: data) {
                let position = ClipboardPosition(timestamp: receipt.timestamp, id: receipt.id)
                if newestRemote == nil || position > newestRemote! { newestRemote = position }
            }
            log("发送成功")
        } catch {
            if !Task.isCancelled { log("发送失败：\(error.localizedDescription)") }
        }
    }

    private func readEvents(generation currentGeneration: Int) async {
        while !Task.isCancelled && generation == currentGeneration {
            onStatusChange?("正在连接")
            do {
                var request = settings.request(path: "events")
                request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
                let (bytes, response) = try await URLSession.shared.bytes(for: request)
                guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
                    let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                    log("events 连接失败：HTTP \(code)")
                    try await Task.sleep(for: .seconds(2))
                    continue
                }
                onStatusChange?("已连接")
                await recoverLatest(using: settings, generation: currentGeneration)

                for try await line in bytes.lines {
                    if Task.isCancelled || generation != currentGeneration { return }
                    if line.hasPrefix("data:") {
                        let data = Data(line.dropFirst(5).trimmingCharacters(in: .whitespaces).utf8)
                        do {
                            let event = try JSONDecoder().decode(ClipboardEvent.self, from: data)
                            try await apply(event, using: settings, generation: currentGeneration)
                        } catch {
                            log("接收失败：\(error.localizedDescription)")
                        }
                    }
                }
                if generation == currentGeneration && !Task.isCancelled {
                    log("events 连接断开")
                    onStatusChange?("正在连接")
                }
            } catch {
                if Task.isCancelled || generation != currentGeneration { return }
                log("events 连接失败：\(error.localizedDescription)")
                onStatusChange?("正在连接")
            }
            try? await Task.sleep(for: .seconds(2))
        }
    }

    private func recoverLatest(using settings: SyncSettings, generation currentGeneration: Int) async {
        do {
            let latest = try await pull(id: -1, using: settings)
            guard generation == currentGeneration else { return }
            let position = ClipboardPosition(timestamp: latest.timestamp, id: latest.id)
            guard newestRemote == nil || position > newestRemote! else {
                log("恢复跳过：已处理")
                return
            }
            guard latest.timestamp == 0 || latest.timestamp >= localCopyAt else {
                log("恢复跳过：本地内容更新")
                return
            }
            let initialChangeCount = NSPasteboard.general.changeCount
            guard initialChangeCount == seenChangeCount else {
                log("恢复跳过：剪贴板已变化")
                return
            }
            try apply(record: latest, initialChangeCount: initialChangeCount,
                      generation: currentGeneration, action: "恢复")
        } catch ClipboardSyncError.http(404) {
            return
        } catch {
            log("恢复失败：\(error.localizedDescription)")
        }
    }

    private func apply(_ event: ClipboardEvent, using settings: SyncSettings,
                       generation currentGeneration: Int) async throws {
        guard event.type == "text" || event.type == "image" else {
            throw ClipboardSyncError.unsupportedType
        }
        let record: ClipboardDownload
        if event.type == "text", let inline = event.content {
            record = ClipboardDownload(data: Data(inline.utf8), mimeType: "text/plain; charset=utf-8",
                                       id: event.id, timestamp: event.timestamp, type: event.type)
        } else {
            record = try await pull(id: event.id, using: settings)
        }
        let initialChangeCount = NSPasteboard.general.changeCount
        try apply(record: record, initialChangeCount: initialChangeCount,
                  generation: currentGeneration, action: "接收")
    }

    private func apply(record: ClipboardDownload, initialChangeCount: Int,
                       generation currentGeneration: Int, action: String) throws {
        guard generation == currentGeneration else { return }
        let position = ClipboardPosition(timestamp: record.timestamp, id: record.id)
        guard newestRemote == nil || position > newestRemote! else { return }
        guard record.timestamp == 0 || record.timestamp >= localCopyAt else {
            log("\(action)跳过：本地内容更新")
            return
        }
        let pasteboard = NSPasteboard.general
        guard pasteboard.changeCount == initialChangeCount else {
            log("\(action)跳过：剪贴板已变化")
            return
        }

        switch record.type {
        case "text":
            guard let text = String(data: record.data, encoding: .utf8) else {
                throw ClipboardSyncError.invalidText
            }
            guard pasteboard.string(forType: .string) != text else { return }
            pasteboard.clearContents()
            pasteboard.setString(text, forType: .string)
        case "image":
            guard let image = NSImage(data: record.data) else {
                throw CocoaError(.fileReadCorruptFile)
            }
            pasteboard.clearContents()
            pasteboard.writeObjects([image])
        default:
            throw ClipboardSyncError.unsupportedType
        }
        seenChangeCount = pasteboard.changeCount
        newestRemote = position
        log("\(action)成功：\(record.type)")
    }

    private func pull(id: Int64, using settings: SyncSettings) async throws -> ClipboardDownload {
        var request = settings.request(path: "pull")
        request.url?.append(queryItems: [URLQueryItem(name: "id", value: String(id))])
        let (data, response) = try await URLSession.shared.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw ClipboardSyncError.http(0) }
        guard http.statusCode == 200 else { throw ClipboardSyncError.http(http.statusCode) }
        let mime = http.value(forHTTPHeaderField: "Content-Type") ?? "application/octet-stream"
        let responseID = Int64(http.value(forHTTPHeaderField: "X-Clipboard-ID") ?? "") ?? id
        let timestamp = Int64(http.value(forHTTPHeaderField: "X-Clipboard-Timestamp") ?? "") ?? 0
        let type = http.value(forHTTPHeaderField: "X-Clipboard-Type")
            ?? (mime.hasPrefix("image/") ? "image" : "text")
        return ClipboardDownload(data: data, mimeType: mime, id: responseID,
                                 timestamp: timestamp, type: type)
    }

    private func log(_ value: String) {
        onLog?(value)
    }

    private static func currentTimestamp() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000)
    }
}

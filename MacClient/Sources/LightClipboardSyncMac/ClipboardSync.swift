import AppKit
import Foundation

private struct ClipboardEvent: Decodable {
    let id: Int64
    let type: String
    let content: String?
}

private enum ClipboardContent {
    case text(String)
    case image(Data, String)
}

@MainActor
final class ClipboardSync {
    var onStatusChange: ((String) -> Void)?
    private(set) var settings: SyncSettings
    private var seenChangeCount = 0
    private var generation = 0
    private var pollTimer: Timer?
    private var eventTask: Task<Void, Never>?
    private var pushTask: Task<Void, Never>?

    init(settings: SyncSettings) {
        self.settings = settings
    }

    func start() {
        seenChangeCount = NSPasteboard.general.changeCount
        pollTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.pollClipboard() }
        }
        connect()
    }

    func update(settings: SyncSettings) {
        self.settings = settings
        settings.persist()
        generation += 1
        eventTask?.cancel()
        pushTask?.cancel()
        seenChangeCount = NSPasteboard.general.changeCount
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
                return
            }
        } else if let text = pasteboard.string(forType: .string) {
            content = .text(text)
        } else {
            return
        }

        let previous = pushTask
        let currentGeneration = generation
        let currentSettings = settings
        pushTask = Task { [weak self] in
            await previous?.value
            guard !Task.isCancelled, self?.generation == currentGeneration else { return }
            await self?.push(content, using: currentSettings)
        }
    }

    private func push(_ content: ClipboardContent, using settings: SyncSettings) async {
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
            let (_, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 201 else {
                onStatusChange?("上传失败")
                return
            }
            onStatusChange?("已同步")
        } catch {
            if !Task.isCancelled { onStatusChange?("上传失败") }
        }
    }

    private func readEvents(generation currentGeneration: Int) async {
        while !Task.isCancelled && generation == currentGeneration {
            do {
                var request = settings.request(path: "events")
                request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
                let (bytes, response) = try await URLSession.shared.bytes(for: request)
                guard (response as? HTTPURLResponse)?.statusCode == 200 else {
                    onStatusChange?("连接失败")
                    try await Task.sleep(for: .seconds(3))
                    continue
                }
                onStatusChange?("已连接")

                for try await line in bytes.lines {
                    if Task.isCancelled || generation != currentGeneration { return }
                    if line.hasPrefix("data:") {
                        let data = Data(line.dropFirst(5).trimmingCharacters(in: .whitespaces).utf8)
                        do {
                            let event = try JSONDecoder().decode(ClipboardEvent.self, from: data)
                            try await apply(event, using: settings)
                        } catch {
                            onStatusChange?("下载失败")
                        }
                    }
                }
                onStatusChange?("重新连接中")
            } catch {
                if Task.isCancelled || generation != currentGeneration { return }
                onStatusChange?("重新连接中")
            }
            try? await Task.sleep(for: .seconds(2))
        }
    }

    private func apply(_ event: ClipboardEvent, using settings: SyncSettings) async throws {
        let pasteboard = NSPasteboard.general
        let initialChangeCount = pasteboard.changeCount
        switch event.type {
        case "text":
            let text: String
            if let inline = event.content {
                text = inline
            } else {
                let data = try await pull(id: event.id, using: settings)
                guard let decoded = String(data: data, encoding: .utf8) else {
                    throw CocoaError(.fileReadInapplicableStringEncoding)
                }
                text = decoded
            }
            guard pasteboard.changeCount == initialChangeCount else { return }
            if pasteboard.string(forType: .string) != text {
                pasteboard.clearContents()
                pasteboard.setString(text, forType: .string)
                seenChangeCount = pasteboard.changeCount
            }
        case "image":
            let data = try await pull(id: event.id, using: settings)
            guard let image = NSImage(data: data) else { throw CocoaError(.fileReadCorruptFile) }
            guard pasteboard.changeCount == initialChangeCount else { return }
            pasteboard.clearContents()
            pasteboard.writeObjects([image])
            seenChangeCount = pasteboard.changeCount
        default:
            throw CocoaError(.fileReadCorruptFile)
        }
        onStatusChange?("已同步")
    }

    private func pull(id: Int64, using settings: SyncSettings) async throws -> Data {
        var request = settings.request(path: "pull")
        request.url?.append(queryItems: [URLQueryItem(name: "id", value: String(id))])
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw CocoaError(.fileReadNoSuchFile)
        }
        return data
    }
}

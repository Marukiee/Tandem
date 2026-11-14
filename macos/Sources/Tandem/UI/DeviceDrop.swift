import AppKit
import SwiftUI
import TandemCore
import UniformTypeIdentifiers

/// A drop on a device in the window or the menu bar panel (R-D12). Files go as files; a web link or text goes as text, not as a file
/// that is not there; and what Photos, Mail or Safari only promise is first written into a folder of its own, then sent.
@MainActor
enum DeviceDrop {
    static let types: [UTType] = [.fileURL, .url, .image, .movie, .pdf, .data, .plainText]

    private enum Dropped {
        case file(URL)
        case text(String, isURL: Bool)
    }

    static func take(_ providers: [NSItemProvider], to device: TandemDevice, model: EngineModel) -> Bool {
        guard device.online else {
            model.showToast(String(localized: "That device is not connected right now"))
            return false
        }
        Task { @MainActor in
            var files: [URL] = []
            var texts: [(String, Bool)] = []
            for provider in providers {
                switch await read(provider) {
                case let .file(url)?: files.append(url)
                case let .text(text, isURL)?: texts.append((text, isURL))
                case nil: break
                }
            }
            if !files.isEmpty { model.send(urls: files, to: [device.id]) }
            guard !texts.isEmpty, let engine = model.tandem else {
                if files.isEmpty { model.showToast(String(localized: "Nothing in that could be sent")) }
                return
            }
            var reached = false
            for (text, isURL) in texts {
                let sent = (try? await engine.sendText(targets: [device.id], text: text, isUrl: isURL, open: false)) ?? []
                reached = reached || !sent.isEmpty
            }
            model.showToast(reached ? String(localized: "Sent to \(device.name)") : String(localized: "That device is not connected right now"))
        }
        return true
    }

    private static func read(_ provider: NSItemProvider) async -> Dropped? {
        if provider.hasItemConformingToTypeIdentifier(UTType.fileURL.identifier), let url = await loadURL(provider), url.isFileURL {
            return .file(url)
        }
        if provider.canLoadObject(ofClass: URL.self), let url = await loadURL(provider), !url.isFileURL {
            return .text(url.absoluteString, isURL: true)
        }
        // A file that is only promised, or data without a file (a picture from a browser): written out first.
        if let type = provider.registeredTypeIdentifiers.first(where: { identifier in
            guard let type = UTType(identifier) else { return false }
            return type.conforms(to: .data) && !type.conforms(to: .text) && !type.conforms(to: .url)
        }), let file = await loadFile(provider, type: type) {
            return .file(file)
        }
        if provider.canLoadObject(ofClass: String.self), let text = await loadText(provider), !text.isEmpty {
            return .text(text, isURL: false)
        }
        return nil
    }

    private static func loadURL(_ provider: NSItemProvider) async -> URL? {
        await withCheckedContinuation { continuation in
            _ = provider.loadObject(ofClass: URL.self) { url, _ in continuation.resume(returning: url) }
        }
    }

    private static func loadText(_ provider: NSItemProvider) async -> String? {
        await withCheckedContinuation { continuation in
            _ = provider.loadObject(ofClass: String.self) { text, _ in continuation.resume(returning: text) }
        }
    }

    /// The file handed over only lives during the callback, so it is copied there.
    private static func loadFile(_ provider: NSItemProvider, type: String) async -> URL? {
        let name = provider.suggestedName
        return await withCheckedContinuation { continuation in
            _ = provider.loadFileRepresentation(forTypeIdentifier: type) { url, _ in
                guard let url, let folder = DragReading.freshFolder() else { return continuation.resume(returning: nil) }
                var fileName = url.lastPathComponent
                if let name, !name.isEmpty {
                    fileName = (name as NSString).pathExtension.isEmpty && !url.pathExtension.isEmpty ? "\(name).\(url.pathExtension)" : name
                }
                let target = folder.appendingPathComponent(fileName)
                continuation.resume(returning: (try? FileManager.default.copyItem(at: url, to: target)) == nil ? nil : target)
            }
        }
    }
}

extension View {
    /// Takes files, promised files, links and text dropped on `device`.
    func deviceDrop(_ device: TandemDevice, model: EngineModel, targeted: Binding<Bool>) -> some View {
        onDrop(of: DeviceDrop.types, isTargeted: targeted) { providers in
            DeviceDrop.take(providers, to: device, model: model)
        }
    }
}

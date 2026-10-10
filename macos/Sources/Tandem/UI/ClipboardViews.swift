import AppKit
import SwiftUI
import TandemCore

// MARK: Filtering

enum ClipFilter: CaseIterable, Hashable {
    case all, pinned, text, links, images

    var title: LocalizedStringKey {
        switch self {
        case .all: "All"
        case .pinned: "Pinned"
        case .text: "Text"
        case .links: "Links"
        case .images: "Pictures"
        }
    }

    func includes(_ item: ClipItem) -> Bool {
        switch self {
        case .all: true
        case .pinned: item.pinned
        case .text: item.kind == .text
        case .links: item.kind == .link
        case .images: item.kind == .image
        }
    }
}

enum ClipQuery {
    /// The items that match every word of the query, in the filter, as they are kept: newest first.
    static func apply(_ items: [ClipItem], query: String, filter: ClipFilter) -> [ClipItem] {
        let words = query.split(whereSeparator: \.isWhitespace).map(String.init)
        return items.filter { item in
            guard filter.includes(item) else { return false }
            guard !words.isEmpty else { return true }
            let haystack = [item.text ?? item.preview, item.appName ?? "", item.device ?? ""].joined(separator: " ")
            return words.allSatisfy { haystack.range(of: $0, options: [.caseInsensitive, .diacriticInsensitive]) != nil }
        }
    }
}

// MARK: Looks

enum ClipTime {
    static func short(_ date: Date, now: Date = Date()) -> String {
        let seconds = now.timeIntervalSince(date)
        if seconds < 45 { return String(localized: "now") }
        if seconds < 3600 { return String(localized: "\(Int(seconds / 60)) min") }
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return date.formatted(date: .omitted, time: .shortened) }
        if calendar.isDateInYesterday(date) { return String(localized: "Yesterday") }
        if calendar.component(.year, from: date) == calendar.component(.year, from: now) {
            return date.formatted(.dateTime.day().month(.abbreviated))
        }
        return date.formatted(.dateTime.day().month(.abbreviated).year())
    }

    static func long(_ date: Date) -> String {
        date.formatted(.dateTime.weekday(.wide).day().month(.wide).hour().minute())
    }
}

@MainActor
enum AppIcons {
    private static var cache: [String: NSImage] = [:]

    static func icon(for bundle: String) -> NSImage {
        if let known = cache[bundle] { return known }
        let image: NSImage
        if let url = NSWorkspace.shared.urlForApplication(withBundleIdentifier: bundle) {
            image = NSWorkspace.shared.icon(forFile: url.path)
        } else {
            image = NSWorkspace.shared.icon(for: .applicationBundle)
        }
        image.size = NSSize(width: 64, height: 64)
        cache[bundle] = image
        return image
    }
}

@MainActor
enum ClipThumbnails {
    private static let cache = NSCache<NSString, NSImage>()

    static func image(for item: ClipItem) -> NSImage? {
        let key = item.id.uuidString as NSString
        if let known = cache.object(forKey: key) { return known }
        let url = ClipboardHistory.shared.thumbnailURL(item.id)
        guard let image = NSImage(contentsOf: url) else { return nil }
        cache.setObject(image, forKey: key)
        return image
    }
}

/// Where a copy came from: the app's own icon, or the device it arrived from.
struct ClipSourceIcon: View {
    let item: ClipItem
    var size: CGFloat = 30

    var body: some View {
        Group {
            if let symbol = item.devicePlatform {
                Image(systemName: symbol)
                    .font(.system(size: size * 0.46, weight: .semibold))
                    .foregroundStyle(Palette.indigo)
                    .frame(width: size, height: size)
                    .background(Palette.indigo.opacity(0.14), in: .rect(cornerRadius: size * 0.27, style: .continuous))
            } else if let app = item.app {
                Image(nsImage: AppIcons.icon(for: app))
                    .resizable()
                    .interpolation(.high)
                    .frame(width: size, height: size)
            } else {
                Image(systemName: "doc.on.clipboard")
                    .font(.system(size: size * 0.46, weight: .semibold))
                    .foregroundStyle(.secondary)
                    .frame(width: size, height: size)
                    .background(Color.primary.opacity(0.07), in: .rect(cornerRadius: size * 0.27, style: .continuous))
            }
        }
        .accessibilityHidden(true)
    }
}

extension ClipItem {
    /// The text on one line, as the list shows it.
    var oneLine: String {
        preview.prefix(240).split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }.joined(separator: " ")
    }

    var url: URL? {
        guard kind == .link else { return nil }
        return URL(string: preview.trimmingCharacters(in: .whitespacesAndNewlines))
    }

    var sourceName: String {
        device ?? appName ?? String(localized: "Unknown app")
    }

    var sizeLine: String {
        switch kind {
        case .image: "\(imageWidth) × \(imageHeight) · \(formatBytes(UInt64(imageBytes)))"
        case .link: String(localized: "Link")
        case .text:
            lines > 1
                ? String(localized: "\(characters) characters, \(lines) lines")
                : String(localized: "\(characters) characters")
        }
    }
}

// MARK: Row

/// One copy in a list: where it came from, what it was, when. The same row in the panel and on the page.
struct ClipRowView: View {
    let item: ClipItem
    var selected = false
    var hovering = false

    var body: some View {
        HStack(spacing: 11) {
            ClipSourceIcon(item: item, size: 30)
                .scaleEffect(hovering ? 1.05 : 1)
                .animation(.tandemSpringy, value: hovering)
            content
            Spacer(minLength: 4)
            VStack(alignment: .trailing, spacing: 4) {
                Text(ClipTime.short(item.date))
                    .font(.caption)
                    .foregroundStyle(.tertiary)
                    .lineLimit(1)
                if item.pinned {
                    Image(systemName: "pin.fill")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(Palette.indigo)
                        .rotationEffect(.degrees(35))
                        .transition(.scale.combined(with: .opacity))
                }
            }
            .frame(minWidth: 44, alignment: .trailing)
        }
        .padding(.horizontal, 10)
        .frame(height: 54)
        .background {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(selected ? Palette.indigo.opacity(0.16) : Color.primary.opacity(hovering ? 0.07 : 0))
        }
        .contentShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
        .animation(.tandemFade, value: selected)
        .animation(.tandemFade, value: hovering)
        .animation(.tandem, value: item.pinned)
    }

    @ViewBuilder
    private var content: some View {
        switch item.kind {
        case .text:
            Text(item.oneLine)
                .font(.callout)
                .lineLimit(2)
                .frame(maxWidth: .infinity, alignment: .leading)
        case .link:
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 5) {
                    Image(systemName: "link").font(.caption.weight(.semibold)).foregroundStyle(Palette.indigo)
                    Text(item.url?.host() ?? item.oneLine).font(.callout.weight(.semibold)).lineLimit(1)
                }
                Text(item.oneLine)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        case .image:
            HStack(spacing: 10) {
                Group {
                    if let image = ClipThumbnails.image(for: item) {
                        Image(nsImage: image).resizable().scaledToFill()
                    } else {
                        Image(systemName: "photo").foregroundStyle(.secondary)
                    }
                }
                .frame(width: 62, height: 38)
                .background(Color.primary.opacity(0.06))
                .clipShape(.rect(cornerRadius: 8, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Picture").font(.callout.weight(.medium))
                    Text(verbatim: "\(item.imageWidth) × \(item.imageHeight)").font(.caption).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

// MARK: Chips and keys

/// The filters, with one capsule that slides from one to the next.
struct ClipFilterChips: View {
    @Binding var filter: ClipFilter
    /// The height of the whole control, for when it stands next to something of a set height (the search field): the pill then fills it.
    var height: CGFloat?
    @Namespace private var pill

    var body: some View {
        HStack(spacing: 2) {
            ForEach(ClipFilter.allCases, id: \.self) { option in
                Button { filter = option } label: {
                    Text(option.title)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(filter == option ? Color.white : Color.secondary)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 5)
                        .frame(maxHeight: height == nil ? nil : .infinity)
                        .background {
                            if filter == option {
                                Capsule().fill(Palette.indigo).matchedGeometryEffect(id: "pill", in: pill)
                            }
                        }
                        .contentShape(Capsule())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(2)
        .frame(height: height)
        .background(Color.primary.opacity(0.06), in: .capsule)
        .animation(.tandem, value: filter)
    }
}

/// A key as it is printed on a keyboard, for the hints.
struct KeyCap: View {
    let text: String

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.system(size: 11, weight: .semibold, design: .rounded))
            .foregroundStyle(.secondary)
            .padding(.horizontal, 5)
            .frame(minWidth: 20, minHeight: 18)
            .background(Color.primary.opacity(0.08), in: .rect(cornerRadius: 6, style: .continuous))
    }
}

// MARK: Preview

/// What an item is, in full: the whole text, the link, the picture.
struct ClipPreview: View {
    let item: ClipItem
    @Environment(EngineModel.self) private var model

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 9) {
                ClipSourceIcon(item: item, size: 24)
                VStack(alignment: .leading, spacing: 0) {
                    Text(item.sourceName).font(.callout.weight(.semibold)).lineLimit(1)
                    Text(ClipTime.long(item.date)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(.bottom, 12)

            Group {
                switch item.kind {
                case .text: TextBody(item: item)
                case .link: LinkBody(item: item)
                case .image: ImageBody(item: item)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)

            Text(item.sizeLine)
                .font(.caption)
                .foregroundStyle(.tertiary)
                .padding(.top, 10)
        }
    }

    private struct TextBody: View {
        let item: ClipItem
        @LocalState private var text = ""

        var body: some View {
            ScrollView {
                Text(text)
                    .font(.callout)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .topLeading)
            }
            .scrollIndicators(.never)
            // Reading a long text from its file is done when the item is chosen, not for every row.
            .task(id: item.id) {
                let full = ClipboardHistory.shared.fullText(of: item) ?? item.preview
                text = full.count > 12_000 ? String(full.prefix(12_000)) + "…" : full
            }
        }
    }

    private struct LinkBody: View {
        let item: ClipItem

        var body: some View {
            VStack(alignment: .leading, spacing: 10) {
                ZStack {
                    Circle().fill(Palette.indigo.opacity(0.14))
                    Image(systemName: "link").font(.system(size: 20, weight: .semibold)).foregroundStyle(Palette.indigo)
                }
                .frame(width: 48, height: 48)
                Text(item.url?.host() ?? item.oneLine).font(.title3.weight(.semibold)).lineLimit(2)
                Text(item.preview)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .textSelection(.enabled)
                    .lineLimit(6)
                if let url = item.url {
                    Button { NSWorkspace.shared.open(url) } label: {
                        Label("Open in browser", systemImage: "arrow.up.right")
                            .font(.callout.weight(.medium))
                    }
                    .buttonStyle(.glass)
                    .controlSize(.regular)
                    .padding(.top, 2)
                }
            }
        }
    }

    private struct ImageBody: View {
        let item: ClipItem
        @LocalState private var image: NSImage?

        var body: some View {
            ZStack {
                if let image {
                    Image(nsImage: image)
                        .resizable()
                        .scaledToFit()
                        .clipShape(.rect(cornerRadius: 12, style: .continuous))
                        .transition(.opacity)
                } else {
                    PillSpinner(size: 22)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .animation(.tandemFade, value: image != nil)
            .task(id: item.id) {
                image = nil
                let url = ClipboardHistory.shared.imageURL(item.id)
                image = await Task.detached(priority: .userInitiated) { ClipImage.downsampled(url, maxPixels: 1400) }.value
            }
        }
    }
}

// MARK: Actions

struct ClipAction: Identifiable {
    let id: String
    let title: String
    let symbol: String
    var shortcut: String?
    var destructive = false
    let run: @MainActor () -> Void
}

@MainActor
enum ClipActions {
    /// What can be done with an item, for the actions menu, the context menu and the buttons.
    /// `primary` are the actions that differ between the panel and the page (paste, copy).
    static func list(for item: ClipItem, model: EngineModel, primary: [ClipAction], afterRemove: @escaping () -> Void = {}) -> [ClipAction] {
        let history = ClipboardHistory.shared
        var actions = primary
        actions.append(ClipAction(
            id: "pin",
            title: item.pinned ? String(localized: "Unpin") : String(localized: "Pin"),
            symbol: item.pinned ? "pin.slash" : "pin",
            shortcut: "⌘P"
        ) { history.togglePin(item.id) })
        if let url = item.url {
            actions.append(ClipAction(id: "open", title: String(localized: "Open in browser"), symbol: "arrow.up.right") { NSWorkspace.shared.open(url) })
        }
        let reachable = model.devices.filter { item.isImage ? $0.online : ($0.online || $0.ble) }
        for device in reachable {
            actions.append(ClipAction(id: "send.\(device.id)", title: String(localized: "Send to \(device.name)"), symbol: device.platform.symbol) {
                model.send(clip: item, to: [device.id])
            })
        }
        actions.append(ClipAction(id: "delete", title: String(localized: "Delete"), symbol: "trash", shortcut: "⌘⌫", destructive: true) {
            history.delete([item.id])
            afterRemove()
        })
        return actions
    }
}

extension EngineModel {
    /// Sends a clip to other devices: text and links as clipboard, a picture as a file.
    func send(clip item: ClipItem, to ids: [String]) {
        guard let engine = engineHandle, !ids.isEmpty else { return }
        let names = ids.compactMap { id in devices.first { $0.id == id }?.name }.joined(separator: ", ")
        if item.isImage {
            let stamp = item.date.formatted(.iso8601.year().month().day().dateSeparator(.omitted).time(includingFractionalSeconds: false).timeSeparator(.omitted))
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("Clipboard \(stamp).png")
            try? FileManager.default.removeItem(at: url)
            do {
                try FileManager.default.copyItem(at: ClipboardHistory.shared.imageURL(item.id), to: url)
            } catch {
                FloatingToast.show(String(localized: "The picture is no longer there"), symbol: "exclamationmark.triangle.fill")
                return
            }
            send(urls: [url], to: ids)
            FloatingToast.show(String(localized: "Sending to \(names)"), symbol: "paperplane.fill")
            return
        }
        guard let text = ClipboardHistory.shared.fullText(of: item) else { return }
        Task { @MainActor in
            let reached = (try? await engine.sendClipboard(targets: ids, text: text, isUrl: item.kind == .link)) ?? []
            if reached.isEmpty {
                FloatingToast.show(String(localized: "Not connected"), symbol: "exclamationmark.triangle.fill")
            } else {
                FloatingToast.show(String(localized: "Sent to \(names)"), symbol: "paperplane.fill")
            }
        }
    }
}

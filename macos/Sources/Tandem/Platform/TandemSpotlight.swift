import AppKit
@preconcurrency import CoreSpotlight
import SwiftUI
import TandemCore
import UniformTypeIdentifiers

/// What else of Tandem Spotlight finds, next to the contacts of the phone (`PhoneSpotlight`): each kind has its own switch.
enum SpotlightKind: String, CaseIterable {
    case devices, actions, conversations, clipboard, files, settings

    var key: String { "spotlight.\(rawValue)" }

    /// Messages and the clipboard hold what people write and copy (codes from the bank, addresses, a password copied from a note).
    /// Spotlight shows it to anyone at the keyboard who types a word of it, so those two are only there when the person wants them.
    var byDefault: Bool { self != .conversations && self != .clipboard }

    var enabled: Bool { UserDefaults.standard.object(forKey: key) as? Bool ?? byDefault }
}

/// One thing in the index of this app.
struct SpotlightEntry: Equatable {
    var id: String
    var domain: String
    var title: String
    var detail: String
    var keywords: [String]
    var type: UTType = .item
    var symbol: String?
    /// Searched as the words of the thing itself, such as the last message of a conversation.
    var text: String?
    var date: Date?

    /// The same in every run, unlike `hashValue`, so what is in the index can be compared with what was there before a restart.
    var fingerprint: String {
        var hash: UInt64 = 0xcbf2_9ce4_8422_2325
        let joined = [title, detail, keywords.joined(separator: " "), type.identifier, symbol ?? "", text ?? "", date.map { "\($0.timeIntervalSince1970)" } ?? ""]
            .joined(separator: "\u{1}")
        for byte in joined.utf8 {
            hash ^= UInt64(byte)
            hash = hash &* 0x100_0000_01b3
        }
        return String(hash, radix: 16)
    }
}

/// What a result of Tandem in Spotlight stands for, written into its identifier. Contacts keep their own form (`PhoneSpotlight`).
enum SpotlightTarget: Equatable {
    enum Action: String, CaseIterable {
        case clipboard, ring, extend, screen, wifi, files, messages
    }

    case device(String)
    case action(String, Action)
    case conversation(String, UInt64)
    case clip(UUID)
    case file(String)
    case setting(SettingsSection, String)

    var identifier: String {
        switch self {
        case let .device(id): "tandem/device/\(id)"
        case let .action(id, action): "tandem/action/\(id)/\(action.rawValue)"
        case let .conversation(id, thread): "tandem/conversation/\(id)/\(thread)"
        case let .clip(id): "tandem/clip/\(id.uuidString)"
        // Last, because the id of a transfer is not chosen here.
        case let .file(id): "tandem/file/\(id)"
        case let .setting(section, key): "tandem/setting/\(section.rawValue)/\(key)"
        }
    }

    init?(_ identifier: String) {
        let parts = identifier.split(separator: "/", maxSplits: 2, omittingEmptySubsequences: false).map(String.init)
        guard parts.count == 3, parts[0] == "tandem" else { return nil }
        let rest = parts[2]
        let pair = rest.split(separator: "/", maxSplits: 1).map(String.init)
        switch parts[1] {
        case "device" where !rest.isEmpty && !rest.contains("/"): self = .device(rest)
        case "action":
            guard pair.count == 2, let action = Action(rawValue: pair[1]) else { return nil }
            self = .action(pair[0], action)
        case "conversation":
            guard pair.count == 2, let thread = UInt64(pair[1]) else { return nil }
            self = .conversation(pair[0], thread)
        case "clip":
            guard let id = UUID(uuidString: rest) else { return nil }
            self = .clip(id)
        case "file" where !rest.isEmpty: self = .file(rest)
        case "setting":
            guard pair.count == 2, let section = SettingsSection(rawValue: pair[0]) else { return nil }
            self = .setting(section, pair[1])
        default: return nil
        }
    }
}

/// Puts the devices, what can be done with them, the conversations of the phones, the clipboard history, the shared files and the
/// settings into Spotlight, and does what a pick asks. What is in the index is compared with what should be there whenever something
/// changes, so only what changed is written; a kind that is turned off, or a device that is removed, is taken out at once.
@MainActor
final class TandemSpotlight {
    static let shared = TandemSpotlight()

    private let index = CSSearchableIndex.default()
    private var model: EngineModel { EngineModel.shared }
    /// What is in the index by domain, as identifier and fingerprint. Kept on disk, so what went while Tandem was closed (a device
    /// removed on another device, a switch turned off in a backup) is taken out at the next start too.
    private var indexed: [String: [String: String]] = TandemSpotlight.loadIndexed()
    /// Index work runs one after another: a domain that is emptied must be empty before it is filled again.
    private var queue: Task<Void, Never>?
    private var pending: Task<Void, Never>?
    private var started = false

    private static let refreshEvery: TimeInterval = 3 * 3600
    private static let retryAfter: TimeInterval = 10 * 60
    /// The conversations of each phone, newest first, from the first page the phone gives and what it tells after.
    private var conversations: [String: [Conversation]] = [:]
    private var lastAsked: [String: Date] = [:]
    private var asking: Set<String> = []

    struct Conversation: Codable, Equatable {
        var thread: UInt64
        var name: String
        var addresses: [String]
        var snippet: String
        var date: UInt64
    }

    func start() {
        guard !started else { return }
        started = true
        for phone in Self.storedPhones() { conversations[phone] = Self.readConversations(phone) }
        watch()
        Task { @MainActor [weak self] in
            // Conversations are asked for again every few hours while the phone stays; the rest changes by itself.
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(15 * 60))
                self?.schedule()
            }
        }
        schedule()
    }

    /// A switch in Settings was turned on or off.
    func kindChanged() {
        if !SpotlightKind.conversations.enabled {
            for phone in conversations.keys { forgetConversations(phone) }
            lastAsked = [:]
        }
        schedule(after: 0)
    }

    /// What a phone tells by itself about conversations that are new or changed.
    func conversationsChanged(_ phone: String, _ changes: [TandemSmsConversation]) {
        guard SpotlightKind.conversations.enabled, !changes.isEmpty, model.device(phone) != nil else { return }
        put(changes.map(Conversation.init), into: phone)
        schedule()
    }

    private func watch() {
        withObservationTracking {
            _ = model.ready
            _ = model.devices
            _ = model.deviceIcons
            _ = model.transfers
            _ = ClipboardHistory.shared.items
        } onChange: {
            Task { @MainActor in
                TandemSpotlight.shared.schedule()
                TandemSpotlight.shared.watch()
            }
        }
    }

    /// The battery, the round trip and every tick of a transfer change what is watched: one look a moment later covers them all.
    /// A long transfer changes something every moment; the look that has waited longest is then let through instead of put off for good.
    private func schedule(after delay: Double = 1.5) {
        if delay > 0, pending != nil, let since = pendingSince, Date().timeIntervalSince(since) > 5 { return }
        pending?.cancel()
        if pendingSince == nil { pendingSince = Date() }
        pending = Task { @MainActor [weak self] in
            if delay > 0 { try? await Task.sleep(for: .seconds(delay)) }
            guard !Task.isCancelled, let self else { return }
            pending = nil
            pendingSince = nil
            sync()
        }
    }

    private var pendingSince: Date?

    /// Everything of this app goes out of Spotlight, the contacts too: after a reset none of those devices is paired any more.
    static func forgetEverything() async {
        try? await CSSearchableIndex.default().deleteAllSearchableItems()
    }

    // MARK: Writing the index

    private func sync() {
        // Before the engine runs there is no list of devices, which is not the same as every device gone.
        guard model.ready, let engine = model.tandem else { return }
        askForConversations(engine)
        var wanted: [String: [SpotlightEntry]] = [:]
        for kind in SpotlightKind.allCases where kind.enabled {
            for entry in entries(kind) { wanted[entry.domain, default: []].append(entry) }
        }
        apply(wanted)
    }

    private func apply(_ wanted: [String: [SpotlightEntry]]) {
        var deleteDomains: [String] = []
        var deleteIds: [String] = []
        var write: [SpotlightEntry] = []
        // Made up results of a check stay until the check takes them out (`debug`).
        for domain in indexed.keys where wanted[domain] == nil && !domain.hasPrefix(Self.debugDomain) {
            deleteDomains.append(domain)
            indexed[domain] = nil
        }
        for (domain, entries) in wanted {
            let before = indexed[domain] ?? [:]
            var now: [String: String] = [:]
            for entry in entries {
                let print = entry.fingerprint
                now[entry.id] = print
                if before[entry.id] != print { write.append(entry) }
            }
            deleteIds += before.keys.filter { now[$0] == nil }
            indexed[domain] = now
        }
        guard !deleteDomains.isEmpty || !deleteIds.isEmpty || !write.isEmpty else { return }
        Self.saveIndexed(indexed)
        let items = write.map(Self.item)
        enqueue { [index] in
            do {
                if !deleteDomains.isEmpty { try await index.deleteSearchableItems(withDomainIdentifiers: deleteDomains) }
                if !deleteIds.isEmpty { try await index.deleteSearchableItems(withIdentifiers: deleteIds) }
                if !items.isEmpty { try await index.indexSearchableItems(items) }
            } catch {
                NSLog("Tandem: Spotlight could not be brought up to date: \(error.localizedDescription)")
            }
        }
    }

    private func enqueue(_ work: @escaping @MainActor () async -> Void) {
        let previous = queue
        queue = Task { @MainActor in
            await previous?.value
            await work()
        }
    }

    private static func item(_ entry: SpotlightEntry) -> CSSearchableItem {
        let attributes = CSSearchableItemAttributeSet(contentType: entry.type)
        attributes.title = entry.title
        attributes.displayName = entry.title
        attributes.contentDescription = entry.detail
        attributes.keywords = entry.keywords
        attributes.textContent = entry.text
        attributes.contentModificationDate = entry.date
        if let symbol = entry.symbol { attributes.thumbnailData = thumbnail(symbol) }
        return CSSearchableItem(uniqueIdentifier: entry.id, domainIdentifier: entry.domain, attributeSet: attributes)
    }

    private static var thumbnails: [String: Data] = [:]

    /// A symbol as a small picture, drawn once per symbol.
    private static func thumbnail(_ symbol: String) -> Data? {
        if let known = thumbnails[symbol] { return known }
        let configuration = NSImage.SymbolConfiguration(pointSize: 44, weight: .medium)
            .applying(NSImage.SymbolConfiguration(hierarchicalColor: NSColor(Palette.indigo)))
        guard let image = NSImage(systemSymbolName: symbol, accessibilityDescription: nil)?.withSymbolConfiguration(configuration) else { return nil }
        let side = 64
        guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: side, pixelsHigh: side, bitsPerSample: 8, samplesPerPixel: 4,
                                         hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)
        else { return nil }
        NSGraphicsContext.saveGraphicsState()
        NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
        let size = image.size
        let scale = min(CGFloat(side) / max(size.width, 1), CGFloat(side) / max(size.height, 1)) * 0.8
        let drawn = NSSize(width: size.width * scale, height: size.height * scale)
        image.draw(in: NSRect(x: (CGFloat(side) - drawn.width) / 2, y: (CGFloat(side) - drawn.height) / 2, width: drawn.width, height: drawn.height))
        NSGraphicsContext.restoreGraphicsState()
        let data = rep.representation(using: .png, properties: [:])
        thumbnails[symbol] = data
        return data
    }

    // MARK: What goes in

    private func entries(_ kind: SpotlightKind) -> [SpotlightEntry] {
        switch kind {
        case .devices: model.devices.map { Self.deviceEntry($0, symbol: model.deviceIcons[$0.id]) }
        case .actions: model.devices.flatMap { Self.actionEntries($0, wifiWorth: WifiShare.shared.worthSharing(with: $0), canExtend: VirtualDisplay.isAvailable) }
        case .conversations:
            model.devices.filter { $0.caps.contains("sms") }.flatMap { device in
                (conversations[device.id] ?? []).map { Self.conversationEntry($0, phone: device.id, phoneName: device.name) }
            }
        case .clipboard: Self.clipEntries(ClipboardHistory.shared.items)
        case .files:
            model.transfers.filter { $0.state == .done && model.filePath($0) != nil }.prefix(300).map { item in
                Self.fileEntry(item, peerName: model.device(item.peer)?.name)
            }
        case .settings: Self.settingEntries()
        }
    }

    private static func words(_ text: String) -> [String] {
        text.split { !$0.isLetter && !$0.isNumber }.map(String.init)
    }

    static func deviceEntry(_ device: TandemDevice, symbol: String?) -> SpotlightEntry {
        let kind: String = switch device.platform {
        case .android: String(localized: "Phone")
        case .ios: "iPhone"
        case .macOs: "Mac"
        case .linux: String(localized: "Linux computer")
        case .windows: String(localized: "Windows computer")
        case .other: String(localized: "Device")
        }
        let english = device.isComputer ? ["computer", "pc"] : ["phone", "telefoon", "mobiel", "mobile"]
        return SpotlightEntry(
            id: SpotlightTarget.device(device.id).identifier,
            domain: "tandem-device.\(device.id)",
            title: device.name,
            detail: String(localized: "\(kind) in Tandem"),
            keywords: words(device.name) + english + ["tandem", "device", "apparaat"],
            symbol: symbol ?? device.platform.symbol
        )
    }

    /// The actions of a device, only those its page offers it (its caps and what this Mac can do), worded as they would be typed.
    static func actionEntries(_ device: TandemDevice, wifiWorth: Bool, canExtend: Bool) -> [SpotlightEntry] {
        let name = device.name
        let phone = device.platform == .android
        var list: [(SpotlightTarget.Action, String, String, [String], String)] = [
            (.clipboard, String(localized: "Send clipboard to \(name)"), String(localized: "Sends the text on this Mac's clipboard"),
             ["clipboard", "klembord", "send", "sturen", "versturen", "copy", "kopiëren", "paste", "plakken", "text", "tekst"], "doc.on.clipboard"),
        ]
        if phone {
            list.append((.ring, String(localized: "Find \(name)"), String(localized: "Rings the phone, even when it is silent"),
                         ["find", "zoeken", "vinden", "ring", "bellen", "rinkelen", "sound", "geluid", "lost", "kwijt"], "bell.and.waves.left.and.right"))
        } else if device.isComputer, device.caps.contains("ring") {
            list.append((.ring, String(localized: "Ring \(name)"), String(localized: "Plays a sound on it, to find it"),
                         ["ring", "bellen", "find", "zoeken", "vinden", "sound", "geluid", "play", "afspelen", "lost", "kwijt"], "bell.and.waves.left.and.right"))
        }
        if device.caps.contains("screen.extend.view"), canExtend {
            list.append((.extend, String(localized: "Extend screen to \(name)"), String(localized: "This Mac gets an extra screen, shown there"),
                         ["extend", "uitbreiden", "screen", "scherm", "display", "beeldscherm", "second", "tweede", "monitor"], "rectangle.on.rectangle"))
        }
        if device.canShowScreen {
            list.append((.screen, String(localized: "Show \(name)'s screen"), String(localized: "Its screen in a window here"),
                         ["show", "tonen", "laten", "zien", "screen", "scherm", "mirror", "spiegelen", "view", "bekijken", "control", "bedienen"],
                         phone ? "iphone.gen3" : "display"))
        }
        if device.caps.contains("wifi.join"), wifiWorth {
            list.append((.wifi, String(localized: "Share Wi-Fi with \(name)"), String(localized: "Sends the name and password of this Mac's Wi-Fi"),
                         ["wifi", "wi-fi", "wlan", "network", "netwerk", "password", "wachtwoord", "share", "delen"], "wifi"))
        }
        if device.caps.contains("files") {
            list.append((.files, String(localized: "Browse files on \(name)"), String(localized: "Its files in Tandem"),
                         ["browse", "bladeren", "files", "bestanden", "folder", "map", "mappen", "bekijken"], "folder"))
        }
        if device.caps.contains("sms") {
            list.append((.messages, String(localized: "Messages of \(name)"), String(localized: "The text messages of the phone"),
                         ["messages", "berichten", "sms", "texts", "chat", "write", "schrijven"], "message"))
        }
        return list.map { action, title, detail, keywords, symbol in
            SpotlightEntry(
                id: SpotlightTarget.action(device.id, action).identifier,
                domain: "tandem-actions.\(device.id)",
                title: title,
                detail: detail,
                keywords: keywords + words(name) + ["tandem"],
                symbol: symbol
            )
        }
    }

    static func conversationEntry(_ conversation: Conversation, phone: String, phoneName: String) -> SpotlightEntry {
        let who = conversation.name.isEmpty ? conversation.addresses.joined(separator: ", ") : conversation.name
        return SpotlightEntry(
            id: SpotlightTarget.conversation(phone, conversation.thread).identifier,
            domain: "tandem-messages.\(phone)",
            title: who,
            detail: conversation.snippet.isEmpty ? String(localized: "Messages of \(phoneName)") : conversation.snippet,
            keywords: words(who) + conversation.addresses + ["messages", "berichten", "sms"],
            type: .message,
            text: conversation.snippet,
            date: Date(timeIntervalSince1970: TimeInterval(conversation.date) / 1000)
        )
    }

    /// Texts and links only. What an app marked as secret never got into the history (`ClipboardRecorder`).
    static func clipEntries(_ items: [ClipItem]) -> [SpotlightEntry] {
        items.filter { $0.kind != .image }.prefix(200).map { item in
            let firstLine = item.preview.split(whereSeparator: \.isNewline).first.map(String.init) ?? item.preview
            let source = item.device ?? item.appName
            return SpotlightEntry(
                id: SpotlightTarget.clip(item.id).identifier,
                domain: "tandem-clipboard",
                title: String(firstLine.trimmingCharacters(in: .whitespaces).prefix(120)),
                detail: source.map { String(localized: "Copied in \($0)") } ?? String(localized: "Clipboard history"),
                keywords: ["clipboard", "klembord", "copied", "gekopieerd"],
                type: item.kind == .link ? .url : .plainText,
                symbol: item.kind == .link ? "link" : "doc.on.clipboard",
                text: item.preview,
                date: item.date
            )
        }
    }

    static func fileEntry(_ item: TransferItem, peerName: String?) -> SpotlightEntry {
        let peer = peerName ?? "?"
        let ext = (item.name as NSString).pathExtension
        return SpotlightEntry(
            id: SpotlightTarget.file(item.id).identifier,
            domain: "tandem-files",
            title: item.name,
            detail: item.incoming ? String(localized: "Received from \(peer)") : String(localized: "Sent to \(peer)"),
            keywords: words(item.name) + ["tandem", item.incoming ? "received" : "sent", item.incoming ? "ontvangen" : "verstuurd"],
            type: UTType(filenameExtension: ext) ?? .data,
            date: item.updated
        )
    }

    /// The pages of Settings and the main switches, with words in both languages: someone with Tandem in English may still type Dutch.
    static func settingEntries() -> [SpotlightEntry] {
        let pages: [(SettingsSection, String, [String])] = [
            (.general, String(localized: "General"), ["general", "algemeen", "name", "naam", "behaviour", "gedrag"]),
            (.devices, String(localized: "Devices"), ["devices", "apparaten", "pair", "koppelen", "remove", "verwijderen"]),
            (.features, String(localized: "What works"), ["features", "functies", "permissions", "rechten", "werkt"]),
            (.files, String(localized: "Files"), ["files", "bestanden", "share", "delen", "folders", "mappen"]),
            (.clipboard, String(localized: "Clipboard"), ["clipboard", "klembord", "history", "geschiedenis"]),
            (.screen, String(localized: "Remote control"), ["remote", "afstand", "control", "bedienen", "screen", "scherm"]),
            (.pointer, String(localized: "Mouse and keyboard"), ["mouse", "muis", "keyboard", "toetsenbord", "pointer", "aanwijzer"]),
            (.quickShare, String(localized: "Quick Share"), ["quick", "share", "nearby", "delen", "android"]),
            (.access, String(localized: "Access"), ["access", "toegang", "guests", "gasten"]),
            (.hotspot, String(localized: "Hotspot"), ["hotspot", "tethering", "internet"]),
            (.updates, String(localized: "Updates"), ["updates", "update", "bijwerken", "version", "versie"]),
            (.about, String(localized: "About"), ["about", "over", "version", "versie"]),
        ]
        let switches: [(SettingsSection, String, String, [String])] = [
            (.pointer, "universal-mouse", String(localized: "Universal mouse"), ["universal", "universele", "shared", "gedeelde", "mouse", "muis", "keyboard", "toetsenbord"]),
            (.pointer, "side-check", String(localized: "Check the side by itself"), ["side", "kant", "check", "controleren", "sound", "geluid", "automatic", "vanzelf"]),
            (.clipboard, "history", String(localized: "Clipboard history"), ["clipboard", "klembord", "history", "geschiedenis", "klembordgeschiedenis", "keep", "bewaren"]),
            (.general, "downloads", String(localized: "Downloads folder"), ["downloads", "folder", "map", "received", "ontvangen", "files", "bestanden", "opslaan"]),
            (.general, "login", String(localized: "Start at login"), ["start", "login", "inloggen", "opstarten", "automatisch"]),
            (.general, "language", String(localized: "Language"), ["language", "taal", "english", "engels", "nederlands", "dutch"]),
            (.general, "dock", String(localized: "Show in the Dock"), ["dock", "icon", "icoon"]),
            (.general, "spotlight", String(localized: "Spotlight"), ["spotlight", "search", "zoeken", "contacts", "contacten"]),
            (.general, "bluetooth", String(localized: "Bluetooth without a network"), ["bluetooth", "network", "netwerk", "offline"]),
            (.general, "tailscale", String(localized: "Turn on Tailscale when needed"), ["tailscale", "vpn", "network", "netwerk"]),
            (.screen, "host", String(localized: "Let my other devices see and control this Mac"), ["screen", "scherm", "share", "delen", "control", "bedienen", "remote", "afstand"]),
            (.files, "share", String(localized: "Share files"), ["share", "delen", "files", "bestanden", "browse", "bekijken"]),
            (.hotspot, "auto", String(localized: "Use my phone's hotspot when this Mac has no connection"), ["hotspot", "connection", "verbinding", "internet", "automatic", "automatisch"]),
            (.updates, "auto", String(localized: "Check for updates automatically"), ["updates", "bijwerken", "automatic", "automatisch", "check", "controleren"]),
        ]
        let titles = Dictionary(uniqueKeysWithValues: pages.map { ($0.0, $0.1) })
        let settings = ["settings", "instellingen", "preferences", "voorkeuren", "tandem"]
        return pages.map { section, title, keywords in
            SpotlightEntry(
                id: SpotlightTarget.setting(section, "page").identifier, domain: "tandem-settings",
                title: String(localized: "Tandem settings: \(title)"), detail: String(localized: "Opens this page of the settings"),
                keywords: keywords + settings, symbol: section.symbol
            )
        } + switches.map { section, key, title, keywords in
            SpotlightEntry(
                id: SpotlightTarget.setting(section, key).identifier, domain: "tandem-settings",
                title: title, detail: String(localized: "Tandem settings, \(titles[section] ?? "")"),
                keywords: keywords + settings, symbol: section.symbol
            )
        }
    }

    // MARK: Conversations

    private func askForConversations(_ engine: TandemEngine) {
        let known = Set(model.devices.map(\.id))
        for phone in conversations.keys where !known.contains(phone) { forgetConversations(phone) }
        guard SpotlightKind.conversations.enabled else { return }
        for device in model.devices where device.online && device.caps.contains("sms") && !asking.contains(device.id) {
            if let last = lastAsked[device.id], Date().timeIntervalSince(last) < Self.refreshEvery { continue }
            asking.insert(device.id)
            let id = device.id
            Task { @MainActor in
                defer { asking.remove(id) }
                do {
                    let page = try await engine.smsConversations(target: id, before: 0, limit: 40)
                    lastAsked[id] = Date()
                    guard SpotlightKind.conversations.enabled else { return }
                    // The first page is the newest: what is not on it any more was deleted on the phone or is older than what is kept.
                    conversations[id] = []
                    put(page.items.map(Conversation.init), into: id)
                    schedule(after: 0)
                } catch let error as TandemSmsError {
                    switch error {
                    case .Off, .NoPermission, .Unavailable:
                        // The phone says no: its conversations go.
                        lastAsked[id] = Date()
                        forgetConversations(id)
                        schedule(after: 0)
                    case .NotConnected, .Failed:
                        lastAsked[id] = Date().addingTimeInterval(Self.retryAfter - Self.refreshEvery)
                    }
                } catch {
                    lastAsked[id] = Date().addingTimeInterval(Self.retryAfter - Self.refreshEvery)
                }
            }
        }
    }

    private func put(_ changes: [Conversation], into phone: String) {
        var byThread = Dictionary((conversations[phone] ?? []).map { ($0.thread, $0) }, uniquingKeysWith: { a, _ in a })
        for change in changes { byThread[change.thread] = change }
        let list = Array(byThread.values.sorted { ($0.date, $0.thread) > ($1.date, $1.thread) }.prefix(60))
        conversations[phone] = list
        Self.writeConversations(list, phone: phone)
    }

    private func forgetConversations(_ phone: String) {
        conversations[phone] = nil
        try? FileManager.default.removeItem(at: Self.conversationsFile(phone))
    }

    // MARK: Picked in Spotlight

    /// Every pick of a result of Tandem comes here; a contact goes on to `PhoneSpotlight`.
    func open(_ identifier: String) {
        if PhoneSpotlight.parse(identifier) != nil {
            PhoneSpotlight.shared.open(identifier: identifier)
            return
        }
        guard let target = SpotlightTarget(identifier) else { return }
        report("picked \(identifier)")
        Task { @MainActor in
            // Spotlight may just have started Tandem: the engine and the list of devices need a moment.
            for _ in 0..<40 where !model.ready { try? await Task.sleep(for: .milliseconds(250)) }
            switch target {
            case let .device(id):
                guard let device = await known(id) else { return }
                AppScenes.showMain(.device(device.id))
            case let .action(id, action):
                guard let device = await known(id) else { return }
                await run(action, on: device)
            case let .conversation(id, thread):
                guard let device = await known(id) else { return }
                MessagesStore.shared.phone(device.id).wanted = thread
                AppScenes.showMain(.messages(device.id))
            case let .clip(id):
                guard let item = ClipboardHistory.shared.items.first(where: { $0.id == id }) else {
                    FloatingToast.show(String(localized: "That is no longer in the clipboard history"), symbol: "exclamationmark.triangle.fill")
                    return
                }
                ClipboardPanelController.shared.copy(item)
            case let .file(id):
                guard let item = model.transfers.first(where: { $0.id == id }) else {
                    FloatingToast.show(String(localized: "The file is no longer there"), symbol: "exclamationmark.triangle.fill")
                    return
                }
                echoToast()
                model.open(item)
            case let .setting(section, _):
                AppScenes.showSettings(section)
            }
        }
    }

    private func known(_ id: String) async -> TandemDevice? {
        for _ in 0..<20 where model.device(id) == nil { try? await Task.sleep(for: .milliseconds(250)) }
        guard let device = model.device(id) else {
            FloatingToast.show(String(localized: "That device is no longer paired"), symbol: "exclamationmark.triangle.fill")
            return nil
        }
        return device
    }

    /// What the button on the device page does, after waiting a moment for a device that Spotlight's start of Tandem has not reached yet.
    private func run(_ action: SpotlightTarget.Action, on found: TandemDevice) async {
        switch action {
        case .files:
            AppScenes.showMain(.files(found.id))
            return
        case .messages:
            AppScenes.showMain(.messages(found.id))
            return
        default:
            break
        }
        for _ in 0..<40 {
            if let device = model.device(found.id), device.online || (device.ble && [.clipboard, .wifi].contains(action)) { break }
            try? await Task.sleep(for: .milliseconds(250))
        }
        let device = model.device(found.id) ?? found
        let reachable = device.online || (device.ble && [.clipboard, .wifi].contains(action))
        guard reachable else {
            FloatingToast.show(String(localized: "That device is not connected right now"), symbol: "wifi.slash")
            return
        }
        report("running \(action.rawValue) on \(device.id)")
        switch action {
        case .clipboard:
            echoToast()
            model.sendClipboard(to: [device.id])
        case .ring:
            if device.platform == .android {
                model.ring(device.id, on: true)
                FloatingToast.show(String(localized: "Your phone is ringing"), symbol: "bell.and.waves.left.and.right.fill")
            } else {
                FindOthers.shared.ring(device.id, on: true)
                FloatingToast.show(String(localized: "\(device.name) plays a sound"), symbol: "bell.and.waves.left.and.right.fill")
            }
        case .extend:
            if ScreenHost.shared.isExtending(to: device.id) { return }
            ScreenHost.shared.offerExtend(to: device)
        case .screen:
            if LiveManager.shared.session(of: .screen, on: device.id) == nil { LiveManager.shared.start(device: device, kind: .screen) }
        case .wifi:
            WifiShare.shared.share(with: device)
        case .files, .messages:
            break
        }
    }

    /// `showToast` of the model shows in the main window only, and a pick from Spotlight is often made with that window closed: the next
    /// one shows as a floating toast then.
    private func echoToast() {
        guard !AppScenes.mainVisible else { return }
        let asked = Date()
        withObservationTracking { _ = model.toast } onChange: {
            Task { @MainActor in
                guard Date().timeIntervalSince(asked) < 8, let text = EngineModel.shared.toast else { return }
                FloatingToast.show(text, symbol: "info.circle.fill")
            }
        }
    }

    private func report(_ line: String) {
        guard DebugSupport.directory != nil else { return }
        reportLines.append(line)
        DebugSupport.write(reportLines.joined(separator: "\n") + "\n", named: "spotlight-picks.txt")
    }

    private var reportLines: [String] = []

    // MARK: Files

    private static var indexFile: URL { EngineModel.supportDirectory().appendingPathComponent("spotlight-index.json") }

    private static func loadIndexed() -> [String: [String: String]] {
        guard let data = try? Data(contentsOf: indexFile) else { return [:] }
        return (try? JSONDecoder().decode([String: [String: String]].self, from: data)) ?? [:]
    }

    private static func saveIndexed(_ value: [String: [String: String]]) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        try? data.write(to: indexFile, options: .atomic)
    }

    private static var conversationsDirectory: URL {
        EngineModel.supportDirectory().appendingPathComponent("spotlight-messages", isDirectory: true)
    }

    private static func conversationsFile(_ phone: String) -> URL { conversationsDirectory.appendingPathComponent("\(phone).json") }

    private static func storedPhones() -> [String] {
        let files = (try? FileManager.default.contentsOfDirectory(atPath: conversationsDirectory.path)) ?? []
        return files.filter { $0.hasSuffix(".json") }.map { String($0.dropLast(5)) }
    }

    private static func writeConversations(_ list: [Conversation], phone: String) {
        let url = conversationsFile(phone)
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard let data = try? JSONEncoder().encode(list) else { return }
        // Who wrote what: only this user may read it.
        try? data.write(to: url, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    private static func readConversations(_ phone: String) -> [Conversation] {
        guard let data = try? Data(contentsOf: conversationsFile(phone)) else { return [] }
        return (try? JSONDecoder().decode([Conversation].self, from: data)) ?? []
    }

    // MARK: Checking it without devices

    private static let debugDomain = "tandem-debug"

    /// `TANDEM_DEBUG_SPOTLIGHT=fake` also puts made up results of every kind in the index (a phone and a computer that are not paired,
    /// their actions, a conversation and a file), made by the same code as the real ones, and writes what a
    /// query inside the app finds to `spotlight-kinds.txt`; `=clear` takes them out. `TANDEM_DEBUG_SPOTLIGHT_OPEN=<identifier>` does
    /// what picking a result does (see `PhoneSpotlight.debug`), and `spotlight-picks.txt` says what came of it.
    static func debug(into directory: URL) {
        let environment = ProcessInfo.processInfo.environment
        switch environment["TANDEM_DEBUG_SPOTLIGHT"] {
        case "fake":
            let status = TandemStatus(battery: nil, network: nil, hotspot: nil, dnd: nil, locked: nil, freeStorage: nil, asleep: nil, wakeMac: nil, muted: nil)
            func device(_ id: String, _ name: String, _ platform: TandemPlatform, _ caps: [String]) -> TandemDevice {
                TandemDevice(id: id, name: name, platform: platform, online: true, route: nil, rttMs: nil, status: status, appVersion: "0.2.15", caps: caps,
                             vouchedByRemoved: false, clipboardEnabled: true, autoAccept: true, notificationsEnabled: true, ble: false, features: [])
            }
            let phone = device("debugphone", "Zebediah Pixel", .android, ["sms", "files", "wifi.join", "screen.host", "screen.extend.view"])
            let mac = device("debugmac", "Zebediah Mac", .macOs, ["ring", "files", "screen.host"])
            let conversation = Conversation(thread: 7, name: "Zebediah Testcontact", addresses: ["+31600000011"], snippet: "Zie je morgen bij het station", date: 1_760_000_000_000)
            let file = TransferItem(id: "debug-1", peer: "debugphone", name: "Zebediah vakantie.jpg", done: 10, total: 10, incoming: true, state: .done,
                                    location: "/tmp/none.jpg", error: nil, started: Date(), updated: Date())
            var entries = [deviceEntry(phone, symbol: nil), deviceEntry(mac, symbol: nil)]
            entries += actionEntries(phone, wifiWorth: true, canExtend: true) + actionEntries(mac, wifiWorth: false, canExtend: true)
            entries.append(conversationEntry(conversation, phone: phone.id, phoneName: phone.name))
            entries.append(fileEntry(file, peerName: phone.name))
            entries = entries.map { var entry = $0; entry.domain = debugDomain + "." + entry.domain; return entry }
            shared.apply(Dictionary(grouping: entries, by: \.domain).merging(shared.realWanted(), uniquingKeysWith: { a, _ in a }))
        case "clear":
            let domains = shared.indexed.keys.filter { $0.hasPrefix(debugDomain) }
            for domain in domains { shared.indexed[domain] = nil }
            saveIndexed(shared.indexed)
            shared.index.deleteSearchableItems(withDomainIdentifiers: Array(domains)) { _ in }
        default:
            break
        }
        // `TANDEM_DEBUG_SPOTLIGHT_SHOW=clipboard|shared`: after the window was closed (`TANDEM_DEBUG_NO_WINDOW`), opens it on that page the way a
        // pick does, for a check without a paired device.
        if let page = environment["TANDEM_DEBUG_SPOTLIGHT_SHOW"] {
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(8))
                AppScenes.showMain(page == "shared" ? .shared : .clipboard)
            }
        }
        guard environment["TANDEM_DEBUG_SPOTLIGHT"] != nil else { return }
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(10))
            let queries = [
                "title == \"Zebediah*\"cd",
                "title == \"Send clipboard*\"cd || title == \"Klembord naar*\"cd",
                "keywords == \"bellen\"cd",
                "keywords == \"scherm\"cd",
                "keywords == \"klembord\"cd",
                "textContent == \"*station*\"cd",
                "title == \"Universal mouse\"cd || title == \"Universele muis\"cd",
                "keywords == \"instellingen\"cd",
            ]
            var lines: [String] = []
            for query in queries {
                let found = await search(query)
                lines.append("\(query): \(found.count)")
                lines += found.map { "  \($0)" }
            }
            try? lines.joined(separator: "\n").appending("\n").write(to: directory.appendingPathComponent("spotlight-kinds.txt"), atomically: true, encoding: .utf8)
        }
    }

    /// What the real kinds want now, so the made up results do not make the real ones look gone.
    private func realWanted() -> [String: [SpotlightEntry]] {
        var wanted: [String: [SpotlightEntry]] = [:]
        for kind in SpotlightKind.allCases where kind.enabled {
            for entry in entries(kind) { wanted[entry.domain, default: []].append(entry) }
        }
        return wanted
    }

    private static func search(_ query: String) async -> [String] {
        await withCheckedContinuation { continuation in
            let context = CSSearchQueryContext()
            context.fetchAttributes = ["title", "contentDescription"]
            let query = CSSearchQuery(queryString: query, queryContext: context)
            var found: [String] = []
            query.foundItemsHandler = { items in
                found += items.map { "\($0.uniqueIdentifier)\t\($0.attributeSet.title ?? "")\t\($0.attributeSet.contentDescription ?? "")" }
            }
            query.completionHandler = { error in
                if let error { found.append("error \(error.localizedDescription)") }
                continuation.resume(returning: found.sorted())
            }
            query.start()
        }
    }
}

extension TandemSpotlight.Conversation {
    init(_ conversation: TandemSmsConversation) {
        self.init(thread: conversation.thread, name: conversation.name, addresses: conversation.addresses, snippet: conversation.snippet, date: conversation.date)
    }
}

// MARK: Opening the windows

/// Opens the main window on a page, and Settings on a section, from outside the views (a pick in Spotlight). The actions SwiftUI gives
/// to views are kept from the first view that had them; the windows themselves and the menu are the way when there is none yet.
@MainActor
enum AppScenes {
    static var openWindow: OpenWindowAction?
    static var openSettings: OpenSettingsAction?

    private static var mainWindow: NSWindow? {
        NSApp.windows.first { $0.identifier?.rawValue.hasPrefix("main") == true && $0.styleMask.contains(.titled) }
    }

    static var mainVisible: Bool { mainWindow?.isVisible == true && NSApp.isActive }

    static func showMain(_ selection: SidebarSelection) {
        MainWindow.requested = selection
        NSApp.activate(ignoringOtherApps: true)
        if let openWindow {
            openWindow(id: "main")
        } else if let window = mainWindow {
            window.makeKeyAndOrderFront(nil)
        }
        // A window that is already there takes the page now; one that is made from nothing takes it when it appears.
        NotificationCenter.default.post(name: .tandemNavigate, object: nil)
        TandemSpotlight.shared.reportScene("main \(selection) via \(openWindow != nil ? "openWindow" : mainWindow != nil ? "window" : "nothing")")
    }

    static func showSettings(_ section: SettingsSection) {
        SettingsSection.requested = section
        NSApp.activate(ignoringOtherApps: true)
        var how = "nothing"
        if let openSettings {
            openSettings()
            how = "openSettings"
        } else if let item = settingsMenuItem, let menu = item.menu {
            menu.performActionForItem(at: menu.index(of: item))
            how = "menu"
        }
        NotificationCenter.default.post(name: .tandemSettingsSection, object: nil)
        TandemSpotlight.shared.reportScene("settings \(section.rawValue) via \(how)")
    }

    /// The Settings item of the app menu, which SwiftUI makes with Command-comma.
    private static var settingsMenuItem: NSMenuItem? {
        NSApp.mainMenu?.items.first?.submenu?.items.first { $0.keyEquivalent == "," && $0.keyEquivalentModifierMask == .command }
    }
}

extension TandemSpotlight {
    fileprivate func reportScene(_ line: String) { report(line) }
}

extension Notification.Name {
    static let tandemNavigate = Notification.Name("tandem.navigate")
    static let tandemSettingsSection = Notification.Name("tandem.settingsSection")
}

extension MainWindow {
    /// The page a pick asked for, taken by the window when it appears or is told.
    @MainActor static var requested: SidebarSelection?
}

extension SettingsSection {
    @MainActor static var requested: SettingsSection?
}

/// On the main window: keeps the actions that open windows, and goes to a page a pick asked for.
struct SpotlightNavigation: ViewModifier {
    @Binding var selection: SidebarSelection?
    @Environment(\.openWindow) private var openWindow
    @Environment(\.openSettings) private var openSettings

    func body(content: Content) -> some View {
        content
            .onAppear {
                AppScenes.openWindow = openWindow
                AppScenes.openSettings = openSettings
                take()
            }
            .onReceive(NotificationCenter.default.publisher(for: .tandemNavigate)) { _ in take() }
    }

    private func take() {
        guard let wanted = MainWindow.requested else { return }
        MainWindow.requested = nil
        selection = wanted
    }
}

/// On Settings: goes to the section a pick asked for.
struct SpotlightSettingsNavigation: ViewModifier {
    @Binding var selection: SettingsSection

    func body(content: Content) -> some View {
        content
            .onAppear(perform: take)
            .onReceive(NotificationCenter.default.publisher(for: .tandemSettingsSection)) { _ in take() }
    }

    private func take() {
        guard let wanted = SettingsSection.requested else { return }
        SettingsSection.requested = nil
        selection = wanted
        if DebugSupport.directory != nil { DebugSupport.write("settings section \(wanted.rawValue)\n", named: "spotlight-settings.txt") }
    }
}

// MARK: Settings

/// The switches of Spotlight, one per kind, with the contacts of the phone.
struct SpotlightSettingsSection: View {
    @AppStorage(PhoneSpotlight.enabledKey) private var contacts = true
    @AppStorage(SpotlightKind.devices.key) private var devices = SpotlightKind.devices.byDefault
    @AppStorage(SpotlightKind.actions.key) private var actions = SpotlightKind.actions.byDefault
    @AppStorage(SpotlightKind.conversations.key) private var conversations = SpotlightKind.conversations.byDefault
    @AppStorage(SpotlightKind.clipboard.key) private var clipboard = SpotlightKind.clipboard.byDefault
    @AppStorage(SpotlightKind.files.key) private var files = SpotlightKind.files.byDefault
    @AppStorage(SpotlightKind.settings.key) private var settings = SpotlightKind.settings.byDefault

    var body: some View {
        Section {
            DescribedToggle(
                "Phone contacts in Spotlight",
                subtitle: "Type a name in Spotlight and pick the person: your phone calls them. Turned off, the contacts are taken out of Spotlight.",
                isOn: $contacts
            )
            .onChange(of: contacts) { _, on in PhoneSpotlight.shared.setEnabled(on) }
            DescribedToggle("Devices", subtitle: "Type the name of a device to open its page.", isOn: $devices)
            DescribedToggle("Actions", subtitle: "Such as Send clipboard to, Find, Extend screen to and Share Wi-Fi with, for each device that can.", isOn: $actions)
            DescribedToggle("Messages", subtitle: "The conversations of your phone, with the last message. Off at first: codes and private words would show to anyone who types here.", isOn: $conversations)
            DescribedToggle("Clipboard history", subtitle: "Texts and links you copied, to copy them again. Off at first, for the same reason.", isOn: $clipboard)
            DescribedToggle("Shared files", subtitle: "Files you sent and received, to open them.", isOn: $files)
            DescribedToggle("Settings", subtitle: "The pages and main switches of these settings.", isOn: $settings)
        } header: {
            Text("Spotlight")
        } footer: {
            Text("What Tandem puts in Spotlight stays on this Mac. Turned off, it is taken out at once.")
        }
        .onChange(of: [devices, actions, conversations, clipboard, files, settings]) { TandemSpotlight.shared.kindChanged() }
    }
}

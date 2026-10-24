import AppKit
import Foundation
import Observation
import TandemCore

/// The text messages of one phone as this Mac knows them so far: the conversations page by page, and the messages of each
/// conversation that was opened. The phone is asked when a page is opened, and tells this Mac itself about every message that
/// is new or changed after that.
@MainActor
@Observable
final class PhoneMessages {
    enum Load: Equatable {
        case idle
        case loading
        case ready
        /// The phone will not, and the person can do something about it there.
        case refused(Refusal)
        case failed(String)
    }

    enum Refusal: Equatable {
        /// Messages is switched off for this Mac on the phone.
        case off
        /// Android has not given Tandem the permission yet.
        case noPermission
        case unavailable
        case offline
    }

    struct Thread {
        /// Newest first, as the phone gives them.
        var messages: [TandemSmsMessage] = []
        var more = true
        var loading = false
        var failed: String?
    }

    /// A text written here that the phone has not shown back yet.
    struct Pending: Identifiable, Equatable {
        enum State: Equatable { case sending, sent, failed(String) }
        let id = UUID()
        let thread: UInt64
        let text: String
        let date: Date
        var state: State
    }

    let device: String
    /// Newest first.
    private(set) var conversations: [TandemSmsConversation] = []
    private(set) var moreConversations = false
    private(set) var loadingMore = false
    var load: Load = .idle
    private(set) var threads: [UInt64: Thread] = [:]
    private(set) var pending: [Pending] = []
    private(set) var pictures: [UInt64: NSImage] = [:]
    /// The conversation on screen, which counts as read here as long as it is.
    var open: UInt64? {
        didSet { if let open { markSeen(open) } }
    }
    /// Until when each conversation was seen on this Mac. The phone's own unread count only changes when it is read there.
    private var seen: [UInt64: UInt64]
    @ObservationIgnored private var askedPictures: Set<UInt64> = []

    init(device: String) {
        self.device = device
        let stored = UserDefaults.standard.dictionary(forKey: Self.seenKey(device)) as? [String: UInt64] ?? [:]
        seen = Dictionary(uniqueKeysWithValues: stored.compactMap { key, value in UInt64(key).map { ($0, value) } })
    }

    private var engine: TandemEngine? { EngineModel.shared.tandem }

    func conversation(_ thread: UInt64) -> TandemSmsConversation? { conversations.first { $0.thread == thread } }

    /// The dot in the list: the phone has it as unread, and it has not been looked at here since the last message.
    func unread(_ conversation: TandemSmsConversation) -> Bool {
        conversation.unread > 0 && conversation.date > (seen[conversation.thread] ?? 0) && conversation.thread != open
    }

    var unreadCount: Int { conversations.filter(unread).count }

    // MARK: Asking the phone

    /// The newest conversations, again from the top.
    func refresh() async {
        guard let engine else { return }
        if conversations.isEmpty { load = .loading }
        do {
            let page = try await engine.smsConversations(target: device, before: 0, limit: 40)
            conversations = merged(page.items, into: [])
            moreConversations = page.more
            load = .ready
            // What is open is looked at again too, for what changed while this Mac was not listening.
            if let open { await reloadThread(open) }
        } catch {
            load = Self.state(of: error)
        }
    }

    func loadMoreConversations() async {
        guard let engine, moreConversations, !loadingMore, let last = conversations.last else { return }
        loadingMore = true
        defer { loadingMore = false }
        if let page = try? await engine.smsConversations(target: device, before: last.date, limit: 40) {
            let before = conversations.count
            conversations = merged(page.items, into: conversations)
            // A page with nothing new is the end, whatever it says.
            moreConversations = page.more && conversations.count > before
        }
    }

    /// The first page of a conversation, when it has none yet.
    func openThread(_ thread: UInt64) async {
        open = thread
        if threads[thread]?.messages.isEmpty ?? true { await reloadThread(thread) }
    }

    private func reloadThread(_ thread: UInt64) async {
        guard let engine else { return }
        threads[thread, default: Thread()].loading = true
        do {
            let page = try await engine.smsMessages(target: device, thread: thread, before: 0, limit: 50)
            var state = threads[thread] ?? Thread()
            state.messages = mergedMessages(page.items, into: state.messages)
            state.more = page.more
            state.loading = false
            state.failed = nil
            threads[thread] = state
            settle(thread)
        } catch {
            threads[thread]?.loading = false
            threads[thread]?.failed = Self.text(of: error)
        }
    }

    func loadOlder(_ thread: UInt64) async {
        guard let engine, var state = threads[thread], state.more, !state.loading, let oldest = state.messages.last else { return }
        state.loading = true
        threads[thread] = state
        let page = try? await engine.smsMessages(target: device, thread: thread, before: oldest.date, limit: 50)
        state = threads[thread] ?? state
        state.loading = false
        if let page {
            let before = state.messages.count
            state.messages = mergedMessages(page.items, into: state.messages)
            state.more = page.more && state.messages.count > before
        }
        threads[thread] = state
    }

    /// Sends `text` into a conversation. It shows at once, and turns into the real message when the phone tells of it.
    func send(_ text: String, in conversation: TandemSmsConversation) {
        let item = Pending(thread: conversation.thread, text: text, date: Date(), state: .sending)
        pending.append(item)
        Task {
            let state: Pending.State
            do {
                _ = try await engine?.smsSend(target: device, to: conversation.addresses, text: text)
                state = .sent
            } catch {
                state = .failed(Self.text(of: error))
            }
            if let index = pending.firstIndex(where: { $0.id == item.id }) { pending[index].state = state }
        }
    }

    /// Writes again a text that could not be sent.
    func retry(_ item: Pending) {
        pending.removeAll { $0.id == item.id }
        if let conversation = conversation(item.thread) { send(item.text, in: conversation) }
    }

    func discard(_ item: Pending) {
        pending.removeAll { $0.id == item.id }
    }

    /// A new message to someone this phone may not have written to yet. Returns the conversation it went to.
    func start(to number: String, name: String, text: String) async throws -> UInt64? {
        guard let engine else { throw TandemSmsError.NotConnected }
        let thread = try await engine.smsSend(target: device, to: [number], text: text)
        await refresh()
        // Android writes the message down once it is out, which can take a moment: until then it stands here as written.
        if let thread, conversation(thread) == nil {
            let now = Date()
            let placeholder = TandemSmsConversation(
                thread: thread, addresses: [number], name: name, snippet: text, date: UInt64(now.timeIntervalSince1970 * 1000), unread: 0, fromMe: true
            )
            conversations = merged([placeholder], into: conversations)
            pending.append(Pending(thread: thread, text: text, date: now, state: .sent))
        }
        if let thread { await openThread(thread) }
        return thread
    }

    func contacts(matching query: String) async -> [TandemSmsContact] {
        guard let engine, !query.trimmingCharacters(in: .whitespaces).isEmpty else { return [] }
        return (try? await engine.smsContacts(target: device, query: query)) ?? []
    }

    /// The picture of an MMS, fetched once and kept while the app runs.
    func picture(_ part: UInt64) {
        guard pictures[part] == nil, !askedPictures.contains(part), let engine else { return }
        askedPictures.insert(part)
        Task {
            if let data = try? await engine.smsPicture(target: device, part: part), let image = NSImage(data: data) {
                pictures[part] = image
            }
        }
    }

    /// Asks the person on the phone to let this Mac in. The phone shows the question, there is no answer to wait for.
    func askAccess() async -> Bool {
        guard let engine else { return false }
        return (try? await engine.smsAskAccess(target: device)) != nil
    }

    // MARK: What the phone tells

    func changed(conversations changes: [TandemSmsConversation], messages: [TandemSmsMessage]) {
        // Before the first page there is nothing to keep up to date: opening the page asks for everything.
        guard load == .ready else { return }
        conversations = merged(changes, into: conversations)
        for (thread, items) in Dictionary(grouping: messages, by: \.thread) {
            guard var state = threads[thread] else { continue }
            state.messages = mergedMessages(items, into: state.messages)
            threads[thread] = state
            settle(thread)
        }
        if let open { markSeen(open) }
    }

    /// A text written here is done waiting once the phone shows the same text going out in that conversation.
    private func settle(_ thread: UInt64) {
        let mine = threads[thread]?.messages.filter { $0.status != .received } ?? []
        pending.removeAll { item in
            if case .failed = item.state { return false }
            // The clock of the phone may be a little off from this one.
            let since = UInt64(max(0, item.date.timeIntervalSince1970 - 30) * 1000)
            return item.thread == thread && mine.contains { $0.body == item.text && $0.date >= since }
        }
    }

    private func markSeen(_ thread: UInt64) {
        guard let date = conversation(thread)?.date, date > (seen[thread] ?? 0) else { return }
        seen[thread] = date
        let stored = Dictionary(uniqueKeysWithValues: seen.map { (String($0.key), $0.value) })
        UserDefaults.standard.set(stored, forKey: Self.seenKey(device))
    }

    // MARK: Lists

    private func merged(_ items: [TandemSmsConversation], into list: [TandemSmsConversation]) -> [TandemSmsConversation] {
        var byThread = Dictionary(list.map { ($0.thread, $0) }, uniquingKeysWith: { a, _ in a })
        for item in items { byThread[item.thread] = item }
        return byThread.values.sorted { ($0.date, $0.thread) > ($1.date, $1.thread) }
    }

    private func mergedMessages(_ items: [TandemSmsMessage], into list: [TandemSmsMessage]) -> [TandemSmsMessage] {
        var byKey = Dictionary(list.map { (Self.key($0), $0) }, uniquingKeysWith: { a, _ in a })
        for item in items { byKey[Self.key(item)] = item }
        return byKey.values.sorted { ($0.date, $0.id) > ($1.date, $1.id) }
    }

    nonisolated static func key(_ message: TandemSmsMessage) -> String { (message.mms ? "m" : "s") + String(message.id) }

    private static func seenKey(_ device: String) -> String { "messagesSeen.\(device)" }

    static func state(of error: Error) -> Load {
        switch error as? TandemSmsError {
        case .Off: .refused(.off)
        case .NoPermission: .refused(.noPermission)
        case .Unavailable: .refused(.unavailable)
        case .NotConnected: .refused(.offline)
        default: .failed(text(of: error))
        }
    }

    static func text(of error: Error) -> String {
        switch error as? TandemSmsError {
        case .Off: String(localized: "Messages is off for this Mac on the phone")
        case .NoPermission: String(localized: "The phone has not allowed Tandem to read its messages")
        case .NotConnected: String(localized: "The phone is not connected")
        case let .Failed(reason): reason
        default: error.localizedDescription
        }
    }
}

extension PhoneMessages {
    /// Made up conversations for a snapshot of the page (`TANDEM_DEBUG_MESSAGES`), with nothing asked of a phone.
    func fillForDebug() {
        let now = UInt64(Date().timeIntervalSince1970 * 1000)
        let hour: UInt64 = 3_600_000
        func conversation(_ thread: UInt64, _ name: String, _ number: String, _ snippet: String, _ ago: UInt64, unread: UInt32 = 0, mine: Bool = false) -> TandemSmsConversation {
            TandemSmsConversation(thread: thread, addresses: number.components(separatedBy: ", "), name: name, snippet: snippet, date: now - ago, unread: unread, fromMe: mine)
        }
        conversations = [
            conversation(1, "Anna de Vries", "+31612345678", "Zie je morgen bij het station!", hour / 6, unread: 2),
            conversation(2, "", "+31687654321", "Uw pakket wordt vandaag tussen 14:00 en 16:00 bezorgd.", 3 * hour),
            conversation(3, "Bob, Carla", "+31611111111, +31622222222", "Wie neemt de taart mee?", 26 * hour),
            conversation(4, "Mam", "+31633333333", "Bel je me straks even?", 50 * hour, mine: true),
            conversation(5, "Tandarts", "+31201234567", "Herinnering: uw afspraak is op dinsdag om 09:30.", 9 * 24 * hour),
        ]
        func message(_ id: UInt64, _ body: String, _ ago: UInt64, mine: Bool, status: TandemSmsStatus? = nil, pictures: [UInt64] = []) -> TandemSmsMessage {
            TandemSmsMessage(id: id, mms: !pictures.isEmpty, thread: 1, address: mine ? "" : "+31612345678", body: body, date: now - ago,
                             status: status ?? (mine ? .sent : .received), read: true, pictures: pictures)
        }
        threads[1] = Thread(messages: [
            message(9, "Zie je morgen bij het station!", hour / 6, mine: false),
            message(8, "Om half negen?", hour / 5, mine: false),
            message(7, "Top, ik neem de trein van 8:12. Dan ben ik er ruim op tijd en kunnen we nog even koffie halen voordat het begint.", hour / 3, mine: true),
            message(6, "", 2 * hour, mine: false, pictures: [1]),
            message(5, "Kijk wat ik vond", 2 * hour + 60_000, mine: false),
            message(4, "Lukt het morgen?", 25 * hour, mine: true, status: .failed),
            message(3, "Ja hoor, prima", 26 * hour, mine: false),
            message(2, "Zullen we morgen samen gaan?", 26 * hour + 120_000, mine: true),
        ], more: false)
        load = .ready
    }
}

/// The messages of every phone, by device.
@MainActor
final class MessagesStore {
    static let shared = MessagesStore()
    private var phones: [String: PhoneMessages] = [:]

    func phone(_ id: String) -> PhoneMessages {
        if let known = phones[id] { return known }
        let made = PhoneMessages(device: id)
        phones[id] = made
        return made
    }

    func handle(from: String, event: TandemSmsEvent) {
        if case let .changed(conversations, messages) = event {
            phones[from]?.changed(conversations: conversations, messages: messages)
        }
    }
}

import AppKit
@preconcurrency import CoreSpotlight
import SwiftUI
import TandemCore
import UniformTypeIdentifiers

/// The contacts of a paired phone in Spotlight: type a name, pick the person, and the phone calls them. Each phone has its own domain in
/// the index, so a phone that is removed (or the setting turned off) takes exactly its own people with it. The list is fetched from the
/// phone when it connects and again every few hours while it stays, and kept in a file next to the data of the engine, because a
/// result is picked when the phone may be away and Spotlight only hands back the identifier.
@MainActor
final class PhoneSpotlight {
    static let shared = PhoneSpotlight()
    static let enabledKey = "phoneContactsSpotlight"
    /// The phones whose people are in the index, so a phone removed while Tandem was not running is still taken out at the next start.
    private static let indexedKey = "phoneContactsSpotlightPhones"
    private static let refreshEvery: TimeInterval = 3 * 3600
    /// After a phone that did not answer, before it is asked again.
    private static let retryAfter: TimeInterval = 10 * 60

    static var enabled: Bool { UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? true }

    private let index = CSSearchableIndex.default()
    private var lastAsked: [String: Date] = [:]
    private var asking: Set<String> = []
    private var ticker: Task<Void, Never>?

    struct Number: Codable, Equatable {
        var number: String
        var label: String
        var preferred: Bool
    }

    struct Person: Codable, Equatable {
        var id: UInt64
        var name: String
        var numbers: [Number]
    }

    /// Called whenever the devices change. Cheap when nothing is due.
    func devicesChanged(_ devices: [TandemDevice], engine: TandemEngine?) {
        if ticker == nil {
            ticker = Task { @MainActor [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(15 * 60))
                    self?.devicesChanged(EngineModel.shared.devices, engine: EngineModel.shared.tandem)
                }
            }
        }
        // Before the engine runs there is no list of devices, which is not the same as every phone gone.
        guard let engine else { return }
        let known = Set(devices.map(\.id))
        for id in Self.indexed where (!known.contains(id) && id != Self.fakePhone) || !Self.enabled { forget(id) }
        guard Self.enabled else { return }
        for device in devices where device.online && device.caps.contains("sms.contacts") {
            if let last = lastAsked[device.id], Date().timeIntervalSince(last) < Self.refreshEvery { continue }
            refresh(device, engine: engine)
        }
    }

    /// The switch in Settings. Off takes every phone out of the index at once.
    func setEnabled(_ on: Bool) {
        UserDefaults.standard.set(on, forKey: Self.enabledKey)
        lastAsked = [:]
        if !on { Self.indexed.forEach(forget) }
        devicesChanged(EngineModel.shared.devices, engine: EngineModel.shared.tandem)
    }

    private func refresh(_ device: TandemDevice, engine: TandemEngine) {
        guard !asking.contains(device.id) else { return }
        asking.insert(device.id)
        let id = device.id
        Task { @MainActor in
            defer { asking.remove(id) }
            do {
                let people = try await engine.smsAllContacts(target: id).map(Person.init)
                guard Self.enabled else { return }
                lastAsked[id] = Date()
                put(people, phone: id)
            } catch let error as TandemSmsError {
                switch error {
                case .Off, .NoPermission, .Unavailable:
                    // The phone says no (Messages off for this Mac, or no permission for the contacts): its people go.
                    lastAsked[id] = Date()
                    forget(id)
                case .NotConnected, .Failed:
                    lastAsked[id] = Date().addingTimeInterval(Self.retryAfter - Self.refreshEvery)
                }
            } catch {
                lastAsked[id] = Date().addingTimeInterval(Self.retryAfter - Self.refreshEvery)
            }
        }
    }

    /// Puts the people of one phone in the index in place of what was there.
    func put(_ people: [Person], phone: String) {
        Self.write(people, phone: phone)
        let domain = Self.domain(phone)
        let items = people.map { person in
            let attributes = CSSearchableItemAttributeSet(contentType: .contact)
            attributes.title = person.name
            attributes.displayName = person.name
            attributes.contentDescription = person.numbers.map { $0.label.isEmpty ? $0.number : "\($0.label) \($0.number)" }.joined(separator: ", ")
            attributes.phoneNumbers = person.numbers.map(\.number)
            // Not `supportsPhoneCall`: Spotlight would then offer the call of the Mac itself, not the phone's.
            attributes.keywords = person.name.split(separator: " ").map(String.init) + person.numbers.map(\.number)
            return CSSearchableItem(uniqueIdentifier: Self.identifier(phone: phone, person: person.id), domainIdentifier: domain, attributeSet: attributes)
        }
        Task { @MainActor in
            do {
                // Who was removed from the phone goes too: the domain is emptied before the people of now go in.
                try await index.deleteSearchableItems(withDomainIdentifiers: [domain])
                try await index.indexSearchableItems(items)
            } catch {
                NSLog("Tandem: the contacts of the phone could not go into Spotlight: \(error.localizedDescription)")
            }
        }
        var indexed = Self.indexed
        indexed.insert(phone)
        Self.indexed = indexed
    }

    /// Takes one phone's people out of the index and off the disk.
    func forget(_ phone: String) {
        index.deleteSearchableItems(withDomainIdentifiers: [Self.domain(phone)]) { _ in }
        try? FileManager.default.removeItem(at: Self.file(phone))
        var indexed = Self.indexed
        indexed.remove(phone)
        Self.indexed = indexed
        lastAsked[phone] = nil
    }

    // MARK: Picked in Spotlight

    /// A result was picked: the phone calls the person, always after asking, as every call from this Mac is (a pick is easily a slip).
    /// With one number a card asks whether to call it; with more, the card that asks which one is that question.
    func open(identifier: String) {
        guard let (phone, personId) = Self.parse(identifier),
              let person = Self.read(phone).first(where: { $0.id == personId })
        else {
            FloatingToast.show(String(localized: "That contact is no longer on the phone"), symbol: "exclamationmark.triangle.fill")
            return
        }
        if person.numbers.count == 1 {
            confirm(person.name, number: person.numbers[0], on: phone)
            return
        }
        // The one the phone would call without asking goes first, where Return picks it.
        let choices = person.numbers.enumerated()
            .sorted { ($0.element.preferred ? 0 : 1, $0.offset) < ($1.element.preferred ? 0 : 1, $1.offset) }
            .map { NumberChooser.Choice(number: $0.element.number, label: $0.element.label) }
        asked += 1
        NumberChooser.shared.ask(name: person.name, choices: choices) { [weak self] number in self?.call(number, name: person.name, on: phone) }
    }

    /// "Call Anna de Vries?" with Cancel and Call in the column of floating cards, since Spotlight leaves no window to ask in. Return
    /// calls, Escape cancels, and nobody answering calls nobody.
    private func confirm(_ name: String, number: Number, on phone: String) {
        let what = [number.label, number.number].filter { !$0.isEmpty }.joined(separator: " ")
        let via = EngineModel.shared.device(phone)?.name ?? ""
        GlassQuestion.shared.ask(
            symbol: "phone.fill",
            title: String(localized: "Call \(name)?"),
            body: via.isEmpty ? what : String(localized: "\(what) via \(via)"),
            answers: [
                .init(title: "Cancel") {},
                .init(title: "Call", prominent: true) { [weak self] in self?.call(number.number, name: name, on: phone) },
            ]
        )
    }

    /// How often a person was asked which number, for the check below.
    private(set) var asked = 0

    private func call(_ number: String, name: String, on phone: String) {
        Task { @MainActor in
            // Spotlight may just have started Tandem: the phone needs a moment to be found.
            for _ in 0..<40 {
                if EngineModel.shared.device(phone)?.online == true { break }
                try? await Task.sleep(for: .milliseconds(250))
            }
            guard let device = EngineModel.shared.device(phone) else {
                FloatingToast.show(String(localized: "That phone is no longer paired"), symbol: "exclamationmark.triangle.fill")
                return
            }
            guard let engine = EngineModel.shared.tandem else { return }
            CallConfirmation.dial(number, device: device.id, deviceName: device.name, name: name, engine: engine) {
                FloatingToast.show($0, symbol: "phone.fill")
            }
        }
    }

    // MARK: Checking it without a phone

    /// The made-up phone of the check below, which is not a device of the circle and so must not be taken out for not being one.
    private static let fakePhone = "debugphone"

    /// `TANDEM_DEBUG_SPOTLIGHT=fake` puts made-up people of a phone that does not exist in the index of this app and writes what
    /// Spotlight finds of them to `spotlight-report.txt`; `=clear` takes them out again. `TANDEM_DEBUG_SPOTLIGHT_OPEN=<identifier>` does
    /// what picking that result does. Only for the Dev app with its own data folder, never the real one.
    static func debug(into directory: URL) {
        let environment = ProcessInfo.processInfo.environment
        switch environment["TANDEM_DEBUG_SPOTLIGHT"] {
        case "fake":
            let people = [
                Person(id: 11, name: "Zebediah Testcontact", numbers: [Number(number: "+31600000011", label: "Mobile", preferred: true)]),
                Person(id: 12, name: "Zebediah Tweenummer", numbers: [
                    Number(number: "+31600000012", label: "Mobile", preferred: false),
                    Number(number: "+31200000012", label: "Work", preferred: true),
                    Number(number: "+31100000012", label: "Home", preferred: false),
                ]),
            ]
            shared.put(people, phone: fakePhone)
        case "clear":
            shared.forget(fakePhone)
        default:
            break
        }
        if environment["TANDEM_DEBUG_SPOTLIGHT"] != nil {
            Task { @MainActor in
                // After the engine started and the devices were looked at, so a fake phone that was taken out for not being one shows.
                try? await Task.sleep(for: .seconds(8))
                let found = await search("Zebediah")
                let cached = read(fakePhone).count
                let report = (["found \(found.count), \(cached) on disk"] + found).joined(separator: "\n") + "\n"
                try? report.write(to: directory.appendingPathComponent("spotlight-report.txt"), atomically: true, encoding: .utf8)
            }
        }
        if let identifier = environment["TANDEM_DEBUG_SPOTLIGHT_OPEN"] {
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(3))
                let before = shared.asked
                // The way Spotlight hands over a pick: a user activity to the delegate of the app, which SwiftUI passes on.
                let activity = NSUserActivity(activityType: CSSearchableItemActionType)
                activity.userInfo = [CSSearchableItemActivityIdentifier: identifier]
                let taken = NSApp.delegate?.application?(NSApp, continue: activity, restorationHandler: { _ in }) ?? false
                try? await Task.sleep(for: .milliseconds(300))
                let what = shared.asked > before ? "asked which number" : "no question (called, or the contact is not known)"
                try? "\(identifier): taken \(taken), \(what)\n".write(to: directory.appendingPathComponent("spotlight-open.txt"), atomically: true, encoding: .utf8)
            }
        }
    }

    /// What the index of this app holds for `text`, as identifier and title.
    private static func search(_ text: String) async -> [String] {
        await withCheckedContinuation { continuation in
            let context = CSSearchQueryContext()
            context.fetchAttributes = ["title", "contentDescription"]
            let query = CSSearchQuery(queryString: "title == \"\(text)*\"cd", queryContext: context)
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

    // MARK: Keys and files

    private static func domain(_ phone: String) -> String { "phone-contacts.\(phone)" }

    static func identifier(phone: String, person: UInt64) -> String { "phone-contact/\(phone)/\(person)" }

    static func parse(_ identifier: String) -> (String, UInt64)? {
        let parts = identifier.split(separator: "/")
        guard parts.count == 3, parts[0] == "phone-contact", let person = UInt64(parts[2]) else { return nil }
        return (String(parts[1]), person)
    }

    private static var indexed: Set<String> {
        get { Set(UserDefaults.standard.stringArray(forKey: indexedKey) ?? []) }
        set { UserDefaults.standard.set(Array(newValue).sorted(), forKey: indexedKey) }
    }

    private static func file(_ phone: String) -> URL {
        EngineModel.supportDirectory().appendingPathComponent("spotlight-contacts", isDirectory: true).appendingPathComponent("\(phone).json")
    }

    private static func write(_ people: [Person], phone: String) {
        let url = file(phone)
        try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard let data = try? JSONEncoder().encode(people) else { return }
        // Names and numbers of other people: only this user may read them.
        try? data.write(to: url, options: .atomic)
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
    }

    static func read(_ phone: String) -> [Person] {
        guard let data = try? Data(contentsOf: file(phone)) else { return [] }
        return (try? JSONDecoder().decode([Person].self, from: data)) ?? []
    }
}

extension PhoneSpotlight.Person {
    init(_ contact: TandemPhoneContact) {
        self.init(id: contact.id, name: contact.name, numbers: contact.numbers.map { .init(number: $0.number, label: $0.label, preferred: $0.preferred) })
    }
}

import Foundation
import Observation
import TandemCore

/// The contacts of one phone as far as this Mac has asked for them. The phone only answers a search (at most 25 at a time, nothing
/// for an empty one), so the whole book is gathered by asking for each letter in turn, and a letter that fills a whole answer again
/// for each two letters it can start with. What comes in shows up at once.
@MainActor
@Observable
final class PhoneContacts {
    struct Number: Hashable {
        let number: String
        let label: String
    }

    /// Someone with all the numbers the phone has for them.
    struct Person: Identifiable, Hashable {
        let name: String
        var numbers: [Number]
        var id: String { name.isEmpty ? numbers.first?.number ?? "" : name }

        /// The number to write to first: a mobile one, since a landline cannot take a text.
        var preferred: Number? {
            numbers.first { $0.label.localizedCaseInsensitiveContains("mob") } ?? numbers.first
        }
    }

    enum Load: Equatable { case idle, loading, done }

    let device: String
    private(set) var people: [Person] = []
    private(set) var load: Load = .idle
    /// By the digits of the number, so one number written two ways is one.
    private var byNumber: [String: TandemSmsContact] = [:]

    private static var phones: [String: PhoneContacts] = [:]

    static func of(_ device: String) -> PhoneContacts {
        if let known = phones[device] { return known }
        let made = PhoneContacts(device: device)
        phones[device] = made
        return made
    }

    private init(device: String) {
        self.device = device
    }

    /// The whole book, once per run of the app. Asked one question after the other so the phone is never flooded.
    func loadAll() async {
        guard load == .idle, let engine = EngineModel.shared.tandem else { return }
        load = .loading
        let letters = "abcdefghijklmnopqrstuvwxyz".map(String.init)
        var full: [String] = []
        for letter in letters {
            guard let found = try? await engine.smsContacts(target: device, query: letter) else {
                // The phone went away or refuses: try again the next time the list is opened.
                load = .idle
                return
            }
            add(found)
            if found.count >= Self.answerLimit { full.append(letter) }
        }
        for letter in full {
            for second in letters {
                if let found = try? await engine.smsContacts(target: device, query: letter + second) { add(found) }
            }
        }
        load = .done
    }

    /// The people that match `query`, from what is known and from the phone itself, which knows the ones not gathered yet.
    func search(_ query: String, online: Bool) async {
        let query = query.trimmingCharacters(in: .whitespaces)
        guard online, !query.isEmpty, let engine = EngineModel.shared.tandem else { return }
        if let found = try? await engine.smsContacts(target: device, query: query) { add(found) }
    }

    func matching(_ query: String) -> [Person] {
        let query = query.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return people }
        let digits = Self.digits(query)
        return people.filter { person in
            person.name.localizedCaseInsensitiveContains(query)
                || (digits.count >= 3 && person.numbers.contains { Self.digits($0.number).contains(digits) })
        }
    }

    /// Who a number belongs to, as far as the phone told this Mac.
    func contact(for number: String) -> TandemSmsContact? {
        byNumber[Self.key(number)]
    }

    private func add(_ found: [TandemSmsContact]) {
        var changed = false
        for contact in found where byNumber[Self.key(contact.number)] == nil {
            byNumber[Self.key(contact.number)] = contact
            changed = true
        }
        guard changed else { return }
        var grouped: [String: Person] = [:]
        for contact in byNumber.values {
            let id = contact.name.isEmpty ? contact.number : contact.name
            grouped[id, default: Person(name: contact.name, numbers: [])].numbers.append(Number(number: contact.number, label: contact.label))
        }
        people = grouped.values
            .map { person in
                var person = person
                person.numbers.sort { $0.label == $1.label ? $0.number < $1.number : $0.label < $1.label }
                return person
            }
            .sorted { ($0.name.isEmpty ? $0.id : $0.name).localizedStandardCompare($1.name.isEmpty ? $1.id : $1.name) == .orderedAscending }
    }

    /// The same as `MAX_CONTACTS` in the core: an answer this long may have left people out.
    private static let answerLimit = 25

    static func digits(_ text: String) -> String {
        text.filter(\.isNumber)
    }

    /// The last nine digits: what is left of a Dutch (and most other) mobile number with the country code or the 0 in front taken off.
    static func key(_ number: String) -> String {
        let digits = digits(number)
        return digits.count > 9 ? String(digits.suffix(9)) : digits
    }

    /// Made up contacts for a snapshot of the page (`TANDEM_DEBUG_MESSAGES`), with nothing asked of a phone.
    func fillForDebug() {
        add([
            TandemSmsContact(name: "Anna de Vries", number: "+31 6 1234 5678", label: "Mobile"),
            TandemSmsContact(name: "Anna de Vries", number: "+31 20 555 0123", label: "Work"),
            TandemSmsContact(name: "Bob Jansen", number: "+31 6 1111 1111", label: "Mobile"),
            TandemSmsContact(name: "Carla Smit", number: "+31 6 2222 2222", label: "Mobile"),
            TandemSmsContact(name: "Mam", number: "+31 6 3333 3333", label: "Mobile"),
            TandemSmsContact(name: "Lotte Bakker", number: "+31 6 4444 4444", label: "Mobile"),
            TandemSmsContact(name: "Tandarts", number: "+31 20 123 4567", label: "Work"),
            TandemSmsContact(name: "Pieter van Dijk", number: "+31 6 5555 5555", label: "Mobile"),
            TandemSmsContact(name: "Pieter van Dijk", number: "+31 30 777 8899", label: "Home"),
            TandemSmsContact(name: "Sanne Visser", number: "+31 6 6666 6666", label: "Mobile"),
            TandemSmsContact(name: "Yusuf Kaya", number: "+31 6 7777 7777", label: "Mobile"),
        ])
        load = .done
    }
}

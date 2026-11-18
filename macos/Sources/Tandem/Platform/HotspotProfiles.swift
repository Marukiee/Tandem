import Foundation
import Observation
import TandemLogic

/// What this Mac knows of the hotspot of each phone: the name and the password, kept for each phone on its own. A new phone has a hotspot
/// of its own, and a phone can rename its hotspot or give it another password.
///
/// The names live in the preferences, the passwords in a private file (see `SecretFile`), never together with the preferences that travel
/// in a backup. What was filled in before there was one for each phone belongs to the first phone, the one that was always asked (`adoptLegacy`).
@MainActor @Observable
final class HotspotProfiles {
    static let shared = HotspotProfiles()

    private static let namesKey = "hotspotNames"
    private static let toldKey = "hotspotTold"
    private static let passwordsFile = "hotspot-phones.secret"
    private static let legacyNameKey = "hotspotSSID"
    private static let legacyPasswordFile = "hotspot.secret"

    private(set) var profiles: [String: HotspotProfile]
    /// What the person was last told about, for each phone: the name that was new, or the name this Mac had when joining failed.
    @ObservationIgnored private var told: [String: String]
    /// What each phone said its hotspot is called since this app started. Only a phone that can read it (Shizuku) says it, and that same
    /// phone can hand the password over, so this is also where "get it from the phone" is offered.
    private(set) var reported: [String: String] = [:]
    /// The phones that were asked for the password of their hotspot, and for which name.
    private(set) var asking: [String: String] = [:]
    /// Why a phone did not hand the password over, until the person tries again.
    private(set) var refusal: [String: String] = [:]

    private init() {
        let names = UserDefaults.standard.dictionary(forKey: Self.namesKey) as? [String: String] ?? [:]
        let passwords = Self.readPasswords()
        var all: [String: HotspotProfile] = [:]
        for id in Set(names.keys).union(passwords.keys) {
            all[id] = HotspotProfile(ssid: names[id] ?? "", password: passwords[id] ?? "")
        }
        profiles = all
        told = UserDefaults.standard.dictionary(forKey: Self.toldKey) as? [String: String] ?? [:]
    }

    // MARK: Reading

    func profile(for id: String) -> HotspotProfile? { profiles[id] }
    func ssid(for id: String) -> String { profiles[id]?.ssid ?? "" }
    func password(for id: String) -> String { profiles[id]?.password ?? "" }
    /// Both are known, so this Mac can join without asking.
    func complete(for id: String) -> Bool { profiles[id]?.complete == true }
    /// The names of every phone's hotspot: none of them is a network this Mac moves to from the hotspot, or sits on by choice.
    var allNames: Set<String> { Set(profiles.values.map(\.ssid).filter { !$0.isEmpty }) }
    /// Whether anything is filled in for any phone: the hotspot is in use, so a new one is worth a word.
    var anySaved: Bool { profiles.values.contains { !$0.isEmpty } }

    func told(_ id: String) -> String? { told[id] }

    // MARK: Writing

    /// A name or a password the person typed, or one that came from the phone. Changing either is a reason to say it again if it does
    /// not work: what was told about before is forgotten.
    func save(ssid: String? = nil, password: String? = nil, for id: String) {
        var profile = profiles[id] ?? HotspotProfile()
        if let ssid { profile.ssid = ssid }
        if let password { profile.password = password }
        guard profiles[id] != profile else { return }
        profiles[id] = profile
        told[id] = nil
        persist()
    }

    func markTold(_ marker: String, for id: String) {
        guard told[id] != marker else { return }
        told[id] = marker
        UserDefaults.standard.set(told, forKey: Self.toldKey)
    }

    /// Joining works with what is saved: what was told about is forgotten, so a later change that breaks it is told again.
    func clearTold(_ id: String) {
        guard told[id] != nil else { return }
        told[id] = nil
        UserDefaults.standard.set(told, forKey: Self.toldKey)
    }

    func hear(_ ssid: String, from id: String) {
        reported[id] = ssid
    }

    func startAsking(_ id: String, for ssid: String) {
        asking[id] = ssid
        refusal[id] = nil
    }

    func stopAsking(_ id: String, refusal reason: String? = nil) {
        asking[id] = nil
        if let reason { refusal[id] = reason }
    }

    /// A phone left the circle for good: what this Mac kept of its hotspot goes with it.
    func forget(_ id: String) {
        guard profiles[id] != nil || told[id] != nil else { return }
        profiles[id] = nil
        told[id] = nil
        reported[id] = nil
        persist()
        UserDefaults.standard.set(told, forKey: Self.toldKey)
    }

    /// Before there was a hotspot for each phone there was one for all. It goes to `phone`, the first one that is there, which is the
    /// one that was asked all along. Called whenever the devices are known, so a start without any phone (still connecting) loses nothing.
    func adoptLegacy(by phone: String) {
        let legacy = HotspotProfile(
            ssid: UserDefaults.standard.string(forKey: Self.legacyNameKey) ?? "",
            password: (try? SecretFile.read(Self.legacyPasswordFile)).flatMap { String(data: $0, encoding: .utf8) } ?? ""
        )
        guard !legacy.isEmpty else { return }
        profiles = HotspotRules.adopt(legacy: legacy, into: profiles, phone: phone)
        persist()
        UserDefaults.standard.removeObject(forKey: Self.legacyNameKey)
        SecretFile.remove(Self.legacyPasswordFile)
    }

    // MARK: Keeping

    private func persist() {
        var names: [String: String] = [:]
        var passwords: [String: String] = [:]
        for (id, profile) in profiles {
            if !profile.ssid.isEmpty { names[id] = profile.ssid }
            if !profile.password.isEmpty { passwords[id] = profile.password }
        }
        UserDefaults.standard.set(names, forKey: Self.namesKey)
        UserDefaults.standard.set(told, forKey: Self.toldKey)
        if passwords.isEmpty {
            SecretFile.remove(Self.passwordsFile)
        } else if let data = try? JSONEncoder().encode(passwords) {
            try? SecretFile.write(data, to: Self.passwordsFile)
        }
    }

    private static func readPasswords() -> [String: String] {
        guard let data = try? SecretFile.read(passwordsFile) else { return [:] }
        return (try? JSONDecoder().decode([String: String].self, from: data)) ?? [:]
    }
}

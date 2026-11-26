import Foundation

/// What this Mac knows of the hotspot of one phone: its name and its password.
public struct HotspotProfile: Equatable, Codable {
    public var ssid: String
    public var password: String

    public init(ssid: String = "", password: String = "") {
        self.ssid = ssid
        self.password = password
    }

    public var isEmpty: Bool { ssid.isEmpty && password.isEmpty }
    /// Both are known, so the Mac can join without asking.
    public var complete: Bool { !ssid.isEmpty && !password.isEmpty }
}

/// The decisions about the hotspot of each phone, without AppKit or the core so they can be tested on their own.
///
/// A new phone has a hotspot of its own, and so does a hotspot that was renamed or given another password. The Mac cannot join what it
/// does not know, so it says so, once for each name: `told` is the name the person was last told about for that phone.
public enum HotspotRules {
    public enum Reason: String, Equatable {
        /// A phone this Mac has no hotspot of yet.
        case new
        /// The phone says its hotspot is called something else now.
        case renamed
        /// The name is known, the password is not.
        case passwordMissing
        /// Joining the hotspot did not work: the name or the password is not what the phone has now.
        case couldNotJoin
    }

    public enum Verdict: Equatable {
        case fine
        case tell(Reason)
    }

    /// The longest a network name can be (802.11). A longer one, or none, is a mistake and is not remembered.
    public static func usableName(_ raw: String) -> String? {
        raw.isEmpty || raw.utf8.count > 32 ? nil : raw
    }

    /// A phone said what its hotspot is called.
    public static func judge(reported ssid: String, stored: HotspotProfile?, told: String?) -> Verdict {
        if let stored, stored.complete, stored.ssid == ssid { return .fine }
        // The person already heard of this name: not again until it changes, or until what they saved stops working.
        if told == ssid { return .fine }
        guard let stored, !stored.ssid.isEmpty else { return .tell(.new) }
        return .tell(stored.ssid == ssid ? .passwordMissing : .renamed)
    }

    /// Joining the hotspot failed in a way that points at the name or the password (not found, or refused), not at the phone or the
    /// network around. What is told about is the name the phone said when it did, and else the name this Mac has: the person who just heard
    /// of a new name is not told again because joining it failed.
    public static func judgeFailure(stored: HotspotProfile?, reported: String? = nil, told: String?) -> Verdict {
        if told == failureMarker(stored: stored, reported: reported) { return .fine }
        guard let stored, !stored.ssid.isEmpty else { return .tell(.new) }
        return .tell(stored.password.isEmpty ? .passwordMissing : .couldNotJoin)
    }

    /// What was marked as told about when a failure was judged: the name the phone said, else the name this Mac has, else nothing for a
    /// phone that was never set up.
    public static func failureMarker(stored: HotspotProfile?, reported: String? = nil) -> String { reported ?? stored?.ssid ?? "" }

    /// A phone that could be asked for its hotspot: in range (over the network or Bluetooth) or not, and with its hotspot filled in on
    /// this Mac or not.
    public struct Candidate: Equatable {
        public var id: String
        public var reachable: Bool
        public var complete: Bool

        public init(id: String, reachable: Bool, complete: Bool) {
            self.id = id
            self.reachable = reachable
            self.complete = complete
        }
    }

    /// The phone to ask next, leaving out the ones `tried` this round. First the phones this Mac can join (hotspot filled in), in range
    /// before out of range: Bluetooth may still find a phone whose beacon was not heard. Then one in range that is not filled in, so a new
    /// phone gets its setup, then the rest. A phone in range went first before, filled in or not: in a train an old phone without mobile
    /// data or a password was asked while the new one lay beside it, and the Mac stayed on "Asking your phone" (2026-11-25). Each phone
    /// that fails hands on to the next, so a new phone beside an old one out of range is still asked, a few seconds later.
    public static func choosePhone(_ phones: [Candidate], except tried: Set<String> = []) -> String? {
        let left = phones.filter { !tried.contains($0.id) }
        return left.first { $0.reachable && $0.complete }?.id
            ?? left.first { $0.complete }?.id
            ?? left.first { $0.reachable }?.id
            ?? left.first?.id
    }

    /// The seconds still to wait before the phone is asked by itself, with `left` of the wait after a start or a wake, in which macOS
    /// joins a network it knows. A look around that found no saved network in range (a street, a train) ends that wait: there is
    /// nothing to join. `savedNearby` is nil when the look could not tell, and then the wait stays. With the lid opened in a train the
    /// phone was asked after 40 seconds, and the person had turned the hotspot on by hand just before (2026-11-26).
    public static func settleWait(left: Double, savedNearby: Bool?) -> Double {
        savedNearby == false ? 0 : max(0, left)
    }

    /// Before there was a hotspot for each phone there was one for all, filled in for the phone that was asked: the first one. It is
    /// handed to `phone` unless that phone has its own already. The caller drops the old one either way.
    public static func adopt(legacy: HotspotProfile, into profiles: [String: HotspotProfile], phone: String) -> [String: HotspotProfile] {
        guard !legacy.isEmpty else { return profiles }
        var result = profiles
        if result[phone]?.isEmpty ?? true { result[phone] = legacy }
        return result
    }
}

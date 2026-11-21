import Foundation
import TandemLogic

/// The word that a phone has a hotspot this Mac cannot join yet: shown as a row in the menu bar panel and as a notification, once for each
/// name. It goes when the hotspot is filled in, or with "Later". It is not a warning that stays: what cannot work says so when it is used.
struct HotspotNotice: Equatable {
    var phone: String
    var phoneName: String
    var reason: HotspotRules.Reason
    /// What the phone said its hotspot is called, when it said.
    var ssid: String?
    /// The phone can hand the password over itself (it said its hotspot, so it reads it).
    var canFetch: Bool

    var title: String {
        switch reason {
        case .new, .renamed: String(localized: "Hotspot of \(phoneName) is new")
        case .passwordMissing: String(localized: "Hotspot of \(phoneName) needs its password")
        case .couldNotJoin: String(localized: "Could not join the hotspot of \(phoneName)")
        }
    }

    /// What is known, for the row in the panel.
    var detail: String {
        switch reason {
        case .new, .renamed: ssid.map { String(localized: "It is called \($0).") } ?? String(localized: "This Mac does not know it yet.")
        case .passwordMissing: String(localized: "This Mac does not have its password.")
        case .couldNotJoin: String(localized: "Its name or password may have changed.")
        }
    }

    /// What a tap on the notification does.
    var body: String {
        let tap: String = switch reason {
        case .couldNotJoin: String(localized: "Tap to set it up again.")
        default: canFetch ? String(localized: "Tap to get the password from your phone.") : String(localized: "Tap to enter the password.")
        }
        return detail + " " + tap
    }
}

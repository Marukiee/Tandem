import Foundation
import TandemCore

/// The tour of what Tandem can do on this Mac, built from the list the other devices get too (`FeatureReport`), so the two never say
/// something different. Each thing that is not ready can be set up now, left for later, or put away as not for this person. What waits
/// for later comes back once, as one notification a few days on, and only while it is still not ready. Nothing stays on a page as a warning.
@MainActor
enum SetupTour {
    /// A new version shows the tour once more after an update, also to whoever has used Tandem for long.
    static let version = 1
    static let notificationCategory = "tandem.tour"

    private static let seenKey = "tourSeen"
    private static let laterKey = "tourLater"
    private static let skipKey = "tourSkip"
    private static let remindKey = "tourRemindAt"
    private static let remindAfter: TimeInterval = 3 * 24 * 60 * 60
    /// Not in the first seconds after the start: the window would come up before the rest of the app.
    private static let started = Date()

    static var later: Set<String> {
        get { Set(UserDefaults.standard.stringArray(forKey: laterKey) ?? []) }
        set { UserDefaults.standard.set(Array(newValue), forKey: laterKey) }
    }

    static var skipped: Set<String> {
        get { Set(UserDefaults.standard.stringArray(forKey: skipKey) ?? []) }
        set { UserDefaults.standard.set(Array(newValue), forKey: skipKey) }
    }

    /// The things of the tour: everything but what never needs anything.
    static func items(_ features: [TandemFeature]) -> [TandemFeature] {
        features.filter { $0.id != "wifi.share" }
    }

    static func putOff(_ id: String) {
        later.insert(id)
        skipped.remove(id)
        if UserDefaults.standard.object(forKey: remindKey) == nil {
            UserDefaults.standard.set(Date().addingTimeInterval(remindAfter), forKey: remindKey)
        }
    }

    static func skip(_ id: String) {
        skipped.insert(id)
        later.remove(id)
    }

    static func undo(_ id: String) {
        skipped.remove(id)
        later.remove(id)
    }

    static func markSeen() {
        UserDefaults.standard.set(version, forKey: seenKey)
    }

    /// Opens the tour once after an update that brings a new one.
    static func showIfNew() {
        guard UserDefaults.standard.integer(forKey: seenKey) < version, Date().timeIntervalSince(started) > 6 else { return }
        markSeen()
        TourWindow.shared.show()
    }

    /// When the day has come and something left for later is still not ready, one notification. What got ready meanwhile is dropped.
    static func remindIfDue(_ features: [TandemFeature]) {
        guard let at = UserDefaults.standard.object(forKey: remindKey) as? Date, Date() >= at else { return }
        UserDefaults.standard.removeObject(forKey: remindKey)
        let waiting = features.filter { !$0.ready && later.contains($0.id) }
        later = Set(waiting.map(\.id))
        guard !waiting.isEmpty else { return }
        Notifier.shared.post(
            id: "tour.remind",
            title: String(localized: "Tandem: still to set up"),
            body: waiting.count == 1 ? waiting[0].name : String(localized: "\(waiting.count) things are still waiting: \(waiting.map(\.name).joined(separator: ", "))"),
            category: notificationCategory
        )
    }
}

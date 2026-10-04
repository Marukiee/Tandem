import AppKit
import TandemCore
import UserNotifications

/// Native notifications: received files, mirrored phone notifications and calls.
@MainActor
final class Notifier: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notifier()

    /// Set by the model: what to do when a button on a notification is pressed.
    var onFileAction: ((String) -> Void)?
    var onCallAction: ((_ device: String, _ callId: String, _ action: TandemCallAction) -> Void)?
    var onMirrorAction: ((_ device: String, _ key: String, _ button: String, _ reply: String?, _ dismiss: Bool) -> Void)?
    var onCopyCode: ((String) -> Void)?

    private var dynamicCategories: [String: UNNotificationCategory] = [:]
    /// The categories made for notifications with a code, oldest first. The title of the button holds the code, so each
    /// notification needs a category of its own, and only the latest ones are kept.
    private var codeCategoryIDs: [String] = []
    private static let codeCategoriesKept = 20
    private var authorised = false

    private static let fileCategory = "tandem.file"
    private static let callCategory = "tandem.call"
    private static let copyCodeAction = "copycode"

    func setUp() {
        guard Bundle.main.bundleIdentifier != nil else { return }
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        center.setNotificationCategories(baseCategories())
        center.requestAuthorization(options: [.alert, .sound, .badge]) { [weak self] granted, _ in
            Task { @MainActor in self?.authorised = granted }
        }
    }

    private func baseCategories() -> Set<UNNotificationCategory> {
        let reveal = UNNotificationAction(identifier: "reveal", title: String(localized: "Show in Finder"), options: [.foreground])
        let file = UNNotificationCategory(identifier: Self.fileCategory, actions: [reveal], intentIdentifiers: [])

        let answer = UNNotificationAction(identifier: "answer", title: String(localized: "Answer"), options: [])
        let reject = UNNotificationAction(identifier: "reject", title: String(localized: "Decline"), options: [.destructive])
        let call = UNNotificationCategory(identifier: Self.callCategory, actions: [answer, reject], intentIdentifiers: [])
        return Set([file, call]).union(dynamicCategories.values)
    }

    func post(
        id: String,
        title: String,
        body: String,
        subtitle: String? = nil,
        category: String? = nil,
        userInfo: [String: String] = [:],
        sound: Bool = false,
        thread: String? = nil
    ) {
        guard Bundle.main.bundleIdentifier != nil else { return }
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        if let subtitle { content.subtitle = subtitle }
        if let category { content.categoryIdentifier = category }
        if let thread { content.threadIdentifier = thread }
        content.userInfo = userInfo
        if sound { content.sound = .default }
        content.interruptionLevel = category == Self.callCategory ? .timeSensitive : .active
        let request = UNNotificationRequest(identifier: id, content: content, trigger: nil)
        UNUserNotificationCenter.current().add(request)
    }

    func remove(id: String) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id])
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id])
    }

    func postFileReceived(name: String, from device: String, location: String) {
        post(
            id: "file.\(location)",
            title: String(localized: "Received \(name)"),
            body: String(localized: "From \(device)"),
            category: Self.fileCategory,
            userInfo: ["path": location],
            thread: "files"
        )
    }

    func postCall(device: String, id: String, name: String?, number: String?, incoming: Bool) {
        let who = name ?? number ?? String(localized: "Unknown number")
        post(
            id: "call.\(id)",
            title: incoming ? String(localized: "Incoming call") : String(localized: "Calling"),
            body: who,
            subtitle: device,
            category: Self.callCategory,
            userInfo: ["device": device, "call": id],
            sound: incoming,
            thread: "calls"
        )
    }

    func postMirrored(device: String, deviceName: String, notification: TandemNotification, code: String? = nil) {
        var category: String?
        var actions: [UNNotificationAction] = []
        if let code {
            actions.append(UNNotificationAction(
                identifier: Self.copyCodeAction,
                title: String(localized: "Copy code \(code)"),
                options: [],
                icon: UNNotificationActionIcon(systemImageName: "doc.on.doc")
            ))
        }
        actions += notification.buttons.prefix(4).map { button in
            if button.isReply {
                return UNTextInputNotificationAction(
                    identifier: "btn." + button.id,
                    title: button.title,
                    options: [],
                    textInputButtonTitle: String(localized: "Send"),
                    textInputPlaceholder: button.title
                )
            }
            return UNNotificationAction(identifier: "btn." + button.id, title: button.title, options: [])
        }
        if !actions.isEmpty {
            if code != nil {
                let id = "tandem.mirror.code.\(device).\(notification.key)"
                dynamicCategories[id] = UNNotificationCategory(identifier: id, actions: actions, intentIdentifiers: [])
                codeCategoryIDs.removeAll { $0 == id }
                codeCategoryIDs.append(id)
                while codeCategoryIDs.count > Self.codeCategoriesKept {
                    dynamicCategories.removeValue(forKey: codeCategoryIDs.removeFirst())
                }
                category = id
            } else {
                let id = "tandem.mirror." + notification.appId
                dynamicCategories[id] = UNNotificationCategory(identifier: id, actions: actions, intentIdentifiers: [])
                category = id
            }
            UNUserNotificationCenter.current().setNotificationCategories(baseCategories())
        }
        var info = ["device": device, "key": notification.key]
        // The code travels with the notification so the button still works after the app has been restarted.
        if let code { info["code"] = code }
        post(
            id: "mirror.\(device).\(notification.key)",
            title: notification.title.isEmpty ? notification.appName : notification.title,
            body: notification.text,
            subtitle: notification.appName + " · " + deviceName,
            category: category,
            userInfo: info,
            sound: !notification.silent,
            thread: notification.appId
        )
    }

    // MARK: UNUserNotificationCenterDelegate

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification
    ) async -> UNNotificationPresentationOptions {
        [.banner, .list, .sound]
    }

    nonisolated func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse
    ) async {
        let info = response.notification.request.content.userInfo as? [String: String] ?? [:]
        let action = response.actionIdentifier
        let reply = (response as? UNTextInputNotificationResponse)?.userText
        await MainActor.run {
            switch response.notification.request.content.categoryIdentifier {
            case Self.fileCategory:
                if let path = info["path"] { onFileAction?(path) }
            case Self.callCategory:
                guard let device = info["device"], let call = info["call"] else { return }
                if action == "answer" { onCallAction?(device, call, .answer) }
                if action == "reject" { onCallAction?(device, call, .reject) }
            default:
                guard let device = info["device"], let key = info["key"] else { return }
                if action == Self.copyCodeAction {
                    if let code = info["code"] { onCopyCode?(code) }
                } else if action == UNNotificationDismissActionIdentifier {
                    onMirrorAction?(device, key, "", nil, true)
                } else if action.hasPrefix("btn.") {
                    onMirrorAction?(device, key, String(action.dropFirst(4)), reply, false)
                } else if action == UNNotificationDefaultActionIdentifier {
                    onMirrorAction?(device, key, "", nil, false)
                }
            }
        }
    }
}

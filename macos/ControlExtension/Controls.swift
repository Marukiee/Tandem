import AppIntents
import AppKit
import SwiftUI
import WidgetKit

/// What the app says is on, from the file it writes (see `ControlBridge` in the app). Keep in step with it.
struct ControlState: Codable {
    var quickShare = false
    var mouse = false
    var extending = false
    var ringing = false
    var extendTarget: String?
    var phone: String?

    static func read() -> ControlState {
        // The sandbox of the extension has its own home; the one of the person is what the app writes in.
        let home = String(cString: getpwuid(getuid()).pointee.pw_dir)
        let folder = Bundle.main.object(forInfoDictionaryKey: "TandemSupportDir") as? String ?? "Tandem"
        let url = URL(fileURLWithPath: home).appendingPathComponent("Library/Application Support/\(folder)/control-state.json")
        guard let data = try? Data(contentsOf: url), let state = try? JSONDecoder().decode(ControlState.self, from: data) else { return ControlState() }
        return state
    }
}

/// A press goes to the app as an address; the app does it. The app is not brought to the front.
func tell(_ action: String, _ value: Bool) async {
    var parts = URLComponents()
    parts.scheme = "tandem"
    parts.host = "control"
    parts.queryItems = [URLQueryItem(name: "action", value: action), URLQueryItem(name: "value", value: value ? "true" : "false")]
    guard let url = parts.url else { return }
    let configuration = NSWorkspace.OpenConfiguration()
    configuration.activates = false
    _ = try? await NSWorkspace.shared.open(url, configuration: configuration)
}

struct QuickShareIntent: SetValueIntent {
    static let title: LocalizedStringResource = "Quick Share"
    @Parameter(title: "On") var value: Bool
    func perform() async throws -> some IntentResult { await tell("quickshare", value); return .result() }
}

struct MouseIntent: SetValueIntent {
    static let title: LocalizedStringResource = "Shared mouse"
    @Parameter(title: "On") var value: Bool
    func perform() async throws -> some IntentResult { await tell("mouse", value); return .result() }
}

struct ExtendIntent: SetValueIntent {
    static let title: LocalizedStringResource = "Extend screen"
    @Parameter(title: "On") var value: Bool
    func perform() async throws -> some IntentResult { await tell("extend", value); return .result() }
}

struct FindIntent: SetValueIntent {
    static let title: LocalizedStringResource = "Find phone"
    @Parameter(title: "On") var value: Bool
    func perform() async throws -> some IntentResult { await tell("find", value); return .result() }
}

struct StateProvider: ControlValueProvider {
    let pick: @Sendable (ControlState) -> Bool
    var previewValue: Bool { false }
    func currentValue() async throws -> Bool { pick(ControlState.read()) }
}

struct QuickShareControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "nl.markmaaktmedia.Tandem.control.quickshare", provider: StateProvider(pick: { $0.quickShare })) { on in
            ControlWidgetToggle("Quick Share", isOn: on, action: QuickShareIntent()) { isOn in
                Label(isOn ? "Everyone" : "Off", systemImage: "dot.radiowaves.left.and.right")
            }
            .tint(Color(red: 0.36, green: 0.36, blue: 0.84))
        }
        .displayName("Quick Share")
        .description("Lets this Mac be found for Quick Share.")
    }
}

struct MouseControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "nl.markmaaktmedia.Tandem.control.mouse", provider: StateProvider(pick: { $0.mouse })) { on in
            ControlWidgetToggle("Shared mouse", isOn: on, action: MouseIntent()) { isOn in
                Label(isOn ? "On" : "Off", systemImage: "cursorarrow.motionlines")
            }
            .tint(Color(red: 0.36, green: 0.36, blue: 0.84))
        }
        .displayName("Shared mouse")
        .description("Lets the mouse and keyboard of this Mac go to your other computers.")
    }
}

struct ExtendControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "nl.markmaaktmedia.Tandem.control.extend", provider: StateProvider(pick: { $0.extending })) { on in
            ControlWidgetToggle("Extend screen", isOn: on, action: ExtendIntent()) { isOn in
                Label(isOn ? "On" : "Off", systemImage: "rectangle.on.rectangle")
            }
            .tint(Color(red: 0.36, green: 0.36, blue: 0.84))
        }
        .displayName("Extend screen")
        .description("Shows a screen of this Mac on your phone or another computer.")
    }
}

struct FindControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "nl.markmaaktmedia.Tandem.control.find", provider: StateProvider(pick: { $0.ringing })) { on in
            ControlWidgetToggle("Find phone", isOn: on, action: FindIntent()) { isOn in
                Label(isOn ? "Ringing" : "Off", systemImage: "bell.and.waves.left.and.right")
            }
            .tint(Color(red: 0.36, green: 0.36, blue: 0.84))
        }
        .displayName("Find phone")
        .description("Makes your phone ring.")
    }
}

@main
struct TandemControls: WidgetBundle {
    var body: some Widget {
        QuickShareControl()
        ExtendControl()
        MouseControl()
        FindControl()
    }
}

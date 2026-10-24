import Foundation
import TandemCore
import WidgetKit

/// The buttons of Tandem in the Control Center (the extension in `macos/ControlExtension`). The extension is sandboxed and a separate program: it
/// reads what is on from a small file this writes, and does what a press asks by opening a `tandem://control` address, which comes here.
@MainActor
final class ControlBridge {
    static let shared = ControlBridge()

    /// What the buttons show. Keep in step with `ControlState` in the extension.
    struct State: Codable, Equatable {
        var quickShare = false
        var mouse = false
        var extending = false
        var ringing = false
        /// Whether there is a device to extend to, and a phone to find, so a button can say it has nothing to act on.
        var extendTarget: String?
        var phone: String?
    }

    private var timer: Timer?
    private var last: State?

    static var stateFile: URL { EngineModel.supportDirectory().appendingPathComponent("control-state.json") }

    func start() {
        guard timer == nil else { return }
        publish()
        timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.publish() }
        }
    }

    private var extendDevice: TandemDevice? {
        let model = EngineModel.shared
        let remembered = UserDefaults.standard.string(forKey: "controlExtendTarget")
        let able = model.devices.filter { $0.online && $0.caps.contains("screen.extend.view") }
        return able.first { $0.id == remembered } ?? able.first
    }

    private var phone: TandemDevice? {
        EngineModel.shared.devices.first { $0.platform == .android && $0.online }
    }

    private func current() -> State {
        State(
            quickShare: QuickShare.shared.enabled,
            mouse: PointerShare.shared.enabled,
            extending: ScreenHost.shared.current?.extend == true,
            ringing: EngineModel.shared.ringing.contains { $0 == phone?.id },
            extendTarget: extendDevice?.name,
            phone: phone?.name
        )
    }

    func publish() {
        let now = current()
        guard now != last else { return }
        last = now
        if let data = try? JSONEncoder().encode(now) { try? data.write(to: Self.stateFile, options: .atomic) }
        ControlCenter.shared.reloadAllControls()
    }

    /// A press: `tandem://control?action=quickshare|mouse|extend|find&value=true|false`.
    func perform(_ url: URL) {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let action = items.first { $0.name == "action" }?.value ?? ""
        let on = items.first { $0.name == "value" }?.value == "true"
        switch action {
        case "quickshare":
            QuickShare.shared.enabled = on
        case "mouse":
            PointerShare.shared.enabled = on
        case "extend":
            if on, let device = extendDevice {
                UserDefaults.standard.set(device.id, forKey: "controlExtendTarget")
                ScreenHost.shared.offerExtend(to: device)
            } else if !on {
                ScreenHost.shared.stopCurrent()
            }
        case "find":
            if let phone { EngineModel.shared.ring(phone.id, on: on) }
        default:
            break
        }
        publish()
    }
}

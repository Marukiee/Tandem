import Foundation
import TandemCore

enum HotspotStatus: Equatable {
    case idle
    case asking
    case starting
    case joining(String)
    case connected(String)
    case failed(String)

    var isBusy: Bool {
        switch self {
        case .asking, .starting, .joining: true
        default: false
        }
    }

    var text: String {
        switch self {
        case .idle: ""
        case .asking: String(localized: "Asking your phone")
        case .starting: String(localized: "Your phone is turning on its hotspot")
        case let .joining(name): String(localized: "Joining \(name)")
        case let .connected(name): String(localized: "Using the hotspot of \(name)")
        case let .failed(reason): reason
        }
    }
}

/// Turns the phone's hotspot on when the Mac has no connection, and reports it.
///
/// When the Mac is offline it cannot reach the phone over the network, so the
/// request goes over Bluetooth Low Energy. When it can still reach the phone
/// (both on the same network, say) the request goes over the normal connection.
@MainActor
final class HotspotCoordinator {
    private weak var model: EngineModel?
    private var offlineTask: Task<Void, Never>?
    private var activeDevice: String?

    static let enabledKey = "autoHotspot"
    static let delayKey = "hotspotDelay"

    var autoEnabled: Bool { UserDefaults.standard.bool(forKey: Self.enabledKey) }

    func attach(model: EngineModel) {
        self.model = model
    }

    /// Called whenever the network changes. Waits a few seconds before acting, because
    /// a Mac that lost Wi-Fi often gets it back on its own.
    func networkChanged(online: Bool) {
        offlineTask?.cancel()
        guard let model else { return }

        if online {
            if case .connected = model.hotspotStatus, !model.networkUsesHotspot {
                // Back on a normal network: the hotspot is no longer needed.
                if let id = activeDevice { stop(id) }
            }
            return
        }
        guard autoEnabled else { return }
        let delay = UserDefaults.standard.object(forKey: Self.delayKey) as? Double ?? 8
        offlineTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard !Task.isCancelled, let self, let model = self.model, !model.isOnline else { return }
            if let target = model.devices.first(where: { $0.platform == .android }) {
                self.requestNow(target.id)
            }
        }
    }

    func deviceConnected(_ id: String) {}

    func requestNow(_ id: String) {
        guard let model, let engine = model.engineHandle else { return }
        activeDevice = id
        model.hotspotStatus = .asking
        Task {
            do {
                try await engine.sendHotspot(target: id, hotspot: .request(reason: "mac-offline"))
            } catch {
                model.hotspotStatus = .failed(String(localized: "Could not reach your phone"))
            }
        }
    }

    func stop(_ id: String) {
        guard let model, let engine = model.engineHandle else { return }
        model.hotspotStatus = .idle
        Task { try? await engine.sendHotspot(target: id, hotspot: .stop) }
    }

    func handle(from id: String, message: TandemHotspot) {
        guard let model else { return }
        let name = model.device(id)?.name ?? "Phone"
        switch message {
        case let .state(on, ssid, _, _, error):
            if let error {
                model.hotspotStatus = .failed(error)
            } else if on {
                model.hotspotStatus = ssid.map { .joining($0) } ?? .starting
            } else {
                model.hotspotStatus = .idle
            }
            _ = name
        case .request, .stop:
            break
        }
    }
}

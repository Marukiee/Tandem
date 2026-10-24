import SwiftUI
import TandemCore

/// What works on each device and what does not, and why: a permission or a setting that is off. A row that needs something has a button
/// that opens the page where it is turned on, here or on that device (which shows it on its own screen).
struct FeaturesSettings: View {
    @Environment(EngineModel.self) private var model
    @LocalState private var mine: [TandemFeature] = []

    private var others: [TandemDevice] {
        model.devices.filter { $0.id != model.myId }.sorted { ($0.online ? 0 : 1, $0.name) < ($1.online ? 0 : 1, $1.name) }
    }

    private var missing: Int {
        mine.filter { !$0.ready }.count + others.filter(\.online).flatMap(\.features).filter { !$0.ready }.count
    }

    var body: some View {
        Form {
            Section {
                Label {
                    Text(missing == 0
                        ? String(localized: "Everything works on your devices.")
                        : String(localized: "\(missing) things need a permission or a setting. Each one says what, and opens the right place."))
                        .font(.callout)
                } icon: {
                    Image(systemName: missing == 0 ? "checkmark.seal.fill" : "exclamationmark.triangle.fill")
                        .foregroundStyle(missing == 0 ? .green : .orange)
                }
            }
            Section {
                ForEach(ordered(mine), id: \.id) { feature in
                    FeatureRow(feature: feature, action: String(localized: "Open Settings")) { FeatureReport.open($0) }
                }
            } header: {
                DeviceHeading(name: model.myName, symbol: "laptopcomputer", online: true)
            }
            ForEach(others, id: \.id) { device in
                Section {
                    if device.features.isEmpty {
                        Text(device.online
                            ? String(localized: "Update Tandem on \(device.name) to see what works there.")
                            : String(localized: "Not connected. What works there shows up when it connects."))
                            .font(.callout)
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(ordered(device.features), id: \.id) { feature in
                            FeatureRow(feature: feature, action: String(localized: "Open on \(device.name)"), enabled: device.online || device.ble) {
                                model.openSettings(on: device.id, page: $0)
                            }
                        }
                    }
                } header: {
                    DeviceHeading(name: device.name, symbol: Self.symbol(device.platform), online: device.online)
                }
            }
        }
        .formStyle(.grouped)
        .animation(.tandem, value: mine.map(\.ready))
        .task {
            // Permissions change in System Settings, so this Mac's own list is looked at again while the page is open.
            while !Task.isCancelled {
                mine = await FeatureReport.current()
                try? await Task.sleep(for: .seconds(1.5))
            }
        }
    }

    /// What needs something first, then what works.
    private func ordered(_ features: [TandemFeature]) -> [TandemFeature] {
        features.filter { !$0.ready } + features.filter(\.ready)
    }

    static func symbol(_ platform: TandemPlatform) -> String {
        switch platform {
        case .android, .ios: "iphone"
        case .macOs: "laptopcomputer"
        case .windows, .linux: "pc"
        case .other: "desktopcomputer"
        }
    }
}

private struct DeviceHeading: View {
    let name: String
    let symbol: String
    let online: Bool

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: symbol)
            Text(name)
            Spacer(minLength: 8)
            if !online {
                Text("Offline").foregroundStyle(.secondary)
            }
        }
    }
}

/// One feature: a green tick when it works, or what is missing and a button to the place where it is turned on.
struct FeatureRow: View {
    let feature: TandemFeature
    let action: String
    var enabled = true
    let open: (String) -> Void

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: feature.ready ? "checkmark.circle.fill" : "exclamationmark.circle.fill")
                .foregroundStyle(feature.ready ? .green : .orange)
            VStack(alignment: .leading, spacing: 2) {
                Text(feature.name).font(.callout.weight(feature.ready ? .regular : .semibold))
                if !feature.ready, !feature.why.isEmpty {
                    Text(feature.why).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: 8)
            if !feature.ready, let page = feature.page {
                Button(action) { open(page) }
                    .buttonStyle(.glassProminent)
                    .tint(Palette.indigo)
                    .controlSize(.small)
                    .disabled(!enabled)
            }
        }
        .padding(.vertical, 2)
    }
}

/// On a device's page: what does not work there now, and the button that opens the setting on that device. Nothing when all works.
struct FeatureNotice: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice

    var body: some View {
        let missing = device.online ? device.features.filter { !$0.ready } : []
        if !missing.isEmpty {
            Card(radius: Metrics.card, padding: 14) {
                VStack(alignment: .leading, spacing: 10) {
                    Label("Not everything works on \(device.name)", systemImage: "exclamationmark.triangle.fill")
                        .font(.callout.weight(.semibold))
                        .foregroundStyle(.orange)
                    ForEach(missing, id: \.id) { feature in
                        FeatureRow(feature: feature, action: String(localized: "Open on \(device.name)")) {
                            model.openSettings(on: device.id, page: $0)
                        }
                    }
                }
            }
            .transition(.opacity.combined(with: .move(edge: .top)))
        }
    }
}

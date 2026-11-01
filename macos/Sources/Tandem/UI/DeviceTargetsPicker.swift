import SwiftUI
import TandemCore

/// Which devices a settings page changes. Nothing chosen is all devices; any number of devices can be chosen and changed together.
struct DeviceTargetsPicker: View {
    let devices: [TandemDevice]
    @Binding var selection: Set<String>

    var body: some View {
        LabeledContent("For") {
            Menu {
                Toggle("All devices", isOn: Binding(get: { selection.isEmpty }, set: { if $0 { selection = [] } }))
                Divider()
                ForEach(devices, id: \.id) { device in
                    Toggle(device.name, isOn: Binding(
                        get: { selection.contains(device.id) },
                        set: { chosen in
                            if chosen { selection.insert(device.id) } else { selection.remove(device.id) }
                        }
                    ))
                }
            } label: {
                Text(summary)
            }
            .fixedSize()
        }
    }

    private var summary: String {
        let chosen = devices.filter { selection.contains($0.id) }
        switch chosen.count {
        case 0: return String(localized: "All devices")
        case 1: return chosen[0].name
        case 2: return "\(chosen[0].name), \(chosen[1].name)"
        default: return String(localized: "\(chosen.count) devices")
        }
    }
}

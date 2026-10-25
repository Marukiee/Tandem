import Foundation
import SwiftUI

// The battery widget, drawn the same in the app (for a debug picture) and in the WidgetKit extension in `macos/ControlExtension`, which
// compiles this very file. So nothing here may reach for the rest of the app: no TandemCore, no Palette, no animations of the app.

/// One device in the widget, as the app writes it for the extension.
struct BatteryWidgetDevice: Codable, Equatable, Identifiable {
    var id: String
    var name: String
    /// An SF Symbol.
    var symbol: String
    var level: Int
    var charging: Bool
    /// Not connected now: the last level that was heard, shown dimmed.
    var away: Bool
}

/// What the app writes to `battery-widget.json` in its support folder, and the extension reads.
struct BatteryWidgetSnapshot: Codable, Equatable {
    var devices: [BatteryWidgetDevice] = []

    static let fileName = "battery-widget.json"
    static let kind = "nl.markmaaktmedia.Tandem.batteries"

    /// Made up devices, for the widget gallery and the debug picture: every colour, charging and away.
    static let sample = BatteryWidgetSnapshot(devices: [
        BatteryWidgetDevice(id: "mac", name: "MacBook Pro", symbol: "laptopcomputer", level: 82, charging: true, away: false),
        BatteryWidgetDevice(id: "phone", name: "Xperia 1 VI", symbol: "iphone", level: 15, charging: false, away: false),
        BatteryWidgetDevice(id: "linux", name: "ThinkPad", symbol: "desktopcomputer", level: 7, charging: false, away: false),
        BatteryWidgetDevice(id: "tablet", name: "Tablet", symbol: "ipad", level: 64, charging: false, away: true),
        BatteryWidgetDevice(id: "pc", name: "Surface", symbol: "pc", level: 100, charging: false, away: false),
        BatteryWidgetDevice(id: "watch", name: "Watch", symbol: "applewatch", level: 41, charging: true, away: false),
    ])
}

enum BatteryWidgetSize {
    case small, medium, large

    var capacity: Int {
        switch self {
        case .small, .medium: 4
        case .large: 6
        }
    }
}

/// Apple's own language, as in the system's Batteries widget: the system background of a widget, thin rings, the system green, yellow and
/// red (which differ a little between light and dark, as Apple makes them), SF Symbols and SF Pro Rounded numbers. Android and the tray panel
/// of Linux and Windows draw the same rings in their own languages.
enum BatteryWidgetStyle {
    static let track = Color.primary.opacity(0.12)
    static let awayOpacity = 0.4

    /// A battery on the charger is green whatever its level, as on iOS: it is getting better.
    static func tint(level: Int, charging: Bool) -> Color {
        if charging { return .green }
        if level < 10 { return .red }
        if level < 20 { return .yellow }
        return .green
    }

    /// Anything below 100 leaves an opening, so 97 does not look like a closed ring with a flaw.
    static func arc(_ level: Int) -> CGFloat {
        level >= 100 ? 1 : CGFloat(max(0, level)) / 100 * 0.945
    }

/// The extension has no translations of its own: the few words it shows are chosen here.
    static func text(_ english: String, _ dutch: String) -> String {
        (Locale.preferredLanguages.first ?? "en").hasPrefix("nl") ? dutch : english
    }
}

struct BatteryWidgetRing: View {
    let device: BatteryWidgetDevice
    let diameter: CGFloat

    var body: some View {
        let line = max(2.5, diameter * 0.075)
        ZStack {
            Circle().stroke(BatteryWidgetStyle.track, lineWidth: line)
            Circle()
                .trim(from: 0, to: BatteryWidgetStyle.arc(device.level))
                .stroke(BatteryWidgetStyle.tint(level: device.level, charging: device.charging), style: StrokeStyle(lineWidth: line, lineCap: .round))
                .rotationEffect(.degrees(-90))
            // Green on the charger, as the system's own widget shows it.
            Image(systemName: device.symbol)
                .font(.system(size: diameter * 0.36, weight: .medium))
                .foregroundStyle(device.charging ? Color.green : Color.primary)
        }
        .padding(line / 2)
        .frame(width: diameter, height: diameter)
        // WidgetKit plays these between two versions of the widget: the ring fills or empties, and the green comes and goes softly.
        .animation(.smooth(duration: 0.7), value: device.level)
        .animation(.smooth(duration: 0.5), value: device.charging)
    }
}

/// The ring with the percentage under it, and for the large size the name under that.
struct BatteryWidgetItem: View {
    let device: BatteryWidgetDevice
    let diameter: CGFloat
    var showsName = false

    var body: some View {
        VStack(spacing: diameter * 0.1) {
            BatteryWidgetRing(device: device, diameter: diameter)
            HStack(spacing: 1) {
                if device.charging {
                    Image(systemName: "bolt.fill").font(.system(size: diameter * 0.2, weight: .bold)).foregroundStyle(.green)
                        .transition(.scale.combined(with: .opacity))
                }
                Text("\(device.level)%")
                    .font(.system(size: max(11, diameter * 0.24), weight: .semibold, design: .rounded).monospacedDigit())
                    .foregroundStyle(.primary)
                    .contentTransition(.numericText(value: Double(device.level)))
            }
            .lineLimit(1)
            .animation(.smooth(duration: 0.5), value: device.charging)
            .animation(.smooth(duration: 0.7), value: device.level)
            if showsName {
                Text(device.name)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
        }
        .opacity(device.away ? BatteryWidgetStyle.awayOpacity : 1)
        .frame(maxWidth: .infinity)
    }
}

/// The content of the widget. The caller gives it the background (the system's, with `containerBackground` in the extension) and the size.
struct BatteryWidgetView: View {
    let devices: [BatteryWidgetDevice]
    let size: BatteryWidgetSize

    var body: some View {
        let shown = Array(devices.prefix(size.capacity))
        Group {
            if shown.isEmpty {
                VStack(spacing: 6) {
                    Image(systemName: "battery.100percent").font(.system(size: 26, weight: .semibold)).foregroundStyle(.secondary)
                    Text(BatteryWidgetStyle.text("No batteries yet", "Nog geen batterijen"))
                        .font(.system(size: 12, weight: .medium))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            } else {
                switch size {
                case .small: small(shown)
                case .medium: row(shown, diameter: 58)
                case .large: grid(shown)
                }
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // One device fills the widget, a few become rows across it with their names, so nothing is empty card. The same as on Android.

    @ViewBuilder
    private func small(_ shown: [BatteryWidgetDevice]) -> some View {
        switch shown.count {
        case 1: BatteryWidgetItem(device: shown[0], diameter: 92, showsName: true)
        default: BatteryWidgetRows(devices: shown)
        }
    }

    private func row(_ shown: [BatteryWidgetDevice], diameter: CGFloat) -> some View {
        HStack(spacing: 6) { ForEach(shown) { BatteryWidgetItem(device: $0, diameter: diameter) } }
    }

    @ViewBuilder
    private func grid(_ shown: [BatteryWidgetDevice]) -> some View {
        switch shown.count {
        case 1: BatteryWidgetItem(device: shown[0], diameter: 170, showsName: true)
        // Two or three rows would each be a tall empty pill here: big rings side by side fill it better.
        case 2, 3: grid(shown, columns: shown.count, diameter: 96, spacing: 20, names: true)
        case 4: BatteryWidgetRows(devices: shown)
        default: grid(shown, columns: 3, diameter: 80, spacing: 22, names: true)
        }
    }

    private func grid(_ shown: [BatteryWidgetDevice], columns: Int, diameter: CGFloat, spacing: CGFloat, names: Bool) -> some View {
        let rows = stride(from: 0, to: shown.count, by: columns).map { Array(shown[$0 ..< min($0 + columns, shown.count)]) }
        return VStack(spacing: spacing) {
            ForEach(rows.indices, id: \.self) { index in
                HStack(spacing: 8) {
                    ForEach(rows[index]) { BatteryWidgetItem(device: $0, diameter: diameter, showsName: names) }
                    // A short last row keeps the columns of the rows above it.
                    ForEach(0 ..< (columns - rows[index].count), id: \.self) { _ in Color.clear.frame(maxWidth: .infinity, maxHeight: 1) }
                }
            }
        }
    }
}

/// Each device a row across the widget, the rows sharing its height: a pill with the ring at its start, the level and under it the name.
struct BatteryWidgetRows: View {
    let devices: [BatteryWidgetDevice]
    private let gap: CGFloat = 4

    var body: some View {
        GeometryReader { proxy in
            let height = (proxy.size.height - gap * CGFloat(devices.count - 1)) / CGFloat(devices.count)
            let inset = min(8, max(4, height * 0.12))
            let diameter = min(height - 2 * inset, proxy.size.width * 0.28, 64)
            VStack(spacing: gap) {
                ForEach(devices) { device in
                    HStack(spacing: 10) {
                        BatteryWidgetRing(device: device, diameter: diameter)
                        VStack(alignment: .leading, spacing: 0) {
                            HStack(spacing: 1) {
                                if device.charging {
                                    Image(systemName: "bolt.fill").font(.system(size: min(20, max(12, height * 0.24)), weight: .bold)).foregroundStyle(.green)
                                }
                                Text("\(device.level)%")
                                    .font(.system(size: min(24, max(14, height * 0.3)), weight: .semibold, design: .rounded).monospacedDigit())
                                    .contentTransition(.numericText(value: Double(device.level)))
                            }
                            Text(device.name)
                                .font(.system(size: min(14, max(11, height * 0.2)), weight: .medium))
                                .foregroundStyle(.secondary)
                                .truncationMode(.tail)
                        }
                        .lineLimit(1)
                        Spacer(minLength: 0)
                    }
                    .padding(.leading, inset)
                    .padding(.trailing, 10)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.primary.opacity(0.06), in: .rect(cornerRadius: min(height / 2, 18)))
                    .opacity(device.away ? BatteryWidgetStyle.awayOpacity : 1)
                    .animation(.smooth(duration: 0.7), value: device.level)
                    .animation(.smooth(duration: 0.5), value: device.charging)
                }
            }
        }
    }
}

import AppKit
import SwiftUI
import TandemCore

/// The panel under the menu bar icon. The panel itself is the glass; what sits in it
/// is content (the device rows, on a plain hover highlight) and controls, which are
/// fills on that glass and not glass of their own (see `onGlass`).
struct MenuBarPanel: View {
    @Environment(EngineModel.self) private var model
    @Environment(\.openWindow) private var openWindow
    @Environment(\.openSettings) private var openSettings

    var body: some View {
        VStack(spacing: 10) {
            header

            if model.devices.isEmpty {
                empty
            } else {
                devices
            }

            MenuNowPlaying()

            MenuTiles()

            quickShare

            UpdateBanner(compact: true)
        }
        .padding(14)
        .frame(width: 344)
        .onGlassSurface()
        .animation(.tandem, value: model.devices.map(\.id))
        .animation(.tandem, value: model.devices.map(\.online))
        .animation(.tandem, value: model.hotspotStatus)
        .animation(.tandem, value: model.isTransferring)
        .animation(.tandem, value: model.remoteMedia.mapValues(\.players))
    }

    /// All devices on one card: the ones that can be reached with their buttons, the hotspot in use, and the rest on a line each.
    private var devices: some View {
        let reachable = model.devices.filter { model.reach(of: $0).reachable }
        let away = model.devices.filter { !model.reach(of: $0).reachable }
        // No glass container here: the buttons in a row sit close together, and in a container glass that close blends into one
        // blob and drags the icons off centre as soon as one of them swells under the pointer.
        return VStack(spacing: 2) {
            ForEach(reachable, id: \.id) { device in
                MenuDeviceRow(device: device)
            }
            if model.hotspotStatus != .idle {
                MenuHotspot()
                    .padding(.horizontal, 4)
                    .padding(.bottom, 4)
            }
            if !reachable.isEmpty && !away.isEmpty {
                Divider().padding(.horizontal, 10).padding(.vertical, 4)
            }
            ForEach(away, id: \.id) { device in
                MenuAwayRow(device: device)
            }
        }
        .padding(4)
        .background(Color.primary.opacity(0.05), in: .rect(cornerRadius: 22, style: .continuous))
    }

    /// Quick Share on a card of its own, with what is being sent from this Mac under it.
    private var quickShare: some View {
        VStack(alignment: .leading, spacing: 4) {
            MenuQuickShare()
            if let item = model.transfers.first(where: { $0.state == .active }) {
                VStack(alignment: .leading, spacing: 5) {
                    HStack {
                        Text(item.name).font(.caption.weight(.medium)).lineLimit(1).truncationMode(.middle)
                        Spacer()
                        Text(formatSpeed(item.speed)).font(.caption).foregroundStyle(.secondary)
                    }
                    ProgressCapsule(fraction: item.fraction).frame(height: 5)
                }
                .padding(.horizontal, 10)
                .padding(.bottom, 8)
                .transition(.opacity.combined(with: .move(edge: .top)))
            }
        }
        .padding(4)
        .background(Color.primary.opacity(0.05), in: .rect(cornerRadius: 22, style: .continuous))
    }

    // MARK: Header

    /// The name and how many devices are there, with the extras and the way into the app at the end: no row of its own at the bottom.
    private var header: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 1) {
                Text(AppIdentity.displayName).font(.headline)
                if !model.devices.isEmpty {
                    HStack(spacing: 5) {
                        Circle()
                            .fill(model.onlineCount > 0 ? Color.green : Color.secondary.opacity(0.45))
                            .frame(width: 6, height: 6)
                        Text(model.connectionSummary)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .contentTransition(.numericText())
                    }
                }
            }
            Spacer(minLength: 0)
            RoundIconMenu(symbol: "ellipsis", size: 30) {
                Button("Pair a device…") { openMain(pairing: true) }
                Button("Settings…") {
                    NSApp.activate(ignoringOtherApps: true)
                    openSettings()
                }
                Divider()
                Button("Quit Tandem") { NSApp.terminate(nil) }
                    .keyboardShortcut("q")
            }
            .help("More")
            RoundIconButton(symbol: "macwindow", tint: Palette.indigo, size: 30) { openMain(pairing: false) }
                .help("Open Tandem")
        }
        // The first control in the panel would take the keyboard focus and a blue ring as soon as the panel opens.
        .focusEffectDisabled()
        .padding(.leading, 8)
        .animation(.tandem, value: model.onlineCount)
    }

    private var empty: some View {
        VStack(spacing: 8) {
            Text("No devices yet").font(.callout.weight(.medium))
            Text("Pair your phone to send files and share the clipboard.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("Pair a device") { openMain(pairing: true) }
                .buttonStyle(.tandemGlassProminent)
                .padding(.top, 2)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 14)
    }

    private func openMain(pairing: Bool) {
        openWindow(id: "main")
        NSApp.activate(ignoringOtherApps: true)
        if pairing {
            // The window may be made from nothing, which takes longer than the delay: the flag is for that case.
            MainWindow.wantsPairing = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) {
                NotificationCenter.default.post(name: .tandemShowPairing, object: nil)
            }
        }
    }
}

/// Where the hotspot is: being asked for, turning on, joined, in use, or what went wrong, with its button. The
/// icon in the menu bar says the same in short, so this is what to read once the panel is open.
private struct MenuHotspot: View {
    @Environment(EngineModel.self) private var model

    var body: some View {
        let status = model.hotspotStatus
        HStack(spacing: 10) {
            ZStack {
                if status.isBusy {
                    PillSpinner(size: 20)
                } else {
                    Image(systemName: symbol(status))
                        .font(.system(size: 15, weight: .semibold))
                        .foregroundStyle(tint(status))
                }
            }
            .frame(width: 24, height: 24)
            VStack(alignment: .leading, spacing: 1) {
                Text("Hotspot").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                Text(status.text).font(.callout).lineLimit(3).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 4)
            if let phone = model.hotspotPhone {
                if case .connected = status {
                    Button("Stop") { model.hotspot.stop(phone.id) }
                        .buttonStyle(.tandemGlass)
                        .controlSize(.small)
                } else if case .failed = status {
                    Button("Try again") { model.hotspot.requestNow(phone.id) }
                        .buttonStyle(.tandemGlass)
                        .controlSize(.small)
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 9)
        .background(tint(status).opacity(0.10), in: .rect(cornerRadius: 18, style: .continuous))
        .transition(.opacity.combined(with: .move(edge: .top)))
    }

    private func symbol(_ status: HotspotStatus) -> String {
        switch status {
        case .connected: "personalhotspot"
        case .failed: "exclamationmark.triangle.fill"
        default: "wifi"
        }
    }

    private func tint(_ status: HotspotStatus) -> Color {
        switch status {
        case .failed: Palette.urgent
        default: Palette.indigo
        }
    }
}

private struct MenuDeviceRow: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    @LocalState private var targeted = false
    /// What the button under the pointer does, shown in the place of the status line while the pointer is on it.
    @LocalState private var hint: LocalizedStringKey?
    /// Counts the times the pointer left a button, so a later one cancels the way back to the status line.
    @LocalState private var left = 0

    /// On the way from one button to the next the pointer crosses the gap: the status line comes back only when it stays off them a moment.
    private func hover(_ inside: Bool, _ text: LocalizedStringKey) {
        left += 1
        if inside {
            hint = text
            return
        }
        let mark = left
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) {
            if left == mark { hint = nil }
        }
    }

    var body: some View {
        let reach = model.reach(of: device)
        VStack(spacing: 8) {
            HStack(spacing: 10) {
                DeviceGlyph(platform: device.platform, online: device.online, size: 36, ring: true, deviceID: device.id, greenWhenOnline: true)
                VStack(alignment: .leading, spacing: 1) {
                    Text(device.name).font(.callout.weight(.semibold)).lineLimit(1)
                    HStack(spacing: 5) {
                        if let hint {
                            Text(hint)
                                .font(.caption.weight(.medium))
                                .foregroundStyle(Palette.indigo)
                                .transition(.opacity)
                        } else {
                            // Connected says itself: the glyph is green. Only what is not the plain case is written out.
                            if reach != .network {
                                Text(reach.text)
                                    .font(.caption.weight(.medium))
                                    .foregroundStyle(reach.color)
                                    .transition(.opacity)
                            }
                            if let battery = device.status.battery {
                                Image(systemName: batterySymbol(battery)).font(.caption2).foregroundStyle(.secondary)
                                Text("\(battery.level)%").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                            }
                            if device.online, let rtt = device.rttMs {
                                // The dot separates it from what comes before; with nothing before it, it stands alone.
                                let alone = reach == .network && device.status.battery == nil
                                Text(alone ? "\(rtt) ms" : "· \(rtt) ms").font(.caption.monospacedDigit()).foregroundStyle(.secondary)
                            }
                        }
                    }
                    .lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            // The buttons share the width under the name, all the same size, instead of crowding the name at its side.
            HStack(spacing: 6) {
                RoundIconButton(symbol: "paperplane.fill", size: 32, onHover: { hover($0, "Send files") }) { pickFiles() }
                    .disabled(!device.online)
                // What is copied goes there by itself while sharing the clipboard is on for this device: only with that off is a
                // button for sending it once worth its place.
                if !device.clipboardEnabled {
                    RoundIconButton(symbol: "doc.on.clipboard", size: 32, onHover: { hover($0, "Send clipboard") }) {
                        model.sendClipboard(to: [device.id])
                    }
                    .disabled(!(device.online || device.ble))
                }
                LiveMenuButton(device: device, size: 32, onHover: { hover($0, device.isComputer ? "Control computer" : "Show phone screen") })
                // A computer: this Mac's screen extended to it, and the shared mouse going over to it.
                if device.isComputer {
                    if device.caps.contains("screen.extend.view") {
                        let extending = ScreenHost.shared.isExtending(to: device.id)
                        RoundIconButton(
                            symbol: "rectangle.on.rectangle", tint: extending ? Palette.indigo : nil, size: 32,
                            onHover: { hover($0, extending ? "Stop extending" : "Extend my screen") }
                        ) {
                            if extending { ScreenHost.shared.stop(peer: device.id) }
                            else if !VirtualDisplay.isAvailable { model.showToast(String(localized: "This Mac cannot make an extra screen")) }
                            else { ScreenHost.shared.offerExtend(to: device) }
                        }
                        .disabled(!device.online && !extending)
                    }
                    let share = PointerShare.shared
                    let mouse = share.enabled && share.layout[device.id] != nil
                    RoundIconButton(
                        symbol: mouse ? "computermouse.fill" : "computermouse", tint: mouse ? Palette.indigo : nil, size: 32,
                        onHover: { hover($0, mouse ? "Stop the universal mouse" : "Universal mouse") }
                    ) { toggleMouse() }
                    .disabled(!device.online && !mouse)
                }
                // The other way round: what is on this Mac, on the phone. Only to a phone that can look at a computer's screen.
                if device.platform == .android, device.caps.contains("screen.view") {
                    let on = ScreenHost.shared.isMirroring(to: device.id)
                    RoundIconButton(
                        symbol: "macbook.and.iphone", tint: on ? Palette.indigo : nil, size: 32,
                        onHover: { hover($0, on ? "Stop showing this Mac" : "Show this Mac on the phone") }
                    ) { ScreenHost.shared.toggleMirror(on: device) }
                    .disabled(!device.online)
                }
                if device.platform == .android {
                    let on = model.speaker.device == device.id
                    RoundIconButton(
                        symbol: on ? "speaker.wave.3.fill" : "speaker.wave.2", tint: on ? Palette.indigo : nil, size: 32,
                        onHover: { hover($0, on ? "Stop phone output" : "Phone as output") }
                    ) { model.toggleSpeaker(for: device.id) }
                    .disabled(!device.online)
                }
            }
            .environment(\.iconButtonsFill, true)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .hoverHighlight(radius: 18, tint: targeted ? Palette.indigo : .primary, selected: targeted)
        .scaleEffect(targeted ? 1.02 : 1)
        .animation(.tandemSpringy, value: targeted)
        .animation(.tandem, value: device.online)
        .animation(.tandemFade, value: hint == nil)
        .dropDestination(for: URL.self) { urls, _ in
            guard device.online else {
                model.showToast(String(localized: "That device is not connected right now"))
                return false
            }
            model.send(urls: urls, to: [device.id])
            return true
        } isTargeted: { targeted = $0 }
    }

    /// The pointer of this Mac goes over the edge to this computer, or no longer: on, it goes on the side the computer was last found on
    /// (the right until a check knows better, which starts at once).
    private func toggleMouse() {
        let share = PointerShare.shared
        if share.enabled, share.layout[device.id] != nil {
            share.layout[device.id] = nil
            return
        }
        if share.layout[device.id] == nil {
            share.layout[device.id] = PointerShare.Placement(edge: DeviceSides.side(of: device.id) ?? "right", offset: nil)
        }
        share.enabled = true
        SideWatch.shared.moved(device.id)
    }

    private func batterySymbol(_ battery: TandemBattery) -> String {
        if battery.charging { return "battery.100percent.bolt" }
        switch battery.level {
        case ..<13: return "battery.0percent"
        case ..<38: return "battery.25percent"
        case ..<63: return "battery.50percent"
        case ..<88: return "battery.75percent"
        default: return "battery.100percent"
        }
    }

    private func pickFiles() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = true
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = true
        NSApp.activate(ignoringOtherApps: true)
        if panel.runModal() == .OK { model.send(urls: panel.urls, to: [device.id]) }
    }
}

/// A device that cannot be reached now, on one quiet line: its buttons would do nothing, so it has none.
private struct MenuAwayRow: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice

    var body: some View {
        let reach = model.reach(of: device)
        HStack(spacing: 10) {
            Image(systemName: device.platform.symbol)
                .font(.system(size: 13, weight: .medium))
                .frame(width: 36)
            Text(device.name).font(.callout).lineLimit(1)
            Spacer(minLength: 6)
            Text(reach.text).font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .foregroundStyle(.secondary)
        .opacity(0.75)
    }
}

/// Four things with one press, chosen in Settings: a tile each, the shortcut that does the same under its name.
private struct MenuTiles: View {
    @Environment(EngineModel.self) private var model
    /// Read again whenever the panel opens, so a change in Settings shows at once.
    @LocalState private var tiles = PanelAction.tiles

    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 8), GridItem(.flexible(), spacing: 8)], spacing: 8) {
            ForEach(tiles) { action in
                MenuTile(action: action, shortcut: ActionShortcuts.shared.shortcut(for: action)?.display)
            }
        }
        .onAppear { tiles = PanelAction.tiles }
        .onReceive(NotificationCenter.default.publisher(for: UserDefaults.didChangeNotification)) { _ in
            let now = PanelAction.tiles
            if now != tiles { tiles = now }
        }
    }
}

/// The symbol in a disc at the left, the name and the shortcut next to it, on the same plain tint as the cards around it. Under the
/// pointer the tint deepens and the disc fills, the symbol turning white: colour only, and the press dips it like the other buttons.
private struct MenuTile: View {
    @Environment(EngineModel.self) private var model
    let action: PanelAction
    let shortcut: String?
    @LocalState private var hovering = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 18, style: .continuous)
        // What needs a phone that is not there looks dimmed, and says why when it is pressed.
        let ready = action.ready
        Button { action.perform() } label: {
            HStack(spacing: 10) {
                ZStack {
                    Circle().fill(hovering ? Palette.indigo : Palette.indigo.opacity(0.15))
                    Image(systemName: action.symbol)
                        .font(.system(size: 14, weight: .semibold))
                        .fadingForeground(Palette.indigo, to: Color.white, when: hovering)
                }
                .frame(width: 32, height: 32)
                VStack(alignment: .leading, spacing: 1) {
                    Text(verbatim: action.tileTitle)
                        .font(.system(size: 12.5, weight: .semibold))
                        .lineLimit(1)
                    if let shortcut {
                        Text(verbatim: shortcut)
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                    }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10)
            .frame(maxWidth: .infinity)
            .frame(height: 52)
            .background(shape.fill(Color.primary.opacity(hovering ? 0.11 : 0.05)))
            .contentShape(shape)
            .opacity(ready ? 1 : 0.55)
        }
        .buttonStyle(.bouncy)
        .onHover { hovering = $0 }
        .animation(.tandemFade, value: hovering)
        .help(action.title)
    }
}

import AppKit
import ApplicationServices
import SwiftUI
import TandemCore

/// Everything about one mouse and keyboard for more computers, on one page: how it works, what this Mac has to allow,
/// which computer sits where, and which of them may use this Mac. The logic is in `PointerShare`.
struct PointerPage: View {
    @Environment(EngineModel.self) private var model
    @Bindable private var share = PointerShare.shared
    @LocalState private var trusted = AXIsProcessTrusted()

    private var computers: [TandemDevice] { model.devices.filter { $0.platform != .android } }
    private var phones: [TandemDevice] { model.devices.filter { $0.platform == .android } }

    private static let sides: [(tag: String, label: LocalizedStringKey)] = [
        ("none", "Not next to this Mac"), ("left", "On the left"), ("right", "On the right"), ("top", "Above"), ("bottom", "Below"),
    ]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Mouse and keyboard").font(.largeTitle.weight(.bold))
                    Text("One mouse and one keyboard for more computers. Push the pointer over the edge of the screen and it carries on at the next computer.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }

                howItWorks
                permissions
                mainComputer
                controlled
                phoneCard
            }
            .pagePadding()
            .frame(maxWidth: 760, alignment: .leading)
            .frame(maxWidth: .infinity)
            .animation(.tandem, value: share.enabled)
            .animation(.tandem, value: trusted)
        }
        .onAppear {
            share.materialize(computers.map(\.id))
            trusted = AXIsProcessTrusted()
        }
        // The permission only changes in System Settings, so it is read again when the person comes back and now and then.
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in
            trusted = AXIsProcessTrusted()
        }
        .onReceive(Timer.publish(every: 2, on: .main, in: .common).autoconnect()) { _ in
            let now = AXIsProcessTrusted()
            if now != trusted { trusted = now }
        }
    }

    // MARK: How it works

    private var howItWorks: some View {
        Card(radius: Metrics.card, padding: 18) {
            VStack(alignment: .leading, spacing: 14) {
                Text("How it works").font(.headline)
                step("1", symbol: "rectangle.2.swap", "Say which computer sits on which side of this one. That is the edge the pointer goes over.")
                step("2", symbol: "cursorarrow.motionlines", "Push the pointer over that edge. Your mouse and keyboard now work on the other computer.")
                step("3", symbol: "arrow.uturn.backward", "Push it back over the edge it came in by, or press Control, Option and Command with Escape.")
            }
        }
    }

    private func step(_ number: String, symbol: String, _ text: LocalizedStringKey) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 14, weight: .medium))
                .foregroundStyle(Palette.indigo)
                .frame(width: 30, height: 30)
                .background(Palette.indigo.opacity(0.12), in: .circle)
            Text(text)
                .font(.callout)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 5)
        }
    }

    // MARK: What this Mac has to allow

    private var permissions: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionTitle("What this Mac has to allow")
            Card(radius: Metrics.card, padding: 6) {
                VStack(spacing: 0) {
                    SettingRow(
                        symbol: "figure.wave",
                        title: "Accessibility",
                        subtitle: trusted
                            ? "Allowed. Tandem can see your mouse and keyboard and play them from another computer"
                            : "Needed to see your mouse and keyboard, and to play them when another computer uses this Mac",
                        subtitleColor: trusted ? .secondary : Palette.urgent
                    ) {
                        if trusted {
                            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green).font(.title3)
                        } else {
                            Button("Allow") { requestAccessibility() }
                                .buttonStyle(.glassProminent)
                                .tint(Palette.indigo)
                        }
                    }
                }
            }
            Text("Windows needs nothing but the firewall: allow Tandem on private networks when Windows asks.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 8)
        }
    }

    private func requestAccessibility() {
        _ = AXIsProcessTrustedWithOptions(["AXTrustedCheckOptionPrompt": true] as CFDictionary)
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
    }

    // MARK: This Mac as the main computer

    private var mainComputer: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionTitle("Use this Mac's mouse and keyboard on other computers")
            Card(radius: Metrics.card, padding: 6) {
                VStack(spacing: 0) {
                    SettingRow(
                        symbol: "cursorarrow.rays",
                        title: "Let the pointer go to other computers",
                        subtitle: trusted ? "Choose below where each computer sits" : "Allow Accessibility first"
                    ) {
                        Toggle("", isOn: $share.enabled).disabled(!trusted)
                    }
                    .opacity(trusted ? 1 : 0.55)
                    if share.enabled && trusted {
                        if computers.isEmpty {
                            Divider().opacity(0.4).padding(.horizontal, 14)
                            note("Pair a Mac or a Windows PC to put it next to this one.")
                        }
                        ForEach(computers, id: \.id) { device in
                            Divider().opacity(0.4).padding(.horizontal, 14)
                            SettingRow(
                                symbol: device.platform.symbol, title: LocalizedStringKey(device.name),
                                subtitle: device.online ? "Connected" : "Not connected right now"
                            ) {
                                Picker("", selection: Binding(
                                    get: { share.neighbours[device.id] ?? "none" },
                                    set: { share.neighbours[device.id] = $0 == "none" ? nil : $0 }
                                )) {
                                    ForEach(Self.sides, id: \.tag) { Text($0.label).tag($0.tag) }
                                }
                                .pickerStyle(.menu)
                                .fixedSize()
                            }
                        }
                    }
                }
            }
            if share.enabled {
                Text("The other computer has to allow it too: in its Tandem, under Mouse and keyboard.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 8)
            }
        }
    }

    // MARK: Other computers using this Mac

    private var controlled: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionTitle("Other computers using this Mac")
            Card(radius: Metrics.card, padding: 6) {
                VStack(spacing: 0) {
                    if computers.isEmpty {
                        note("Pair a Mac or a Windows PC first. Then you can say here which of them may use this Mac.")
                    }
                    ForEach(Array(computers.enumerated()), id: \.element.id) { index, device in
                        if index > 0 { Divider().opacity(0.4).padding(.horizontal, 14) }
                        SettingRow(
                            symbol: device.platform.symbol, title: LocalizedStringKey(device.name),
                            subtitle: share.controlledBy == device.id ? "Using this Mac right now" : "May use this Mac's screen as its next screen"
                        ) {
                            Toggle("", isOn: Binding(
                                get: { share.isAllowed(device.id) },
                                set: { on in
                                    if on { share.allowed.insert(device.id) } else { share.allowed.remove(device.id) }
                                }
                            ))
                            .disabled(!trusted)
                        }
                        .opacity(trusted ? 1 : 0.55)
                    }
                }
            }
            Text("Nobody may until you turn it on here. Only computers in your circle can ask at all.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.horizontal, 8)
        }
    }

    // MARK: Phones

    private var phoneCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            sectionTitle("Your phone")
            Card(radius: Metrics.card, padding: 6) {
                VStack(spacing: 0) {
                    note("A phone has no edge to cross. Show its screen in a window here and move the pointer into it: clicks, scrolling and typing go to the phone. The phone has to allow that with Tandem's control under Accessibility.")
                    ForEach(phones, id: \.id) { device in
                        Divider().opacity(0.4).padding(.horizontal, 14)
                        SettingRow(
                            symbol: "iphone", title: LocalizedStringKey(device.name),
                            subtitle: device.caps.contains("screen.host") ? (device.online ? "Ready" : "Not connected right now") : "Update Tandem on the phone first"
                        ) {
                            Button("Show screen") { LiveManager.shared.start(device: device, kind: .screen) }
                                .buttonStyle(.glass)
                                .disabled(!device.online || !device.caps.contains("screen.host"))
                        }
                    }
                }
            }
        }
    }

    // MARK: Pieces

    private func sectionTitle(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(.headline)
            .padding(.horizontal, 8)
            .padding(.top, 4)
    }

    private func note(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(.callout)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
    }
}

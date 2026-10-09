import AppKit
import ApplicationServices
import SwiftUI
import TandemCore

/// One mouse and keyboard for more computers: how it works, what this Mac has to allow, which computer sits where, and
/// which of them may use this Mac. The logic is in `PointerShare`.
struct PointerSettings: View {
    @Environment(EngineModel.self) private var model
    @Bindable private var share = PointerShare.shared
    @LocalState private var trusted = AXIsProcessTrusted()

    private var computers: [TandemDevice] { model.devices.filter { $0.platform != .android } }
    private var phones: [TandemDevice] { model.devices.filter { $0.platform == .android } }
    /// What can sit next to this Mac: the computers, and the phones whose Tandem can take a pointer in.
    private var neighbourChoices: [TandemDevice] { computers + phones.filter { $0.caps.contains("pointer.in") } }

    private static let sides: [(tag: String, label: LocalizedStringKey)] = [
        ("none", "Not next to this Mac"), ("left", "On the left"), ("right", "On the right"), ("top", "Above"), ("bottom", "Below"),
    ]

    var body: some View {
        Form {
            Section {
                step("rectangle.2.swap", "Say which computer sits on which side of this one. That is the edge the pointer goes over.")
                step("cursorarrow.motionlines", "Push the pointer over that edge. Your mouse and keyboard now work on the other computer.")
                step("arrow.uturn.backward", "Push it back over the edge it came in by, or press Control, Option and Command with Escape.")
            } header: {
                Text("How it works")
            }

            Section {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Accessibility")
                        Text(trusted
                            ? "Allowed. Tandem can see your mouse and keyboard and play them from another computer"
                            : "Needed to see your mouse and keyboard, and to play them when another computer uses this Mac")
                            .font(.caption)
                            .foregroundStyle(trusted ? Color.secondary : Palette.urgent)
                    }
                    Spacer(minLength: 12)
                    if trusted {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                    } else {
                        Button("Allow") { requestAccessibility() }
                            .buttonStyle(.glassProminent)
                            .tint(Palette.indigo)
                    }
                }
            } header: {
                Text("What this Mac has to allow")
            } footer: {
                Text("Windows needs nothing but the firewall: allow Tandem on private networks when Windows asks.")
            }

            Section {
                DescribedToggle(
                    "Let the pointer go to other computers",
                    subtitle: trusted ? "Choose below where each computer sits" : "Allow Accessibility first",
                    isOn: $share.enabled
                )
                .disabled(!trusted)
                if share.enabled && trusted {
                    if neighbourChoices.isEmpty {
                        Text("Pair a Mac, a PC or a phone to put it next to this one.").foregroundStyle(.secondary)
                    }
                    ForEach(neighbourChoices, id: \.id) { device in
                        Picker(device.name, selection: Binding(
                            get: { share.neighbours[device.id] ?? "none" },
                            set: { share.neighbours[device.id] = $0 == "none" ? nil : $0 }
                        )) {
                            ForEach(Self.sides, id: \.tag) { Text($0.label).tag($0.tag) }
                        }
                    }
                }
            } header: {
                Text("Use this Mac's mouse and keyboard on other computers")
            } footer: {
                if share.enabled { Text("The other computer has to allow it too: in its Tandem, under Mouse and keyboard.") }
            }

            Section {
                if computers.isEmpty {
                    Text("Pair a Mac or a Windows PC first. Then you can say here which of them may use this Mac.").foregroundStyle(.secondary)
                }
                ForEach(computers, id: \.id) { device in
                    DescribedToggle(
                        LocalizedStringKey(device.name),
                        subtitle: share.controlledBy == device.id ? "Using this Mac right now" : "May use this Mac's screen as its next screen",
                        isOn: Binding(
                            get: { share.isAllowed(device.id) },
                            set: { on in
                                if on { share.allowed.insert(device.id) } else { share.allowed.remove(device.id) }
                            }
                        )
                    )
                    .disabled(!trusted)
                }
            } header: {
                Text("Other computers using this Mac")
            } footer: {
                Text("Nobody may until you turn it on here. Only computers in your circle can ask at all.")
            }

            Section {
                Text("A phone can sit next to this Mac too, when you turn on \"Use a computer's mouse on this phone\" in its Tandem. Or show its screen in a window here and move the pointer into it: clicks, scrolling and typing go to the phone. Both need Tandem's control under Accessibility on the phone.")
                    .foregroundStyle(.secondary)
                ForEach(phones, id: \.id) { device in
                    LabeledContent(device.name) {
                        Button("Show screen") { LiveManager.shared.start(device: device, kind: .screen) }
                            .disabled(!device.online || !device.caps.contains("screen.host"))
                    }
                }
            } header: {
                Text("Your phone")
            }
        }
        .formStyle(.grouped)
        .animation(.tandem, value: share.enabled)
        .animation(.tandem, value: trusted)
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

    private func step(_ symbol: String, _ text: LocalizedStringKey) -> some View {
        Label {
            Text(text)
        } icon: {
            Image(systemName: symbol).foregroundStyle(Palette.indigo)
        }
    }

    private func requestAccessibility() {
        _ = AXIsProcessTrustedWithOptions(["AXTrustedCheckOptionPrompt": true] as CFDictionary)
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility") {
            NSWorkspace.shared.open(url)
        }
    }
}

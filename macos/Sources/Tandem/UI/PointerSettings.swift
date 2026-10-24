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
    @LocalState private var finding: String?
    @AppStorage(SideWatch.enabledKey) private var checkByItself = true

    private func findSide(_ device: TandemDevice) {
        finding = device.id
        Task {
            let side = await SideFinder.find(device)
            finding = nil
            model.showToast(SideFinder.message(side, name: device.name))
        }
    }

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
                step("rectangle.2.swap", "Drag the screens next to this one, the way they stand on your desk. That is where the pointer goes over.")
                step("cursorarrow.motionlines", "Push the pointer over that edge. Your mouse and keyboard now work on the other computer.")
                step("arrow.uturn.backward", "Push it back over the edge it came in by, or press Control, Option and Command with Escape.")
            } header: {
                Text("How it works")
            }

            Section {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Accessibility")
                        // Grey either way: someone may not want this, so a permission that is not given is no warning.
                        Text(trusted
                            ? "Allowed. Tandem can see your mouse and keyboard and play them from another computer"
                            : "Needed to see your mouse and keyboard, and to play them when another computer uses this Mac")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    Spacer(minLength: 12)
                    if trusted {
                        Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
                    } else {
                        Button("Allow") { requestAccessibility() }
                    }
                }
            } header: {
                Text("What this Mac has to allow")
            } footer: {
                Text("Windows needs nothing but the firewall: allow Tandem on private networks when Windows asks.")
            }

            Section {
                // Looks the same with or without the permission: turning it on without it asks for it.
                DescribedToggle(
                    "Universal mouse",
                    subtitle: "Choose below where each computer sits",
                    isOn: Binding(get: { share.enabled }, set: { on in
                        if on && !trusted { requestAccessibility() } else { share.enabled = on }
                    })
                )
                // Always there and only greyed while off: rows that come and go with the switch made the whole page jump.
                Group {
                    if neighbourChoices.isEmpty {
                        Text("Pair a Mac, a PC or a phone to put it next to this one.").foregroundStyle(.secondary)
                    } else {
                        ArrangementEditor(share: share, devices: neighbourChoices)
                            .padding(.vertical, 4)
                        ForEach(neighbourChoices, id: \.id) { device in
                            LabeledContent(device.name) {
                                HStack(spacing: 10) {
                                    Group {
                                        if share.layout[device.id] == nil { Text("Not placed") } else { Text(sideText(share.layout[device.id]?.edge ?? "")) }
                                    }
                                    .foregroundStyle(.secondary)
                                    // This Mac plays a sound and the other one says which speaker was louder: it is then put on that side.
                                    if device.caps.contains("side.listen") {
                                        Button(finding == device.id ? "Listening…" : "Find by sound") { findSide(device) }
                                            .disabled(!device.online || finding != nil)
                                    }
                                    if share.layout[device.id] != nil {
                                        Button("Remove") { share.layout[device.id] = nil }
                                    }
                                }
                            }
                        }
                    }
                }
                .disabled(!share.enabled)
                .opacity(share.enabled ? 1 : 0.45)
            } header: {
                Text("Use this Mac's mouse and keyboard on other computers")
            } footer: {
                Text("The other computer has to allow it too: in its Tandem, under Mouse and keyboard.")
            }

            Section {
                DescribedToggle(
                    "Check the side by itself",
                    subtitle: "When a second screen starts, a computer comes back and now and then while you use them. With a sound too high to hear, and only when this Mac plays through its own speakers.",
                    isOn: $checkByItself
                )
                HStack {
                    Text("A short sweep from low to high on the left and from high to low on the right, at the same time. Soft first, louder when it was not heard.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Spacer(minLength: 12)
                    Button("Play") { Task { await SideTone.play() } }
                }
            } header: {
                Text("Finding the side by sound")
            } footer: {
                Text("This Mac plays a short sound from its left and its right speaker, and the other device listens with its microphone. A second screen goes on that side too.")
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
                                if on && !trusted {
                                    requestAccessibility()
                                } else if on {
                                    share.allowed.insert(device.id)
                                } else {
                                    share.allowed.remove(device.id)
                                }
                            }
                        )
                    )
                }
            } header: {
                Text("Other computers using this Mac")
            } footer: {
                Text("Nobody may until you turn it on here. Only computers in your circle can ask at all.")
            }

            Section {
                Text("A phone can sit next to this Mac too, when you turn on \"Universal mouse\" in its Tandem. Or show its screen in a window here and move the pointer into it: clicks, scrolling and typing go to the phone. Both need Tandem's control under Accessibility on the phone.")
                    .foregroundStyle(.secondary)
                ForEach(phones, id: \.id) { device in
                    LabeledContent(device.name) {
                        // Grey only while the phone is away; an older Tandem there is said when the button is pressed.
                        Button("Show screen") {
                            if device.caps.contains("screen.host") {
                                LiveManager.shared.start(device: device, kind: .screen)
                            } else {
                                FloatingToast.show(String(localized: "Update Tandem on the phone to version 0.1.38 or newer"), symbol: "iphone.gen3")
                            }
                        }
                        .disabled(!device.online)
                    }
                }
            } header: {
                Text("Your phone")
            }
        }
        .formStyle(.grouped)
        .animation(.tandemFade, value: share.enabled)
        .animation(.tandemFade, value: trusted)
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

    private func sideText(_ edge: String) -> LocalizedStringKey {
        switch edge {
        case "left": "On the left"
        case "right": "On the right"
        case "top": "Above"
        default: "Below"
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

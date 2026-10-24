import SwiftUI
import TandemCore

/// Play sound on the page of a computer: it rings until it is found, like Find My. The button always looks the same; only a press that
/// cannot work (the computer is away, or its Tandem is too old) says why, next to the button.
struct PlaySoundButton: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    @LocalState private var why: WhyNot?

    enum WhyNot: Identifiable {
        case offline, old
        var id: Self { self }
    }

    var body: some View {
        let ringing = FindOthers.shared.ringing.contains(device.id)
        GlassActionButton(
            title: ringing ? "Stop sound" : "Play sound",
            symbol: ringing ? "bell.slash.fill" : "bell.and.waves.left.and.right",
            prominent: ringing
        ) {
            if ringing {
                FindOthers.shared.ring(device.id, on: false)
            } else if !device.online {
                why = .offline
            } else if !device.caps.contains("ring") {
                why = .old
            } else {
                FindOthers.shared.ring(device.id, on: true)
                model.showToast(String(localized: "\(device.name) plays a sound"))
            }
        }
        .popover(item: $why, arrowEdge: .bottom) { why in
            VStack(alignment: .leading, spacing: 6) {
                switch why {
                case .offline:
                    Text("\(device.name) is offline").font(.headline)
                    Text("It can play a sound again as soon as it is connected.")
                    if let seen = LastSeenText.text(for: device) { Text(verbatim: seen).foregroundStyle(.secondary) }
                case .old:
                    Text("Update Tandem on \(device.name)").font(.headline)
                    Text("The Tandem on it cannot play a sound yet. Update it there, then try again.")
                }
            }
            .font(.callout)
            .fixedSize(horizontal: false, vertical: true)
            .frame(width: 280, alignment: .leading)
            .padding(16)
        }
    }
}

/// When a computer was last connected to this Mac and on which network, under the buttons of its page.
struct LastSeenText: View {
    let device: TandemDevice

    var body: some View {
        if let text = Self.text(for: device) {
            Label(text, systemImage: device.online ? "wifi" : "clock")
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.leading, 4)
                .contentTransition(.opacity)
        }
    }

    static func text(for device: TandemDevice) -> String? {
        guard let seen = device.lastSeen else { return nil }
        if device.online { return seen.ssid.map { String(localized: "Connected on \($0)") } }
        let when = Date(timeIntervalSince1970: Double(seen.atMs) / 1000).formatted(.relative(presentation: .named, unitsStyle: .wide))
        if let ssid = seen.ssid { return String(localized: "Last seen \(when) on \(ssid)") }
        switch seen.route {
        case .lan: return String(localized: "Last seen \(when) on the local network")
        case .tailnet: return String(localized: "Last seen \(when) over Tailscale")
        default: return String(localized: "Last seen \(when)")
        }
    }
}

/// The sound this Mac plays when another device looks for it, from the sounds of the system, with a preview.
struct FindSoundSettings: View {
    @AppStorage(FindSounds.key) private var picked = ""
    @LocalState private var sounds: [FindSounds.Sound] = []

    var body: some View {
        Section {
            Picker("Sound", selection: Binding(get: { _ = picked; return FindSounds.chosen }, set: { picked = $0; FindSounds.preview($0) })) {
                Section("Alert sounds") {
                    ForEach(sounds.filter { !$0.ringtone }) { Text(verbatim: $0.name).tag($0.path) }
                }
                if sounds.contains(where: \.ringtone) {
                    Section("Ringtones") {
                        ForEach(sounds.filter(\.ringtone)) { Text(verbatim: $0.name).tag($0.path) }
                    }
                }
            }
            LabeledContent("Preview") {
                Button("Play") { FindSounds.preview(FindSounds.chosen) }
            }
        } header: {
            Text("Find this Mac")
        } footer: {
            Text("When your phone or another computer looks for this Mac, this sound plays loudly until you stop it, for two minutes at most.")
        }
        .onAppear { sounds = FindSounds.all() }
        .onDisappear { FindSounds.stopPreview() }
    }
}

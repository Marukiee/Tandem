import SwiftUI
import TandemCore

/// A switch that makes a phone this Mac's speaker: everything the Mac plays comes out of the phone.
struct SpeakerCard: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice

    private var active: Bool { model.speaker.device == device.id }

    var body: some View {
        Card(radius: Metrics.card, padding: 16) {
            HStack(spacing: 14) {
                Image(systemName: active ? "speaker.wave.3.fill" : "speaker.wave.2")
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(active ? Palette.indigo : .secondary)
                    .frame(width: 44, height: 44)
                    .background((active ? Palette.indigo : Color.primary).opacity(0.1), in: .circle)
                    .contentTransition(.symbolEffect(.replace))

                VStack(alignment: .leading, spacing: 3) {
                    Text("Use this phone as a speaker").font(.headline)
                    Text(subtitle)
                        .font(.callout)
                        .foregroundStyle(failure == nil ? Color.secondary : Palette.urgent)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 8)
                if case .starting = model.speaker, active { PillSpinner(size: 18) }
                Toggle("", isOn: Binding(get: { active }, set: { _ in model.toggleSpeaker(for: device.id) }))
                    .labelsHidden()
                    .disabled(!device.online)
            }
        }
        .animation(.tandem, value: model.speaker)
    }

    private var failure: String? {
        if case let .failed(text) = model.speaker { return text }
        return nil
    }

    private var subtitle: String {
        switch model.speaker {
        case .on(device.id): String(localized: "Everything this Mac plays comes out of \(device.name)")
        case .starting(device.id): String(localized: "Starting")
        case let .failed(text): text
        default: String(localized: "Plays this Mac's sound on \(device.name) and mutes this Mac. Uses about 700 MB an hour. The first time, macOS asks to allow System Audio Recording.")
        }
    }
}

import SwiftUI
import TandemCore

/// On top of the page Shared: transfers that stopped halfway before Tandem started again, each with "Go on or throw away?" (P2).
struct HalfwaySection: View {
    @Environment(EngineModel.self) private var model
    private let halfway = HalfwayTransfers.shared

    var body: some View {
        let waiting = halfway.waiting.filter { model.sharedDeviceFilter == nil || $0.from == model.sharedDeviceFilter }
        if !waiting.isEmpty {
            Card(radius: Metrics.card, padding: Metrics.cardInset) {
                VStack(spacing: 2) {
                    ForEach(waiting, id: \.self) { transfer in
                        HalfwayRow(transfer: transfer)
                            .transition(.asymmetric(insertion: .scale(scale: 0.96).combined(with: .opacity), removal: .scale(scale: 0.96).combined(with: .opacity)))
                    }
                }
            }
            .transition(.scale(scale: 0.97, anchor: .top).combined(with: .opacity))
        }
    }
}

/// One transfer that stopped halfway: what it is, how far it got, and the two answers. Laid out like a row of the transfers under it.
struct HalfwayRow: View {
    @Environment(EngineModel.self) private var model
    let transfer: TandemResumable

    var body: some View {
        let halfway = HalfwayTransfers.shared
        let online = model.device(transfer.from)?.online == true
        let bytes = halfway.bytes(transfer)
        let name = halfway.senderName(transfer)
        HStack(spacing: 12) {
            ZStack {
                Circle().fill(Palette.indigo.opacity(0.16))
                Image(systemName: "pause.fill")
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(Palette.indigo)
            }
            .frame(width: 40, height: 40)

            VStack(alignment: .leading, spacing: 4) {
                Text(halfway.title(transfer)).font(.callout.weight(.medium)).lineLimit(1).truncationMode(.middle)
                Group {
                    if online {
                        Text("Stopped halfway, \(formatBytes(bytes.done)) of \(formatBytes(bytes.total)) from \(name). Go on or throw away?")
                    } else {
                        Text("Stopped halfway. \(name) is not connected.")
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
                .contentTransition(.opacity)
                ProgressCapsule(fraction: bytes.total == 0 ? 0 : Double(bytes.done) / Double(bytes.total))
                    .frame(height: 5)
                    .saturation(online ? 1 : 0)
            }

            Spacer(minLength: 8)

            HStack(spacing: 8) {
                Button("Throw away") { halfway.throwAway(transfer) }
                    .buttonStyle(.tandemGlass)
                // Grey while the sender is away; a click then says why instead of doing nothing.
                Button("Go on") { halfway.goOn(transfer) }
                    .buttonStyle(.tandemGlassProminent)
                    .saturation(online ? 1 : 0)
                    .opacity(online ? 1 : 0.55)
                    .help(online ? Text("") : Text("\(name) is not connected"))
            }
            .fixedSize()
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 10)
        .animation(.tandem, value: online)
    }
}

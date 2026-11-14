import SwiftUI
import TandemCore

/// On top of the page Shared: what is underway and what is incoming (P3b). Underway is what this Mac sends that runs, waits for a yes,
/// waits for a device that is away, or failed; incoming is what comes in, waits for a yes here, or stopped halfway (P2). Each row is one
/// transfer with one device, so stopping one leaves the others going.
struct UnderwaySection: View {
    @Environment(EngineModel.self) private var model
    private let underway = UnderwayTransfers.shared
    private let halfway = HalfwayTransfers.shared

    var body: some View {
        let filter = model.sharedDeviceFilter
        let outgoing = underway.outgoing.filter { filter == nil || $0.peer.device == filter }
        let incoming = underway.incoming.filter { filter == nil || $0.peer.device == filter }
        let stopped = halfway.waiting.filter { filter == nil || $0.from == filter }
        VStack(alignment: .leading, spacing: 16) {
            if !outgoing.isEmpty {
                group("Underway") {
                    ForEach(outgoing) { row in
                        UnderwayRow(row: row).transition(Self.rowTransition)
                    }
                }
            }
            if !incoming.isEmpty || !stopped.isEmpty {
                group("Incoming") {
                    ForEach(stopped, id: \.self) { transfer in
                        HalfwayRow(transfer: transfer).transition(Self.rowTransition)
                    }
                    ForEach(incoming) { row in
                        UnderwayRow(row: row).transition(Self.rowTransition)
                    }
                }
            }
        }
        .animation(.tandem, value: outgoing)
        .animation(.tandem, value: incoming)
        .animation(.tandem, value: stopped)
    }

    private static let rowTransition = AnyTransition.asymmetric(
        insertion: .scale(scale: 0.96).combined(with: .opacity),
        removal: .scale(scale: 0.96).combined(with: .opacity)
    )

    private func group<Content: View>(_ title: LocalizedStringKey, @ViewBuilder rows: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.callout.weight(.semibold))
                .foregroundStyle(Palette.indigo)
                .padding(.horizontal, 4)
            Card(radius: Metrics.card, padding: Metrics.cardInset) {
                VStack(spacing: 2) { rows() }
            }
        }
        .transition(.scale(scale: 0.97, anchor: .top).combined(with: .opacity))
    }
}

/// One transfer with one device, laid out like the rows of the list under it and like `HalfwayRow`.
struct UnderwayRow: View {
    @Environment(EngineModel.self) private var model
    let row: UnderwayTransfers.Row

    var body: some View {
        let underway = UnderwayTransfers.shared
        let bytes = underway.bytes(row)
        let name = underway.name(row)
        HStack(spacing: 12) {
            ZStack {
                Circle().fill(tint.opacity(0.16))
                Image(systemName: symbol)
                    .font(.system(size: 15, weight: .semibold))
                    .foregroundStyle(tint)
                    .contentTransition(.symbolEffect(.replace))
            }
            .frame(width: 40, height: 40)

            VStack(alignment: .leading, spacing: 4) {
                Text(underway.title(row)).font(.callout.weight(.medium)).lineLimit(1).truncationMode(.middle)
                Text(subtitle(name: name, bytes: bytes))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .contentTransition(.opacity)
                if row.kind == .running {
                    ProgressCapsule(fraction: bytes.total == 0 ? 0 : Double(bytes.done) / Double(bytes.total))
                        .frame(height: 5)
                        .transition(.opacity)
                }
            }

            Spacer(minLength: 8)

            actions(name: name).fixedSize()
        }
        .padding(.vertical, 6)
        .padding(.horizontal, 10)
        .animation(.tandem, value: row.kind)
    }

    @ViewBuilder
    private func actions(name: String) -> some View {
        let underway = UnderwayTransfers.shared
        switch row.kind {
        case .waiting where row.incoming:
            HStack(spacing: 8) {
                Button("Decline") { underway.decline(row) }
                    .buttonStyle(.tandemGlass)
                Button("Accept") { underway.accept(row) }
                    .buttonStyle(.tandemGlassProminent)
            }
        case .failed:
            HStack(spacing: 8) {
                Button("Send again") { underway.sendAgain(row) }
                    .buttonStyle(.tandemGlass)
                RowActionButton(symbol: "xmark", help: "Hide") { underway.putAwayRow(row) }
            }
        default:
            RowActionButton(symbol: "xmark", help: row.kind == .queued ? "Do not send" : "Stop") { underway.stop(row) }
        }
    }

    private func subtitle(name: String, bytes: (done: UInt64, total: UInt64)) -> String {
        switch row.kind {
        case .queued:
            String(localized: "Waits for \(name). It goes as soon as \(name) is back.")
        case .waiting:
            row.incoming
                ? String(localized: "\(name) wants to send this, \(formatBytes(bytes.total))")
                : String(localized: "Waits for a yes on \(name)")
        case .running:
            row.incoming
                ? String(localized: "\(formatBytes(bytes.done)) of \(formatBytes(bytes.total)) from \(name)")
                : String(localized: "\(formatBytes(bytes.done)) of \(formatBytes(bytes.total)) to \(name)")
        case .failed:
            UnderwayTransfers.shared.reason(row)
        }
    }

    private var symbol: String {
        switch row.kind {
        case .queued: "clock"
        case .waiting: row.incoming ? "tray.and.arrow.down" : "hourglass"
        case .running: row.incoming ? "arrow.down" : "arrow.up"
        case .failed: "exclamationmark"
        }
    }

    private var tint: Color {
        row.kind == .failed ? Palette.urgent : Palette.indigo
    }
}

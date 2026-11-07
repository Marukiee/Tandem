import SwiftUI

/// Which device of a backup comes back on this Mac. The one it recognised is chosen already; a device of another kind stays in
/// the list, grey, and says why when pressed.
struct RestorePicker: View {
    let choice: BackupChoice
    let restore: (BackupDevice) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var picked: Int?
    @State private var reason: String?

    init(choice: BackupChoice, restore: @escaping (BackupDevice) -> Void) {
        self.choice = choice
        self.restore = restore
        _picked = State(initialValue: choice.recognised)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Restore settings").font(.title3.weight(.semibold))
                Text(choice.recognised == nil
                    ? "Pick the device whose settings come back on this Mac."
                    : "This Mac is recognised in the backup. Pick another device if that is not right.")
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            VStack(spacing: 2) {
                ForEach(choice.devices) { device in
                    RestoreRow(device: device, picked: picked == device.index, recognised: choice.recognised == device.index) {
                        if device.fits {
                            withAnimation(.snappy) {
                                picked = device.index
                                reason = nil
                            }
                        } else {
                            withAnimation(.snappy) { reason = device.reason }
                        }
                    }
                }
            }
            .padding(.horizontal, -10)
            if let reason {
                Text(reason)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .transition(.opacity.combined(with: .move(edge: .top)))
            }
            HStack {
                Spacer()
                Button("Cancel", role: .cancel) { dismiss() }
                    .keyboardShortcut(.cancelAction)
                Button("Restore") {
                    if let picked, let device = choice.devices.first(where: { $0.index == picked }) { restore(device) }
                    dismiss()
                }
                .keyboardShortcut(.defaultAction)
                .disabled(picked == nil)
            }
        }
        .padding(20)
        .frame(width: 400)
    }
}

private struct RestoreRow: View {
    let device: BackupDevice
    let picked: Bool
    let recognised: Bool
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: device.symbol)
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(device.fits ? Color.white : Color.secondary)
                    .frame(width: 34, height: 34)
                    .background(Circle().fill(device.fits ? Color.accentColor : Color.secondary.opacity(0.18)))
                VStack(alignment: .leading, spacing: 1) {
                    Text(device.name).font(.body.weight(.medium)).lineLimit(1)
                    Text(detail).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 0)
                Image(systemName: picked ? "checkmark.circle.fill" : "circle")
                    .font(.system(size: 17))
                    .foregroundStyle(picked ? Color.accentColor : Color.secondary.opacity(device.fits ? 0.6 : 0))
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .background(
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .fill(Color.primary.opacity(picked ? 0.08 : hover && device.fits ? 0.05 : 0))
            )
            .opacity(device.fits ? 1 : 0.5)
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }

    private var detail: String {
        if recognised { return String(localized: "This Mac") }
        var parts: [String] = []
        switch device.platform {
        case "android": parts.append(String(localized: "Phone"))
        case "mac": parts.append("Mac")
        case "windows": parts.append("Windows")
        case "linux": parts.append("Linux")
        default: break
        }
        if let model = device.model, !model.isEmpty { parts.append(model) }
        return parts.joined(separator: " · ")
    }
}

import SwiftUI

/// The notifications your phones have shown, newest first. They also arrive as ordinary macOS
/// notifications; this is where they stay once those have gone.
struct NotificationsView: View {
    @Environment(EngineModel.self) private var model

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Notifications").font(.largeTitle.weight(.bold))
                    Text("What your phones showed. New ones also appear as notifications on this Mac.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }
                if model.mirrored.isEmpty {
                    ContentUnavailableView(
                        "No notifications yet",
                        systemImage: "bell",
                        description: Text("When a phone shows a notification, it is listed here. Nothing coming in? On the phone, open Settings, Permissions and look at Notification access.")
                    )
                    .frame(maxWidth: .infinity, minHeight: 260)
                } else {
                    Card(radius: Metrics.card, padding: Metrics.cardInset) {
                        VStack(spacing: 2) {
                            ForEach(model.mirrored) { item in
                                NotificationRow(item: item)
                                    .transition(.asymmetric(insertion: .scale(scale: 0.96).combined(with: .opacity), removal: .opacity))
                            }
                        }
                    }
                }
            }
            .pagePadding()
            .frame(maxWidth: 760, alignment: .leading)
            .frame(maxWidth: .infinity)
            .animation(.tandem, value: model.mirrored.map(\.id))
        }
        .toolbar {
            if !model.mirrored.isEmpty {
                ToolbarItem(placement: .primaryAction) {
                    Button {
                        withAnimation(.tandem) { model.clearMirrored() }
                    } label: {
                        Label("Clear all", systemImage: "xmark.bin")
                    }
                    .help("Clear all")
                }
            }
        }
    }
}

private struct NotificationRow: View {
    @Environment(EngineModel.self) private var model
    let item: MirroredNotification

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "bell.fill")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Palette.indigo)
                .frame(width: 34, height: 34)
                .background(Palette.indigo.opacity(0.14), in: Circle())
            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(item.appName).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    Spacer(minLength: 8)
                    Text(item.date, style: .relative).font(.caption).foregroundStyle(.tertiary)
                }
                if !item.title.isEmpty { Text(item.title).font(.callout.weight(.semibold)) }
                if !item.text.isEmpty { Text(item.text).font(.callout).foregroundStyle(.secondary).lineLimit(4) }
                Text(item.deviceName).font(.caption2).foregroundStyle(.tertiary)
                if let code = item.code {
                    Button { model.copyCode(code) } label: {
                        Label("Copy code \(code)", systemImage: "doc.on.doc")
                            .font(.caption.weight(.semibold))
                    }
                    .buttonStyle(.glass)
                    .controlSize(.small)
                    .padding(.top, 4)
                }
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .hoverHighlight(radius: Metrics.cardInner)
    }
}

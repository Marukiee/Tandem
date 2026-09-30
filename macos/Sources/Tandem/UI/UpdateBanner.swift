import SwiftUI

/// The bar that shows up at the top of the window and the menu bar panel when a newer
/// version is out. It only appears when there is something to do, one button does the
/// whole update, and "Later" is remembered per version so it does not nag.
struct UpdateBanner: View {
    @LocalState private var updater = Updater.shared
    @AppStorage("updateDismissedVersion") private var dismissedVersion = ""
    var compact = false

    var body: some View {
        Group {
            switch updater.state {
            case let .available(release) where release.version != dismissedVersion:
                bar {
                    Text("Version \(release.version) is available").font(.subheadline.weight(.semibold))
                    Spacer(minLength: 8)
                    Button("Later") { dismissedVersion = release.version }
                        .buttonStyle(.plain)
                        .foregroundStyle(.secondary)
                    Button("Update and restart") { Task { await updater.install() } }
                        .buttonStyle(.glassProminent)
                        .tint(Palette.indigo)
                        .controlSize(.small)
                }
            case let .downloading(release, progress):
                bar {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Downloading version \(release.version)").font(.subheadline.weight(.semibold))
                        ProgressCapsule(fraction: progress).frame(height: 5)
                    }
                }
            case .installing:
                bar {
                    PillSpinner(size: 16)
                    Text("Installing").font(.subheadline.weight(.semibold))
                    Spacer(minLength: 0)
                }
            default:
                EmptyView()
            }
        }
        .transition(.move(edge: .top).combined(with: .opacity))
        .animation(.tandemSpringy, value: updater.state)
    }

    private func bar<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "arrow.down.circle.fill").foregroundStyle(Palette.indigo)
            content()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, compact ? 8 : 10)
        .background(Palette.indigo.opacity(0.12), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .padding(.horizontal, compact ? 0 : 20)
        .padding(.top, compact ? 0 : 10)
    }
}

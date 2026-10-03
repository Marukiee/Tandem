import SwiftUI

/// The card that shows up at the top of the window and in the menu bar panel when a newer version is out.
/// It only appears when there is something to do, one button does the whole update, "Later" is
/// remembered per version so it does not nag, and a failed update says so instead of disappearing.
///
/// A solid card like the others on the page, not glass over the content: it has to be read at a glance.
struct UpdateBanner: View {
    @LocalState private var updater = Updater.shared
    @AppStorage("updateDismissedVersion") private var dismissedVersion = ""
    var compact = false
    /// Slides in from the bottom, for a banner that sits at the foot of something.
    var fromBottom = false

    var body: some View {
        Group {
            switch updater.state {
            case let .available(release) where release.version != dismissedVersion:
                available(release)
            case let .downloading(release, progress):
                downloading(release, progress)
            case .installing:
                installing
            case let .failed(message) where updater.installFailed:
                failed(message)
            default:
                EmptyView()
            }
        }
        .transition(.move(edge: fromBottom ? .bottom : .top).combined(with: .opacity))
        .animation(.tandemSpringy, value: updater.state)
    }

    // MARK: States

    private func available(_ release: Updater.Release) -> some View {
        card {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 12) {
                    icon("arrow.down")
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Version \(release.version) is available")
                            .font(compact ? .callout.weight(.semibold) : .headline)
                            .lineLimit(1)
                        Text("You have version \(updater.currentVersion)")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    if !compact {
                        Spacer(minLength: 12)
                        actions(release)
                    }
                }
                if compact {
                    HStack {
                        Spacer(minLength: 0)
                        actions(release)
                    }
                }
            }
        }
    }

    private func actions(_ release: Updater.Release) -> some View {
        HStack(spacing: 12) {
            Button("Later") { dismissedVersion = release.version }
                .buttonStyle(.plain)
                .foregroundStyle(.secondary)
            Button("Update and restart") { Task { await updater.install() } }
                .buttonStyle(.glassProminent)
                .tint(Palette.indigo)
                .controlSize(compact ? .small : .regular)
        }
    }

    private func downloading(_ release: Updater.Release, _ progress: Double) -> some View {
        card {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 12) {
                    icon("arrow.down")
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Downloading version \(release.version)")
                            .font(compact ? .callout.weight(.semibold) : .headline)
                            .lineLimit(1)
                        if release.size > 0 {
                            Text("\(Self.bytes(Int64(Double(release.size) * progress))) of \(Self.bytes(release.size))")
                                .font(.caption.monospacedDigit())
                                .foregroundStyle(.secondary)
                        }
                    }
                    Spacer(minLength: 8)
                    Text("\(Int((progress * 100).rounded()))%")
                        .font((compact ? Font.callout : Font.title3).weight(.semibold).monospacedDigit())
                        .contentTransition(.numericText())
                }
                ProgressCapsule(fraction: progress).frame(height: compact ? 6 : 8)
            }
        }
    }

    private var installing: some View {
        card {
            HStack(spacing: 12) {
                PillSpinner(size: compact ? 22 : 26)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Installing").font(compact ? .callout.weight(.semibold) : .headline)
                    Text("Tandem opens again by itself").font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
        }
    }

    private func failed(_ message: String) -> some View {
        card(tint: Palette.urgent) {
            HStack(alignment: .top, spacing: 12) {
                icon("exclamationmark.triangle.fill", tint: Palette.urgent)
                VStack(alignment: .leading, spacing: 2) {
                    Text("The update did not work").font(compact ? .callout.weight(.semibold) : .headline)
                    Text(message).font(.caption).foregroundStyle(.secondary).lineLimit(3)
                }
                Spacer(minLength: 8)
                Button("Try again") {
                    Task {
                        // The release is forgotten once it failed, so look again, then install what is there.
                        await updater.check(manual: true)
                        await updater.install()
                    }
                }
                .buttonStyle(.glass)
                .controlSize(compact ? .small : .regular)
                Button { updater.state = .idle } label: {
                    Image(systemName: "xmark").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .help("Dismiss")
            }
        }
    }

    // MARK: Pieces

    private func icon(_ symbol: String, tint: Color = Palette.indigo) -> some View {
        Image(systemName: symbol)
            .font(.system(size: compact ? 13 : 15, weight: .bold))
            .foregroundStyle(.white)
            .frame(width: compact ? 30 : 36, height: compact ? 30 : 36)
            .background(tint.gradient, in: .circle)
    }

    /// An opaque card with a little of its colour in it and a soft shadow, so it reads as lying on the page.
    private func card<Content: View>(tint: Color = Palette.indigo, @ViewBuilder _ content: () -> Content) -> some View {
        let radius: CGFloat = compact ? 18 : 22
        return content()
            .padding(.horizontal, compact ? 12 : 16)
            .padding(.vertical, compact ? 10 : 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .surface(radius: radius, tint: tint)
            .overlay { RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(tint.opacity(0.25), lineWidth: 1) }
            .shadow(color: .black.opacity(0.10), radius: 10, y: 3)
            .padding(.horizontal, compact ? 0 : 20)
            .padding(.top, compact ? 0 : 10)
            .padding(.bottom, compact ? 0 : 6)
    }

    private static func bytes(_ count: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: count, countStyle: .file)
    }
}

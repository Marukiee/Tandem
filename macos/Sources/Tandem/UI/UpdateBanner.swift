import SwiftUI

/// The card that shows up at the top of the window and in the menu bar panel when a newer version is out.
/// It only appears when there is something to do, one button does the whole update, "Later" is
/// remembered per version so it does not nag, and a failed update says so instead of disappearing.
///
/// A solid card like the others on the page, not glass over the content: it has to be read at a glance. In the menu bar panel and the
/// sidebar (`compact`) it is a card like its neighbours there: the same fill, corners and disc, with its buttons on the row itself.
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

    @ViewBuilder
    private func available(_ release: Updater.Release) -> some View {
        if compact {
            card {
                HStack(spacing: 10) {
                    icon("arrow.down.circle.fill")
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Version \(release.version)").font(.callout.weight(.semibold)).lineLimit(1)
                        Text("You have \(updater.currentVersion)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                    Spacer(minLength: 4)
                    Button { dismissedVersion = release.version } label: {
                        Text("Later").font(.caption.weight(.semibold))
                    }
                    .buttonStyle(.tandemGlass)
                    .controlSize(.small)
                    Button { Task { await updater.install() } } label: {
                        Text("Update").font(.caption.weight(.semibold))
                    }
                    .buttonStyle(.tandemGlassProminent)
                    .controlSize(.small)
                    .help("Update and restart")
                }
            }
        } else {
            wide(release)
        }
    }

    private func wide(_ release: Updater.Release) -> some View {
        card {
            HStack(spacing: 12) {
                icon("arrow.down")
                VStack(alignment: .leading, spacing: 2) {
                    Text("Version \(release.version) is available").font(.headline).lineLimit(1)
                    Text("You have version \(updater.currentVersion)").font(.caption).foregroundStyle(.secondary)
                }
                Spacer(minLength: 12)
                Button("Later") { dismissedVersion = release.version }
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                Button("Update and restart") { Task { await updater.install() } }
                    .buttonStyle(.tandemGlassProminent)
            }
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
            VStack(alignment: .leading, spacing: 10) {
                HStack(alignment: .center, spacing: 12) {
                    icon("exclamationmark.triangle.fill", tint: Palette.urgent)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("The update did not work")
                            .font(compact ? .callout.weight(.semibold) : .headline)
                            .lineLimit(1)
                        Text(message)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .lineLimit(2)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 8)
                    if !compact { retry }
                    RowActionButton(symbol: "xmark", help: "Dismiss") { updater.state = .idle }
                }
                // In the narrow panel the button gets its own row, as the update itself does, so nothing wraps.
                if compact {
                    HStack {
                        Spacer(minLength: 0)
                        retry
                    }
                }
            }
        }
    }

    private var retry: some View {
        Button("Try again") {
            Task {
                // The release is forgotten once it failed, so look again, then install what is there.
                await updater.check(manual: true)
                await updater.install()
            }
        }
        .buttonStyle(.tandemGlassProminent)
        .controlSize(compact ? .small : .regular)
        .fixedSize()
    }

    // MARK: Pieces

    @ViewBuilder
    private func icon(_ symbol: String, tint: Color = Palette.indigo) -> some View {
        if compact {
            // The disc of the rows above it in the panel.
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 36, height: 36)
                .background(Circle().fill(tint.opacity(0.14)))
        } else {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(.white)
                .frame(width: 36, height: 36)
                .background(tint.gradient, in: .circle)
        }
    }

    /// An opaque card with a little of its colour in it and a soft shadow, so it reads as lying on the page. Compact, the card of the
    /// panel: a faint fill with the corners of the cards above it, and only a colour when something went wrong.
    @ViewBuilder
    private func card<Content: View>(tint: Color = Palette.indigo, @ViewBuilder _ content: () -> Content) -> some View {
        if compact {
            content()
                .padding(.horizontal, 10)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(4)
                .background(tint == Palette.indigo ? Color.primary.opacity(0.05) : tint.opacity(0.12), in: .rect(cornerRadius: 22, style: .continuous))
        } else {
            wideCard(tint: tint, content)
        }
    }

    private func wideCard<Content: View>(tint: Color, _ content: () -> Content) -> some View {
        let radius: CGFloat = 22
        return content()
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .surface(radius: radius, tint: tint)
            .overlay { RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(tint.opacity(0.25), lineWidth: 1) }
            .shadow(color: .black.opacity(0.10), radius: 10, y: 3)
            .padding(.horizontal, 20)
            .padding(.top, 10)
            .padding(.bottom, 6)
    }

    private static func bytes(_ count: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: count, countStyle: .file)
    }
}

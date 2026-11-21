import SwiftUI

/// The card that shows up in the sidebar and in the menu bar panel when a newer version is out. One card for the whole update: Later and
/// Update share its width, and Update turns into a progress bar while the file comes in. When it is in, the update installs and Tandem
/// starts again by itself, there is no second click. "Later" is remembered per version so it does not nag, and a failed update says so.
struct UpdateBanner: View {
    @LocalState private var updater = Updater.shared
    @AppStorage("updateDismissedVersion") private var dismissedVersion = ""
    /// Slides in from the bottom, for a banner that sits at the foot of something.
    var fromBottom = false

    private enum Phase { case rest, downloading, installing }

    /// What the card shows: one card whose parts change, not one view per state, so the change can be a movement.
    private var current: (release: Updater.Release, phase: Phase, progress: Double)? {
        switch updater.state {
        case let .available(release) where release.version != dismissedVersion: (release, .rest, 0)
        case let .downloading(release, progress): (release, .downloading, progress)
        case .installing:
            if let release = updater.installingRelease { (release, .installing, 1) } else { nil }
        default: nil
        }
    }

    var body: some View {
        Group {
            if let current {
                card(current.release, current.phase, current.progress)
            } else if case let .failed(message) = updater.state, updater.installFailed {
                failed(message)
            }
        }
        .transition(.move(edge: fromBottom ? .bottom : .top).combined(with: .opacity))
        .animation(.tandemSpringy, value: updater.state)
    }

    // MARK: The card

    private func card(_ release: Updater.Release, _ phase: Phase, _ progress: Double) -> some View {
        let notes = Updater.notes(release.notes)
        return VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                tile(done: phase == .installing)
                lines(release, phase, progress)
            }
            if phase != .installing, !notes.isEmpty {
                VStack(alignment: .leading, spacing: 1) {
                    ForEach(notes, id: \.self) { note in
                        Text("\u{2022} \(note)").lineLimit(2).fixedSize(horizontal: false, vertical: true)
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .padding(.leading, 50)
                .transition(.move(edge: .top).combined(with: .opacity))
            }
            actions(release, phase, progress)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(4)
        .background(Color.primary.opacity(0.05), in: .rect(cornerRadius: 22, style: .continuous))
        .animation(.tandem, value: phase)
    }

    /// Title and the line under it: the old pair fades out and the new one fades in on the same place, nothing slides.
    private func lines(_ release: Updater.Release, _ phase: Phase, _ progress: Double) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(phase == .installing ? "Installing \(release.version)" : "New version \(release.version)")
                .font(.callout.weight(.semibold)).lineLimit(1)
            Group {
                switch phase {
                case .rest: Text("You have \(updater.currentVersion)")
                case .downloading:
                    if release.size > 0 {
                        Text("\(Self.megabytes(Int64(Double(release.size) * progress))) of \(Self.megabytes(release.size)) MB").monospacedDigit().contentTransition(.numericText())
                    } else {
                        Text("Downloading")
                    }
                case .installing: Text("Restarts in a moment")
                }
            }
            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
        }
        .id(phase)
        .transition(.opacity)
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(height: 38)
    }

    /// A sparkle for "new", and once it installs a round green tick.
    private func tile(done: Bool) -> some View {
        let green = Color(hex: 0x34C759)
        return ZStack {
            Image(systemName: "sparkles").opacity(done ? 0 : 1).scaleEffect(done ? 0.3 : 1).rotationEffect(.degrees(done ? -30 : 0))
            Image(systemName: "checkmark").opacity(done ? 1 : 0).scaleEffect(done ? 1 : 0.3)
        }
        .font(.system(size: 17, weight: .semibold))
        .foregroundStyle(done ? green : Palette.indigo)
        .frame(width: 40, height: 40)
        .background((done ? green : Palette.indigo).opacity(0.16), in: .rect(cornerRadius: done ? 20 : 13, style: .continuous))
        .animation(.tandem, value: done)
    }

    // MARK: Buttons and bar

    /// Later takes a third of the width and Update two thirds. While the file comes in Later shrinks to nothing and Update is the bar.
    private func actions(_ release: Updater.Release, _ phase: Phase, _ progress: Double) -> some View {
        GeometryReader { proxy in
            let share: CGFloat = phase == .rest ? 1 : 0
            let gap = 8 * share
            let later = (proxy.size.width - 8) / 3 * share
            let wide = proxy.size.width - later - gap
            HStack(spacing: 0) {
                Button { dismissedVersion = release.version } label: {
                    Text("Later").font(.caption.weight(.semibold)).lineLimit(1).fixedSize().frame(maxWidth: .infinity)
                }
                .buttonStyle(.tandemGlass)
                .controlSize(.small)
                .frame(width: later)
                .clipped()
                .opacity(share)
                .allowsHitTesting(phase == .rest)
                Spacer().frame(width: gap)
                ZStack {
                    Button { Task { await updater.install() } } label: {
                        Text("Update").font(.caption.weight(.semibold)).lineLimit(1).frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.tandemGlassProminent)
                    .controlSize(.small)
                    .opacity(phase == .rest ? 1 : 0)
                    .allowsHitTesting(phase == .rest)
                    .help("Update and restart")
                    bar(phase, progress, width: wide)
                        .opacity(phase == .rest ? 0 : 1)
                        .allowsHitTesting(false)
                }
                .frame(width: wide)
            }
        }
        .frame(height: 28)
    }

    /// The bar that Update turns into: filled as far as the download has come, full and softly shimmering while it installs.
    private func bar(_ phase: Phase, _ progress: Double, width: CGFloat) -> some View {
        ZStack(alignment: .leading) {
            Capsule().fill(Color.primary.opacity(0.1))
            Capsule().fill(Palette.indigo.gradient)
                .frame(width: max(0, width * (phase == .installing ? 1 : progress)))
                .animation(.tandem, value: progress)
            if phase == .installing { Sheen().clipShape(.capsule) }
            ZStack {
                if phase == .installing {
                    Text("Installing…").transition(.opacity)
                } else {
                    Text("\(Int((progress * 100).rounded()))%").monospacedDigit().contentTransition(.numericText())
                        .transition(.opacity)
                }
            }
            .font(.caption.weight(.bold))
            .foregroundStyle(phase == .installing || progress > 0.5 ? Color.white : Color.primary)
            .frame(maxWidth: .infinity)
        }
        .frame(height: 28)
        .animation(.tandem, value: phase)
    }

    /// A soft light that passes over the full bar while the installer works.
    private struct Sheen: View {
        var body: some View {
            TimelineView(.animation) { context in
                GeometryReader { proxy in
                    let t = context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 1.4) / 1.4
                    LinearGradient(colors: [.clear, .white.opacity(0.22), .clear], startPoint: .leading, endPoint: .trailing)
                        .frame(width: proxy.size.width * 0.6)
                        .offset(x: proxy.size.width * (CGFloat(t) * 1.6 - 0.6))
                }
            }
        }
    }

    // MARK: Failed

    private func failed(_ message: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .center, spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(Palette.urgent)
                    .frame(width: 40, height: 40)
                    .background(Palette.urgent.opacity(0.16), in: .rect(cornerRadius: 13, style: .continuous))
                VStack(alignment: .leading, spacing: 1) {
                    Text("The update did not work").font(.callout.weight(.semibold)).lineLimit(1)
                    Text(message).font(.caption).foregroundStyle(.secondary).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 4)
                RowActionButton(symbol: "xmark", help: "Dismiss") { updater.state = .idle }
            }
            Button {
                Task {
                    // The release is forgotten once it failed, so look again, then install what is there.
                    await updater.check(manual: true)
                    await updater.install()
                }
            } label: {
                Text("Again").font(.caption.weight(.semibold)).frame(maxWidth: .infinity)
            }
            .buttonStyle(.tandemGlassProminent)
            .controlSize(.small)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(4)
        .background(Palette.urgent.opacity(0.12), in: .rect(cornerRadius: 22, style: .continuous))
    }

    private static func megabytes(_ count: Int64) -> String {
        (Double(count) / 1_000_000).formatted(.number.precision(.fractionLength(1)))
    }
}

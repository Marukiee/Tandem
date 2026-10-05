import AppKit
import SwiftUI
import TandemCore

/// The cards in the corner of the screen while someone sends files to this Mac with Quick Share: who, what, the PIN to compare,
/// and the two buttons. After accepting it shows the progress, and after that where the files went.
@MainActor
final class QuickSharePanel {
    static let shared = QuickSharePanel()

    private static let width: CGFloat = 392
    private static let margin: CGFloat = 40
    private var panel: NSPanel?
    private var host: NSHostingView<QuickShareStack>?

    func refresh() {
        let share = QuickShare.shared
        guard !share.incoming.isEmpty else {
            panel?.orderOut(nil)
            return
        }
        let panel = panel ?? makePanel()
        self.panel = panel
        // The height is worked out from what the cards hold: a hosting view without a sizing option does not tell.
        let cards = share.incoming.map { item -> CGFloat in
            if item.failure != nil { return 128 }
            if item.saved != nil { return 142 }
            return item.accepted ? 128 : 174
        }
        let height = cards.reduce(0, +) + CGFloat(max(0, cards.count - 1)) * 12 + Self.margin * 2
        guard let area = (NSScreen.main ?? NSScreen.screens.first)?.visibleFrame else { return }
        let frame = NSRect(
            x: area.maxX - Self.width - Self.margin * 2 + Self.margin - 8, y: area.maxY - height + Self.margin - 8,
            width: Self.width + Self.margin * 2, height: height
        )
        panel.setFrame(frame, display: true)
        if !panel.isVisible { panel.orderFrontRegardless() }
    }

    private func makePanel() -> NSPanel {
        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: Self.width + Self.margin * 2, height: 260),
            styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false
        )
        panel.isFloatingPanel = true
        panel.level = .statusBar
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.animationBehavior = .none
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        let host = NSHostingView(rootView: QuickShareStack())
        host.sizingOptions = []
        // Not the content view itself: a hosting view that is the content view resizes its window by itself in the middle of a
        // layout pass, which AppKit does not allow (see InsertHUD).
        let container = NSView(frame: NSRect(origin: .zero, size: panel.frame.size))
        container.autoresizesSubviews = true
        host.frame = container.bounds
        host.autoresizingMask = [.width, .height]
        container.addSubview(host)
        panel.contentView = container
        self.host = host
        return panel
    }
}

private struct QuickShareStack: View {
    private var share: QuickShare { .shared }

    var body: some View {
        VStack(spacing: 12) {
            ForEach(share.incoming) { item in
                QuickShareCard(item: item)
                    .transition(.asymmetric(insertion: .scale(scale: 0.94, anchor: .top).combined(with: .opacity), removal: .opacity))
            }
        }
        .frame(width: 392)
        .padding(40)
        .frame(maxHeight: .infinity, alignment: .top)
        .animation(.spring(response: 0.46, dampingFraction: 0.86), value: share.incoming.map(\.id))
    }
}

private struct QuickShareCard: View {
    let item: QuickShare.Incoming
    private var share: QuickShare { .shared }

    private var summary: String {
        guard let first = item.files.first else { return String(localized: "Something") }
        let rest = item.files.count - 1
        if rest <= 0 { return first.name }
        return String(localized: "\(first.name) and \(rest) more")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(spacing: 12) {
                ZStack {
                    Circle().fill(Palette.indigo.opacity(0.14))
                    Image(systemName: item.saved != nil ? "checkmark" : (item.failure != nil ? "exclamationmark" : "arrow.down.circle.fill"))
                        .font(.system(size: 18, weight: .semibold))
                        .foregroundStyle(item.failure != nil ? Palette.urgent : Palette.indigo)
                        .contentTransition(.symbolEffect(.replace))
                }
                .frame(width: 44, height: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.system(size: 15, weight: .semibold)).lineLimit(1)
                    Text(subtitle).font(.system(size: 13)).foregroundStyle(.secondary).lineLimit(2)
                }
                Spacer(minLength: 0)
            }

            if item.accepted && item.saved == nil && item.failure == nil {
                ProgressCapsule(fraction: item.total == 0 ? 0 : Double(item.done) / Double(item.total)).frame(height: 5)
            } else if !item.accepted {
                HStack(spacing: 8) {
                    Image(systemName: "number").font(.caption.weight(.bold)).foregroundStyle(.secondary)
                    Text("PIN \(item.pin)").font(.system(size: 13, weight: .semibold, design: .rounded)).monospacedDigit()
                    Text("must match the other device").font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(1)
                }
                .padding(.horizontal, 12)
                .frame(height: 30)
                .background(Capsule().fill(Color.primary.opacity(0.07)))
            }

            buttons
        }
        .padding(18)
        .frame(width: 392)
        .liveGlass(in: .rect(cornerRadius: 32, style: .continuous))
        .outerShadow(radius: 32, blur: 20, y: 8, opacity: 0.22)
    }

    private var title: String {
        if item.failure != nil { return String(localized: "The transfer stopped") }
        if item.saved != nil { return String(localized: "Received from \(item.sender)") }
        if item.accepted { return String(localized: "Receiving from \(item.sender)") }
        return String(localized: "\(item.sender) wants to share")
    }

    private var subtitle: String {
        if let failure = item.failure { return failure }
        if let saved = item.saved { return saved.count == 1 ? URL(fileURLWithPath: saved[0]).lastPathComponent : String(localized: "\(saved.count) files in Downloads") }
        return "\(summary) · \(formatBytes(item.total))"
    }

    @ViewBuilder
    private var buttons: some View {
        HStack(spacing: 10) {
            if item.saved != nil {
                Button("Show in Finder") {
                    NSWorkspace.shared.activateFileViewerSelecting((item.saved ?? []).map { URL(fileURLWithPath: $0) })
                    share.dismiss(item.id)
                }
                .buttonStyle(.glass)
                Spacer(minLength: 0)
                Button("Done") { share.dismiss(item.id) }.buttonStyle(.glassProminent).tint(Palette.indigo)
            } else if item.failure != nil {
                Spacer(minLength: 0)
                Button("Close") { share.dismiss(item.id) }.buttonStyle(.glass)
            } else if item.accepted {
                Spacer(minLength: 0)
                Button("Cancel") { share.decline(item.id) }.buttonStyle(.glass)
            } else {
                Button("Decline") { share.decline(item.id) }.buttonStyle(.glass)
                Spacer(minLength: 0)
                Button("Accept") { share.accept(item.id) }.buttonStyle(.glassProminent).tint(Palette.indigo)
            }
        }
        .controlSize(.large)
    }
}

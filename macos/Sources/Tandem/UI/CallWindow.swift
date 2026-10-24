import AppKit
import Observation
import SwiftUI
import TandemCore

/// One call on one phone, as its card shows it.
struct CallCard: Identifiable, Equatable {
    enum Phase: Equatable {
        case ringing
        case dialing
        /// Since when, by the clock of this Mac: the phone's own clock can be off by minutes.
        case active(since: Date)
        case missed
    }

    let device: String
    let call: String
    var deviceName: String
    var name: String?
    var number: String?
    var incoming: Bool
    var phase: Phase

    /// Two phones can give the same id to a call (it is a time in milliseconds), so the phone is part of it.
    var id: String { "\(device)/\(call)" }

    var title: String { name ?? number ?? String(localized: "Unknown number") }
}

@MainActor
@Observable
final class CallCards {
    var cards: [CallCard] = []
}

/// The small floating window of a call on a phone, like the call popup of WhatsApp: who it is, which phone, and Answer and Decline while
/// it rings, Hang up while it goes on. The talking stays on the phone or its headset. One card per call, stacked, in the top right corner
/// of the screen until it is dragged somewhere else.
///
/// The panel never activates the app or takes the keyboard, so a call coming in does not take the cursor out of what is being typed.
@MainActor
final class CallWindow {
    static let shared = CallWindow()

    static let cardWidth: CGFloat = 320
    static let cardHeight: CGFloat = 112
    static let gap: CGFloat = 10
    /// Room around the cards for their shadow: a shadow that reaches past the edge of a window is cut off in a straight line.
    static let margin: CGFloat = 32

    /// Set by the model: sends Answer, Decline or Hang up to the phone.
    var onAction: ((_ device: String, _ callId: String, _ action: TandemCallAction) -> Void)?

    let model = CallCards()
    private var panel: CallPanel?
    private var hideTask: Task<Void, Never>?
    private var shrinkTask: Task<Void, Never>?
    /// What takes a card away by itself: a ringing that never ended (the phone went away) or a missed call that was shown.
    private var expiry: [String: Task<Void, Never>] = [:]
    /// Calls declined or hung up here. The phone reports a declined call as missed, and that is no news to whoever declined it.
    private var closedHere: [String: Date] = [:]
    /// The number this Mac asked a phone to call. Android does not say whom it is calling, so the card takes it from here.
    private var dialed: (device: String, number: String, name: String?, at: Date)?
    /// Where the person dragged the cards to, for as long as the app runs.
    private var topLeft: NSPoint?
    private var debugKeep = false

    // MARK: What the phone says

    func received(_ call: TandemCall, from device: String, deviceName: String) {
        let key = "\(device)/\(call.id)"
        let index = model.cards.firstIndex { $0.id == key }
        switch call.state {
        case .ringing, .dialing:
            guard closedHere[key] == nil else { return }
            let phase: CallCard.Phase = call.state == .ringing && call.incoming ? .ringing : .dialing
            if let index {
                // Ringing is sent again once the phone has looked up the name: the same card, with the name.
                fill(index, from: call, deviceName: deviceName)
                if model.cards[index].phase != phase, !model.cards[index].isActive {
                    withAnimation(.tandem) { model.cards[index].phase = phase }
                }
            } else {
                insert(card(for: call, device: device, deviceName: deviceName, phase: phase))
            }
            // A phone that stops ringing says so. One that is gone says nothing, and its card should not stay forever.
            expire(key, after: .seconds(120))

        case .active:
            guard closedHere[key] == nil else { return }
            expiry.removeValue(forKey: key)?.cancel()
            if let index {
                fill(index, from: call, deviceName: deviceName)
                if !model.cards[index].isActive {
                    withAnimation(.tandem) { model.cards[index].phase = .active(since: Date()) }
                }
            } else {
                insert(card(for: call, device: device, deviceName: deviceName, phase: .active(since: Date())))
            }

        case .ended:
            remove(key)

        case .missed:
            if closedHere.removeValue(forKey: key) != nil {
                remove(key)
                return
            }
            let missed = index.map { model.cards[$0] } ?? card(for: call, device: device, deviceName: deviceName, phase: .missed)
            // The card goes after a few seconds; the notification stays, so the call can be found later.
            Notifier.shared.post(id: "missed.\(call.id)", title: String(localized: "Missed call"), body: missed.title, thread: "calls")
            if let index {
                withAnimation(.tandem) { model.cards[index].phase = .missed }
            } else {
                insert(missed)
            }
            expire(key, after: debugKeep ? .seconds(600) : .seconds(4))
        }
    }

    /// A call this Mac asked a phone to place.
    func dialed(device: String, number: String, name: String?) {
        dialed = (device, number, name, Date())
    }

    private func card(for call: TandemCall, device: String, deviceName: String, phase: CallCard.Phase) -> CallCard {
        var card = CallCard(
            device: device, call: call.id, deviceName: deviceName,
            name: call.name, number: call.number, incoming: call.incoming, phase: phase
        )
        if !call.incoming, call.number == nil, let dialed, dialed.device == device, Date().timeIntervalSince(dialed.at) < 60 {
            card.number = dialed.number
            card.name = card.name ?? dialed.name
        }
        return card
    }

    private func fill(_ index: Int, from call: TandemCall, deviceName: String) {
        var card = model.cards[index]
        card.deviceName = deviceName
        if let name = call.name { card.name = name }
        if let number = call.number { card.number = number }
        if card != model.cards[index] { model.cards[index] = card }
    }

    // MARK: Buttons

    func act(_ card: CallCard, _ action: TandemCallAction) {
        onAction?(card.device, card.call, action)
        switch action {
        case .reject, .hangup, .rejectWithMessage:
            // The card goes at once, whether or not the phone could be reached: a card that cannot be closed would be worse.
            closedHere[card.id] = Date()
            closedHere = closedHere.filter { Date().timeIntervalSince($0.value) < 600 }
            remove(card.id)
        case .answer, .silence:
            break
        }
    }

    // MARK: Coming and going

    private func insert(_ card: CallCard) {
        hideTask?.cancel()
        shrinkTask?.cancel()
        let panel = panel ?? makePanel()
        self.panel = panel
        if !panel.isVisible {
            place(panel, count: model.cards.count + 1)
            panel.orderFrontRegardless()
        } else {
            resize(count: model.cards.count + 1)
        }
        // One turn later, so the window is on screen and laid out before the card springs in.
        DispatchQueue.main.async { [weak self] in
            guard let self, !self.model.cards.contains(where: { $0.id == card.id }) else { return }
            withAnimation(.tandemSpringy) { self.model.cards.append(card) }
        }
    }

    private func remove(_ key: String) {
        expiry.removeValue(forKey: key)?.cancel()
        guard model.cards.contains(where: { $0.id == key }) else { return }
        withAnimation(.tandem) { model.cards.removeAll { $0.id == key } }
        if model.cards.isEmpty {
            hideTask?.cancel()
            hideTask = Task { @MainActor [weak self] in
                try? await Task.sleep(for: .milliseconds(450))
                guard !Task.isCancelled, let self, self.model.cards.isEmpty, let panel = self.panel else { return }
                self.topLeft = NSPoint(x: panel.frame.minX, y: panel.frame.maxY)
                panel.orderOut(nil)
            }
        } else {
            // The window keeps its size until the card has faded, or the fade would be cut off.
            shrinkTask?.cancel()
            shrinkTask = Task { @MainActor [weak self] in
                try? await Task.sleep(for: .milliseconds(450))
                guard !Task.isCancelled, let self else { return }
                self.resize(count: self.model.cards.count)
            }
        }
    }

    private func expire(_ key: String, after delay: Duration) {
        expiry[key]?.cancel()
        expiry[key] = Task { @MainActor [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            self?.remove(key)
        }
    }

    // MARK: The window

    private func makePanel() -> CallPanel {
        let size = Self.size(count: 1)
        let panel = CallPanel(
            contentRect: NSRect(origin: .zero, size: size),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: true
        )
        panel.isFloatingPanel = true
        panel.level = .statusBar
        panel.isOpaque = false
        panel.backgroundColor = .clear
        // The cards draw their own shadow, which follows the glass.
        panel.hasShadow = false
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
        panel.animationBehavior = .none
        panel.title = "Call"

        let host = CallHostingView(rootView: CallStack(model: model) { [weak self] card, action in self?.act(card, action) })
        host.sizingOptions = []
        // In a plain view and not as the content view itself, for the reason InsertHUD gives: a hosting view that is the content view
        // follows its content with the window frame, and AppKit does not allow that during the window's layout.
        let container = NSView(frame: NSRect(origin: .zero, size: size))
        container.autoresizesSubviews = true
        host.frame = container.bounds
        host.autoresizingMask = [.width, .height]
        container.addSubview(host)
        panel.contentView = container
        return panel
    }

    static func size(count: Int) -> CGSize {
        let cards = CGFloat(max(1, count))
        return CGSize(
            width: cardWidth + margin * 2,
            height: cards * cardHeight + (cards - 1) * gap + margin * 2
        )
    }

    /// Top right of the screen the pointer is on, just under the menu bar, unless the cards were dragged somewhere else before.
    private func place(_ panel: NSPanel, count: Int) {
        let size = Self.size(count: count)
        if let topLeft, NSScreen.screens.contains(where: { $0.visibleFrame.insetBy(dx: -Self.margin, dy: -Self.margin).contains(NSPoint(x: topLeft.x + size.width / 2, y: topLeft.y - Self.margin - 1)) }) {
            panel.setFrame(NSRect(x: topLeft.x, y: topLeft.y - size.height, width: size.width, height: size.height), display: true)
            return
        }
        let mouse = NSEvent.mouseLocation
        let screen = NSScreen.screens.first { NSMouseInRect(mouse, $0.frame, false) } ?? NSScreen.main ?? NSScreen.screens[0]
        let area = screen.visibleFrame
        let x = area.maxX - 14 - Self.cardWidth - Self.margin
        let top = area.maxY - 10 + Self.margin
        panel.setFrame(NSRect(x: x, y: top - size.height, width: size.width, height: size.height), display: true)
    }

    /// The top edge stays where it is, wherever the cards were dragged, and the stack grows or shrinks downwards.
    private func resize(count: Int) {
        guard let panel else { return }
        let size = Self.size(count: count)
        let frame = panel.frame
        panel.setFrame(NSRect(x: frame.minX, y: frame.maxY - size.height, width: size.width, height: size.height), display: true)
    }

    // MARK: Debug

    /// `TANDEM_DEBUG_CALL=ringing|dialing|active|missed|stack|cycle`: a made up call on a made up phone, for the snapshot harness.
    func debugShow(_ stage: String) {
        debugKeep = true
        func call(_ id: String, _ state: TandemCallState, incoming: Bool = true, name: String? = "Sanne de Vries", number: String? = "+31 6 12345678") -> TandemCall {
            TandemCall(id: id, state: state, incoming: incoming, number: number, name: name, ts: 0)
        }
        let phone = "debug-phone"
        let other = "debug-phone-2"
        switch stage {
        case "dialing":
            received(call("1", .dialing, incoming: false), from: phone, deviceName: "Maruks Telefoon")
        case "active":
            received(call("1", .active), from: phone, deviceName: "Maruks Telefoon")
        case "missed":
            received(call("1", .ringing), from: phone, deviceName: "Maruks Telefoon")
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { self.received(call("1", .missed), from: phone, deviceName: "Maruks Telefoon") }
        case "stack":
            received(call("1", .active), from: phone, deviceName: "Maruks Telefoon")
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.8) {
                self.received(call("1", .ringing, name: nil, number: "+31 20 7654321"), from: other, deviceName: "Werktelefoon")
            }
        case "cycle":
            // The whole life of a call: rings, the name arrives, answered, ended.
            received(call("1", .ringing, name: nil), from: phone, deviceName: "Maruks Telefoon")
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { self.received(call("1", .ringing), from: phone, deviceName: "Maruks Telefoon") }
            DispatchQueue.main.asyncAfter(deadline: .now() + 3.0) { self.received(call("1", .active), from: phone, deviceName: "Maruks Telefoon") }
            DispatchQueue.main.asyncAfter(deadline: .now() + 9.0) { self.received(call("1", .ended), from: phone, deviceName: "Maruks Telefoon") }
        default:
            received(call("1", .ringing), from: phone, deviceName: "Maruks Telefoon")
        }
    }

    /// What is under a point of the first card, for the debug report: the drag area on the words, the buttons on the buttons.
    func debugHitReport() -> String {
        guard let panel, let container = panel.contentView, let host = container.subviews.first else { return "no window\n" }
        var lines = ["frame \(panel.frame)", "cards \(model.cards.count)"]
        let points: [(String, CGPoint)] = [
            ("name", CGPoint(x: Self.margin + 100, y: Self.margin + 30)),
            ("right button", CGPoint(x: Self.margin + Self.cardWidth - 14 - 20, y: Self.margin + Self.cardHeight / 2)),
            ("margin", CGPoint(x: 4, y: 4)),
        ]
        for (label, point) in points {
            // From the top left, as SwiftUI lays out, to the bottom left of AppKit.
            let inHost = NSPoint(x: point.x, y: host.isFlipped ? point.y : host.bounds.height - point.y)
            let hit = host.hitTest(host.convert(inHost, to: host.superview))
            lines.append("\(label): \(hit.map { String(describing: type(of: $0)) } ?? "nothing")")
        }
        return lines.joined(separator: "\n") + "\n"
    }
}

private extension CallCard {
    var isActive: Bool { if case .active = phase { true } else { false } }
}

/// A panel that can be clicked without waking the app, and that never takes the keys.
private final class CallPanel: NSPanel {
    override var canBecomeKey: Bool { false }
    override var canBecomeMain: Bool { false }
}

private final class CallHostingView: NSHostingView<CallStack> {
    // A click on Answer should answer, also while another app is in front, and not first bring this window forward.
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}

// MARK: The cards

struct CallStack: View {
    let model: CallCards
    let act: (CallCard, TandemCallAction) -> Void

    var body: some View {
        VStack(spacing: CallWindow.gap) {
            ForEach(model.cards) { card in
                CallCardView(card: card) { act(card, $0) }
                    .transition(.asymmetric(
                        insertion: .scale(scale: 0.85, anchor: .topTrailing).combined(with: .opacity),
                        removal: .opacity
                    ))
            }
        }
        .padding(CallWindow.margin)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }
}

private struct CallCardView: View {
    let card: CallCard
    let act: (TandemCallAction) -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(spacing: 10) {
            CallAvatar(name: card.name, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: card.title)
                    .font(.headline)
                    .lineLimit(1)
                    // A whole phone number fits, a long name gets a little smaller before it is cut off.
                    .minimumScaleFactor(0.8)
                status
                Text("via \(card.deviceName)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            buttons
        }
        .padding(.horizontal, 14)
        .frame(width: CallWindow.cardWidth, height: CallWindow.cardHeight)
        // Under the words and the picture, so the card can be dragged by anything that is not a button.
        .background(WindowDragArea())
        // Plain glass takes on the desktop behind it, and a light desktop under a dark card (or the other way round) washes the words
        // out. A fill in the colour of the appearance under the words keeps them readable on any wallpaper.
        .background(
            RoundedRectangle(cornerRadius: Metrics.panel, style: .continuous)
                .fill(Color(nsColor: .windowBackgroundColor).opacity(colorScheme == .dark ? 0.7 : 0.62))
        )
        .floatingGlass(in: RoundedRectangle(cornerRadius: Metrics.panel, style: .continuous))
        .shadow(color: .black.opacity(0.18), radius: 18, y: 8)
    }

    @ViewBuilder private var status: some View {
        Group {
            switch card.phase {
            case .ringing: Text("Incoming call").foregroundStyle(colorScheme == .dark ? Palette.indigoLight : Palette.indigo)
            case .dialing: Text("Calling…").foregroundStyle(.primary)
            case let .active(since): Text(since, style: .timer).monospacedDigit().foregroundStyle(.primary)
            case .missed: Text("Missed call").foregroundStyle(Color(nsColor: .systemRed))
            }
        }
        .font(.subheadline.weight(.medium))
        .lineLimit(1)
        .id(card.phase.kind)
        .transition(.opacity)
    }

    @ViewBuilder private var buttons: some View {
        HStack(spacing: 8) {
            // The place for "Answer here" (while it rings) and "Take over here" / "Back to phone" (during the call), which will carry the
            // sound of the call to this Mac once there is a Bluetooth hands-free route for it. They are left out until that works: a button
            // that does nothing is never shown.
            switch card.phase {
            case .ringing:
                RoundCallButton(label: "Decline", symbol: "phone.down.fill", color: Color(nsColor: .systemRed)) { act(.reject) }
                RoundCallButton(label: "Answer", symbol: "phone.fill", color: Color(nsColor: .systemGreen)) { act(.answer) }
            case .dialing, .active:
                RoundCallButton(label: "Hang up", symbol: "phone.down.fill", color: Color(nsColor: .systemRed)) { act(.hangup) }
            case .missed:
                EmptyView()
            }
        }
        .transition(.scale(scale: 0.8).combined(with: .opacity))
    }
}

private extension CallCard.Phase {
    var kind: Int {
        switch self {
        case .ringing: 0
        case .dialing: 1
        case .active: 2
        case .missed: 3
        }
    }
}

/// The person as a coloured disc with their initials, or a figure for a number without a name. The colour comes from the name, so the
/// same person always gets the same one.
private struct CallAvatar: View {
    let name: String?
    let size: CGFloat

    private static let colors: [UInt32] = [0x5B5BD6, 0x934464, 0x2F8F83, 0xC0642B, 0x3E7CC9, 0x7A4FB5]

    private var initials: String {
        let words = (name ?? "").split(separator: " ").filter { $0.first?.isLetter == true }
        return words.prefix(2).compactMap(\.first).map { String($0).uppercased() }.joined()
    }

    private var color: Color {
        // Not `hashValue`, which changes with every start of the app.
        let sum = (name ?? "").unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0xFFFFFF }
        return Color(hex: Self.colors[sum % Self.colors.count])
    }

    var body: some View {
        ZStack {
            Circle().fill(LinearGradient(colors: [color.opacity(0.82), color], startPoint: .top, endPoint: .bottom))
            if initials.isEmpty {
                Image(systemName: "person.fill")
                    .font(.system(size: size * 0.42, weight: .medium))
            } else {
                Text(verbatim: initials)
                    .font(.system(size: size * 0.4, weight: .semibold, design: .rounded))
            }
        }
        .foregroundStyle(.white)
        .frame(width: size, height: size)
    }
}

/// A round button on the card. On glass, so a fill of its own and not more glass.
private struct RoundCallButton: View {
    let label: LocalizedStringKey
    let symbol: String
    let color: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: 40, height: 40)
                .background(Circle().fill(color))
                .contentShape(Circle())
        }
        .buttonStyle(.bouncy)
        .help(Text(label))
        .accessibilityLabel(Text(label))
        .transition(.scale(scale: 0.6).combined(with: .opacity))
    }
}

/// Moves the window when it is dragged, under everything on the card that is not a button.
private struct WindowDragArea: NSViewRepresentable {
    func makeNSView(context: Context) -> NSView { DragView() }
    func updateNSView(_ nsView: NSView, context: Context) {}

    final class DragView: NSView {
        override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
        override var mouseDownCanMoveWindow: Bool { true }
        override func mouseDown(with event: NSEvent) { window?.performDrag(with: event) }
    }
}

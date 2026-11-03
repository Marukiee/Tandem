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
    /// Whether the sound of this call is on this Mac or stays on the phone.
    var audioOnMac = false

    /// Two phones can give the same id to a call (it is a time in milliseconds), so the phone is part of it.
    var id: String { "\(device)/\(call)" }

    var title: String { name ?? number ?? String(localized: "Unknown number") }
}

/// What one panel shows: its card, and whether the card is in, apart from whether the panel exists, so it can come and go with a spring.
@MainActor
@Observable
final class CallSlot {
    var card: CallCard
    var shown = false

    init(card: CallCard) { self.card = card }
}

/// The small floating window of a call on a phone, like the call popup of WhatsApp: who it is, which phone, and Answer and Decline while
/// it rings, Hang up while it goes on. The talking stays on the phone or its headset. One card per call, each a panel of its own in the
/// column of floating cards at the top right (`FloatingStack`). A card that is dragged leaves the column and stays where it was put.
///
/// The panels never activate the app or take the keyboard, so a call coming in does not take the cursor out of what is being typed.
@MainActor
final class CallWindow {
    static let shared = CallWindow()

    static let cardWidth: CGFloat = 520
    /// More room for labels and a clearer layout.
    static let cardHeight: CGFloat = 116
    /// A bit larger radius to match the wider card.
    static let cardRadius: CGFloat = 26
    /// Room around the card for its shadow, the same as the other floating cards (`FloatingCard`): a shadow that reaches past the edge of
    /// a window is cut off in a straight line.
    static let insets = EdgeInsets(top: 12, leading: 28, bottom: 36, trailing: 28)

    /// Set by the model: sends Answer, Decline or Hang up to the phone.
    var onAction: ((_ device: String, _ callId: String, _ action: TandemCallAction) -> Void)?

    /// Update audio routing state for a specific card.
    var onAudioChanged: ((_ device: String, _ callId: String, _ audioOnMac: Bool) -> Void)?

    /// The calls as the phones last told them, oldest first.
    private var cards: [CallCard] = []
    private var shown: [String: (panel: CallPanel, slot: CallSlot)] = [:]
    /// What takes a card away by itself: a ringing that never ended (the phone went away) or a missed call that was shown.
    private var expiry: [String: Task<Void, Never>] = [:]
    /// Calls declined or hung up here. The phone reports a declined call as missed, and that is no news to whoever declined it.
    private var closedHere: [String: Date] = [:]
    /// The number this Mac asked a phone to call. Android does not say whom it is calling, so the card takes it from here.
    private var dialed: (device: String, number: String, name: String?, at: Date)?
    private var debugKeep = false
    /// Whether the sound of the call is on this Mac or stays on the phone.
    private var audioOnMac = false

    // MARK: Audio routing updates

    /// Update a card's `audioOnMac` state when the user moves audio to/from the Mac.
    func updateCardAudio(device: String, callId: String, audioOnMac: Bool) {
        let key = "\(device)/\(callId)"
        Log.d("CallWindow", "updateCardAudio key=\(key) audioOnMac=\(audioOnMac) global=\(self.audioOnMac)")
        for slot in shown.values where slot.card.device == device && slot.call == callId {
            if slot.card.audioOnMac != audioOnMac {
                Log.d("CallWindow", "updateCardAudio card=\(slot.card.id) audioOnMac \(slot.card.audioOnMac) -> \(audioOnMac)")
            }
            slot.card.audioOnMac = audioOnMac
        }
        // Also update the global state.
        if self.audioOnMac != audioOnMac {
            Log.d("CallWindow", "updateCardAudio global audioOnMac \(self.audioOnMac) -> \(audioOnMac)")
        }
        self.audioOnMac = audioOnMac
    }

    // MARK: Permissions

    /// Check whether this Mac has the permissions to take calls from a phone.
    static var canTakeCalls: Bool {
        let key = "callCanTakeCalls"
        return UserDefaults.standard.object(forKey: key) as? Bool ?? true
    }

    /// Ask for call permissions and remember the answer.
    static func askForCallPermissions() {
        // On macOS we just toggle the setting; no system permission dialog.
        UserDefaults.standard.set(true, forKey: "callCanTakeCalls")
    }

    /// Whether the user has dismissed the call permissions message permanently.
    static var neverAskCallPermissionsAgain: Bool {
        get { UserDefaults.standard.bool(forKey: "callNeverAskPermissions") }
        set { UserDefaults.standard.set(newValue, forKey: "callNeverAskPermissions") }
    }

    // MARK: What the phone says

    func received(_ call: TandemCall, from device: String, deviceName: String) {
        defer { sync() }
        let key = "\(device)/\(call.id)"
        let index = cards.firstIndex { $0.id == key }
        switch call.state {
        case .ringing, .dialing:
            guard closedHere[key] == nil else { return }
            let phase: CallCard.Phase = call.state == .ringing && call.incoming ? .ringing : .dialing
            if let index {
                // Ringing is sent again once the phone has looked up the name: the same card, with the name.
                fill(index, from: call, deviceName: deviceName)
                if !cards[index].isActive { cards[index].phase = phase }
            } else {
                cards.append(card(for: call, device: device, deviceName: deviceName, phase: phase))
            }
            // A phone that stops ringing says so. One that is gone says nothing, and its card should not stay forever.
            expire(key, after: .seconds(120))

        case .active:
            guard closedHere[key] == nil else { return }
            expiry.removeValue(forKey: key)?.cancel()
            if let index {
                fill(index, from: call, deviceName: deviceName)
                if !cards[index].isActive { cards[index].phase = .active(since: Date()) }
            } else {
                cards.append(card(for: call, device: device, deviceName: deviceName, phase: .active(since: Date())))
            }

        case .ended:
            remove(key)

        case .missed:
            if closedHere.removeValue(forKey: key) != nil {
                remove(key)
                return
            }
            let missed = index.map { cards[$0] } ?? card(for: call, device: device, deviceName: deviceName, phase: .missed)
            // The card goes after a few seconds; the notification stays, so the call can be found later.
            Notifier.shared.post(id: "missed.\(call.id)", title: String(localized: "Missed call"), body: missed.title, thread: "calls")
            if let index {
                cards[index].phase = .missed
            } else {
                var shownMissed = missed
                shownMissed.phase = .missed
                cards.append(shownMissed)
            }
            expire(key, after: debugKeep ? .seconds(600) : .seconds(4))
        }
    }

    /// The phone went out of reach during a call: it cannot say that the call ended, so a card that counts on would be wrong. It goes
    /// after a while, unless the phone is back before that and says the call still goes on.
    func phoneReachable(_ device: String, _ reachable: Bool) {
        for card in cards where card.device == device && card.isActive {
            // Back in reach, the phone says how the call is (`CallMonitor.resend`), which keeps the card or ends it.
            if !reachable { expire(card.id, after: .seconds(30)) }
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
        cards[index].deviceName = deviceName
        if let name = call.name { cards[index].name = name }
        if let number = call.number { cards[index].number = number }
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
            sync()
        case .answer, .silence:
            break
        case .moveToThisDevice:
            if !CallWindow.canTakeCalls {
                Log.d("CallWindow", "moveToThisDevice: canTakeCalls=false, show permissions message")
                showCallPermissionsMessage()
            } else {
                Log.d("CallWindow", "moveToThisDevice: card=\(card.id) audioOnMac \(card.audioOnMac) -> true")
                card.audioOnMac = true
                onAudioChanged?(card.device, card.call, true)
            }
        case .moveBack:
            Log.d("CallWindow", "moveBack: card=\(card.id) audioOnMac \(card.audioOnMac) -> false")
            card.audioOnMac = false
            onAudioChanged?(card.device, card.call, false)
        case .muteMic:
            onAction?(card.device, card.call, action)
        }
    }

    /// Show a message about taking calls on this Mac.
    func showCallPermissionsMessage() {
        if CallWindow.neverAskCallPermissionsAgain { return }
        let body = String(localized: "You can also take calls on your laptop. Turn it on in Settings, then try again.")
        Notifier.shared.post(
            id: "call-permissions",
            title: String(localized: "Take calls on this Mac"),
            body: body,
            userInfo: ["action": "open-settings"],
            sound: false
        )
    }

    /// Ask the user to allow taking calls on this Mac.
    func askForCallPermissions() {
        CallWindow.askForCallPermissions()
        sync()
    }

    // MARK: Coming and going

    private func remove(_ key: String) {
        expiry.removeValue(forKey: key)?.cancel()
        cards.removeAll { $0.id == key }
    }

    private func expire(_ key: String, after delay: Duration) {
        expiry[key]?.cancel()
        expiry[key] = Task { @MainActor [weak self] in
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled, let self else { return }
            self.remove(key)
            self.sync()
        }
    }

    /// Makes the panels match the calls: a new call springs in at the top of the column, a changed one changes in place, a gone one fades
    /// while the others slide up into its place.
    private func sync() {
        for card in cards {
            if let existing = shown[card.id] {
                if existing.slot.card != card { withAnimation(.tandem) { existing.slot.card = card } }
            } else {
                let slot = CallSlot(card: card)
                let panel = makePanel(slot)
                shown[card.id] = (panel, slot)
                // The room the panel keeps around its card, so the column lines up the cards and not their shadows.
                FloatingStack.shared.show(panel, room: FloatingStack.Room(top: Self.insets.top, bottom: Self.insets.bottom, side: Self.insets.trailing))
                // One turn later, so the panel is on screen and laid out before the card springs in.
                DispatchQueue.main.async { withAnimation(.tandemSpringy) { slot.shown = true } }
            }
        }
        for (key, gone) in shown where !cards.contains(where: { $0.id == key }) {
            shown.removeValue(forKey: key)
            FloatingStack.shared.remove(gone.panel)
            withAnimation(.tandem) { gone.slot.shown = false }
            let panel = gone.panel
            Task { @MainActor in
                // Long enough for the fade.
                try? await Task.sleep(for: .milliseconds(450))
                panel.orderOut(nil)
            }
        }
    }

    // MARK: The window

    static var panelSize: CGSize {
        CGSize(width: cardWidth + insets.leading + insets.trailing, height: cardHeight + insets.top + insets.bottom)
    }

    private func makePanel(_ slot: CallSlot) -> CallPanel {
        let size = Self.panelSize
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
        // The card draws its own shadow, which follows the glass.
        panel.hasShadow = false
        panel.hidesOnDeactivate = false
        panel.isReleasedWhenClosed = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .ignoresCycle]
        panel.animationBehavior = .none
        panel.title = "Call"

        let root = CallSlotView(
            slot: slot,
            act: { [weak self] card, action in self?.act(card, action) },
            // Put somewhere by hand: out of the column, so the column does not move it back.
            onDragged: { [weak panel] in if let panel { FloatingStack.shared.remove(panel) } }
        )
        let host = CallHostingView(rootView: root)
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

    /// Where the panels are and what is under a point of each card, for the debug report: the drag area on the words, the buttons on the
    /// buttons.
    func debugHitReport() -> String {
        var lines: [String] = []
        for card in cards {
            guard let panel = shown[card.id]?.panel, let host = panel.contentView?.subviews.first else { continue }
            lines.append("\(card.id) phase \(card.phase.kind) frame \(panel.frame) visible \(panel.isVisible)")
            let points: [(String, CGPoint)] = [
                ("name", CGPoint(x: Self.insets.leading + 100, y: Self.insets.top + 24)),
                ("avatar", CGPoint(x: Self.insets.leading + 38, y: Self.insets.top + Self.cardHeight / 2)),
                ("top edge", CGPoint(x: Self.insets.leading + 180, y: Self.insets.top + 6)),
                ("between", CGPoint(x: Self.insets.leading + 190, y: Self.insets.top + Self.cardHeight / 2)),
                ("right button", CGPoint(x: Self.insets.leading + Self.cardWidth - 16 - 22, y: Self.insets.top + Self.cardHeight / 2)),
                ("margin", CGPoint(x: 4, y: 4)),
            ]
            for (label, point) in points {
                // From the top left, as SwiftUI lays out, to the bottom left of AppKit.
                let inHost = NSPoint(x: point.x, y: host.isFlipped ? point.y : host.bounds.height - point.y)
                let hit = host.hitTest(host.convert(inHost, to: host.superview))
                lines.append("  \(label): \(hit.map { String(describing: type(of: $0)) } ?? "nothing")")
            }
        }
        return (lines.isEmpty ? ["no calls"] : lines).joined(separator: "\n") + "\n"
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

private final class CallHostingView: NSHostingView<CallSlotView> {
    // A click on Answer should answer, also while another app is in front, and not first bring this window forward.
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}

// MARK: The card

struct CallSlotView: View {
    let slot: CallSlot
    let act: (CallCard, TandemCallAction) -> Void
    let onDragged: () -> Void

    var body: some View {
        ZStack(alignment: .top) {
            if slot.shown {
                CallCardView(card: slot.card, onDragged: onDragged) { act(slot.card, $0) }
                    .transition(.asymmetric(
                        insertion: .scale(scale: 0.85, anchor: .topTrailing).combined(with: .opacity),
                        removal: .opacity
                    ))
            }
        }
        .padding(CallWindow.insets)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }
}

private struct CallCardView: View {
    let card: CallCard
    let onDragged: () -> Void
    let act: (TandemCallAction) -> Void
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        HStack(spacing: 16) {
            // The picture and the words let the mouse through to the drag area under them; otherwise they would swallow the drag.
            CallAvatar(name: card.name, size: 52)
                .allowsHitTesting(false)
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: card.title)
                    // A whole phone number fits, a long name gets a little smaller before it is cut off.
                    .font(.headline)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
                status
                Text("via \(card.deviceName)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .allowsHitTesting(false)
            buttons
        }
        .padding(.horizontal, 20)
        .frame(width: CallWindow.cardWidth, height: CallWindow.cardHeight)
        // Under the words and the picture, so the card can be dragged by anything that is not a button.
        .background(WindowDragArea(onDragged: onDragged))
        // Plain glass takes on the desktop behind it, and a light desktop under a dark card (or the other way round) washes the words
        // out. A fill in the colour of the appearance under the words keeps them readable on any wallpaper.
        .background(
            RoundedRectangle(cornerRadius: CallWindow.cardRadius, style: .continuous)
                .fill(Color(nsColor: .windowBackgroundColor).opacity(colorScheme == .dark ? 0.7 : 0.62))
        )
        .floatingGlass(in: RoundedRectangle(cornerRadius: CallWindow.cardRadius, style: .continuous))
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
        HStack(spacing: 12) {
            switch card.phase {
            case .ringing:
                CallActionButton(label: "Decline", symbol: "phone.down.fill", color: Color(nsColor: .systemRed)) { act(.reject) }
                if CallWindow.canTakeCalls {
                    CallActionButton(label: "Answer here", symbol: "phone.fill", color: Color(nsColor: .systemGreen)) { act(.answer) }
                } else {
                    CallActionButton(label: "Answer on phone", symbol: "phone.fill", color: Color(nsColor: .systemGreen)) { act(.answer) }
                }
            case .dialing, .active:
                CallActionButton(label: "Hang up", symbol: "phone.down.fill", color: Color(nsColor: .systemRed)) { act(.hangup) }
                // Move the call sound to this Mac or back to the phone.
                if CallWindow.canTakeCalls && !card.audioOnMac {
                    CallActionButton(label: "Move here", symbol: "arrow.right.circle", color: Color(nsColor: .controlAccentColor)) { act(.moveToThisDevice) }
                } else if card.audioOnMac {
                    CallActionButton(label: "Back to phone", symbol: "arrow.left.circle", color: Color(nsColor: .controlAccentColor)) { act(.moveBack) }
                }
                // Mute the microphone on this device.
                CallActionButton(label: "Mute mic", symbol: "mic.fill", color: Color(nsColor: .systemGray)) { act(.muteMic) }
            case .missed:
                EmptyView()
            }
        }
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
/// same person always gets the same one (on the other computers too: `callcard.js` does the same sum).
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

/// A button on the card with an icon and a visible label below it.
private struct CallActionButton: View {
    let label: LocalizedStringKey
    let symbol: String
    let color: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 6) {
                Image(systemName: symbol)
                    .font(.system(size: 20, weight: .semibold))
                    .foregroundStyle(.white)
                Text(label)
                    .font(.system(size: 11, weight: .medium))
                    .foregroundStyle(.white.opacity(0.95))
            }
            .frame(minWidth: 84, maxWidth: 96, minHeight: 68, maxHeight: 68)
            .padding(.vertical, 8)
            .padding(.horizontal, 6)
            .background(Capsule().fill(color))
        }
        .buttonStyle(.bouncy)
        .focusEffectDisabled()
        .accessibilityLabel(Text(label))
        .transition(.scale(scale: 0.7).combined(with: .opacity))
    }
}

/// Moves the window when it is dragged, under everything on the card that is not a button. By hand and not with `performDrag`, so it is
/// known when the card was moved and it can leave the column.
private struct WindowDragArea: NSViewRepresentable {
    let onDragged: () -> Void

    func makeNSView(context: Context) -> DragView { DragView() }
    func updateNSView(_ nsView: DragView, context: Context) { nsView.onDragged = onDragged }

    final class DragView: NSView {
        var onDragged: (() -> Void)?
        private var grab: NSPoint?
        private var moved = false

        override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

        override func mouseDown(with event: NSEvent) {
            grab = NSEvent.mouseLocation
            moved = false
        }

        override func mouseDragged(with event: NSEvent) {
            guard let window, let grab else { return }
            let now = NSEvent.mouseLocation
            let step = NSPoint(x: now.x - grab.x, y: now.y - grab.y)
            // A click that wobbles a pixel is not a drag.
            if !moved, abs(step.x) + abs(step.y) < 3 { return }
            if !moved {
                moved = true
                onDragged?()
            }
            window.setFrameOrigin(NSPoint(x: window.frame.minX + step.x, y: window.frame.minY + step.y))
            self.grab = now
        }

        override func mouseUp(with event: NSEvent) {
            grab = nil
        }
    }
}

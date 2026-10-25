import AppKit
import SwiftUI
import TandemCore

/// The text messages of a phone, the way Messages shows those of an iPhone: the conversations on the left, the one that is open
/// on the right, and a field at the bottom to write in. Sending goes out from the phone, as an ordinary SMS.
///
/// The page fills the window from the top: the way back, the search and the open conversation's name sit in the strip of the
/// toolbar, as in Messages. A header row of its own under the toolbar left an empty band above the list.
struct MessagesView: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    let onBack: () -> Void

    @LocalState private var selection: UInt64?
    @LocalState private var composing = false
    @LocalState private var search = ""

    private var phone: PhoneMessages { MessagesStore.shared.phone(device.id) }

    var body: some View {
        let phone = phone
        Group {
            if phone.conversations.isEmpty && phone.load != .ready && !composing {
                MessagesNotice(phone: phone, device: device, onBack: onBack)
            } else {
                HStack(spacing: 0) {
                    ConversationList(phone: phone, search: $search, selection: $selection, composing: $composing, onBack: onBack)
                        .frame(minWidth: 250, idealWidth: 300, maxWidth: 330)
                    detail(phone)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                        .background {
                            // The conversation is a sheet of paper from the top edge down, the list beside it stays on the window.
                            Rectangle()
                                .fill(MessagesColor.transcript)
                                .overlay(alignment: .leading) { Rectangle().fill(.separator).frame(width: 1) }
                                .ignoresSafeArea()
                        }
                }
            }
        }
        .task(id: "\(device.id)-\(device.online)") {
            if device.online {
                await phone.refresh()
            } else if phone.load != .ready {
                phone.load = .refused(.offline)
            }
        }
        .onAppear {
            if DebugSupport.messagesStart == "compose" { composing = true }
        }
        .onChange(of: phone.conversations.first?.thread, initial: true) { _, first in
            // A wide window has room for the newest conversation from the start, as Messages does.
            if selection == nil, !composing, let first { selection = DebugSupport.messagesThread ?? first }
        }
        .onChange(of: phone.wanted, initial: true) { _, wanted in
            guard let wanted else { return }
            phone.wanted = nil
            composing = false
            selection = wanted
        }
        .onChange(of: selection) { _, thread in
            phone.open = thread
            if let thread {
                composing = false
                Task { await phone.openThread(thread) }
            }
        }
        .onDisappear { phone.open = nil }
    }

    /// The phone places the call itself; the talking happens on the phone or its headset, since call audio does not reach a computer.
    private func call(_ number: String) {
        // What stands in the way is said when someone asks for a call, not shown on the page all the time.
        if let calls = device.features.first(where: { $0.id == "calls" }), !calls.ready {
            model.showToast(calls.why)
            return
        }
        guard let engine = model.tandem, device.online else {
            model.showToast(String(localized: "That device is not connected right now"))
            return
        }
        Task {
            do {
                try await engine.dial(target: device.id, number: number)
                model.showToast(String(localized: "Calling from \(device.name)"))
            } catch {
                model.showToast(String(localized: "That device is not connected right now"))
            }
        }
    }

    @ViewBuilder
    private func detail(_ phone: PhoneMessages) -> some View {
        if composing {
            NewMessageView(phone: phone, device: device) { thread in
                composing = false
                if let thread { selection = thread }
            }
            .transition(.opacity)
        } else if let thread = selection, let conversation = phone.conversation(thread) {
            ConversationView(phone: phone, conversation: conversation, onCall: MessagesText.dialable(conversation).map { number in { call(number) } })
                .id(thread)
                .transition(.opacity)
        } else {
            NothingChosen(hasConversations: !phone.conversations.isEmpty) {
                selection = nil
                composing = true
            }
        }
    }
}

// MARK: The list

private struct ConversationList: View {
    let phone: PhoneMessages
    @Binding var search: String
    @Binding var selection: UInt64?
    @Binding var composing: Bool
    let onBack: () -> Void
    /// One pill that moves from row to row, as in the sidebar.
    @Namespace private var pill

    private var shown: [TandemSmsConversation] {
        let query = search.trimmingCharacters(in: .whitespaces)
        guard !query.isEmpty else { return phone.conversations }
        return phone.conversations.filter { conversation in
            conversation.name.localizedCaseInsensitiveContains(query)
                || conversation.snippet.localizedCaseInsensitiveContains(query)
                || conversation.addresses.contains { $0.contains(query) }
        }
    }

    var body: some View {
        let shown = shown
        UnderToolbar(avoidsWindowButtons: true) {
            // Spacing 0: pieces of glass side by side, not one that flows into the other.
            GlassEffectContainer(spacing: 0) {
                HStack(spacing: 8) {
                    Button(action: onBack) {
                        Image(systemName: "chevron.left").font(.system(size: 14, weight: .semibold))
                    }
                    .buttonStyle(GlassCircleButtonStyle(size: 30))
                    .help("Back to the device")
                    PageSearchField(text: $search)
                    Button {
                        selection = nil
                        composing = true
                    } label: {
                        Image(systemName: "square.and.pencil")
                            .font(.system(size: 14, weight: .semibold))
                            .offset(y: -1)
                    }
                    .buttonStyle(GlassCircleButtonStyle(size: 30, active: composing))
                    .help("New message")
                }
            }
            .padding(.horizontal, 12)
        } content: {
            ScrollView {
                LazyVStack(spacing: 2) {
                    ForEach(shown, id: \.thread) { conversation in
                        Button {
                            selection = conversation.thread
                        } label: {
                            ConversationRow(
                                conversation: conversation,
                                unread: phone.unread(conversation),
                                selected: selection == conversation.thread && !composing,
                                pill: pill
                            )
                        }
                        .buttonStyle(.plain)
                    }
                    if shown.isEmpty && !search.isEmpty {
                        Text("No conversations found")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .padding(.top, 30)
                    }
                    if phone.moreConversations && search.isEmpty {
                        PillSpinner(size: 18)
                            .padding(10)
                            .onAppear { Task { await phone.loadMoreConversations() } }
                    }
                }
                .padding(.horizontal, 8)
                .padding(.top, 2)
                .padding(.bottom, 10)
                .animation(.tandem, value: shown.map(\.thread))
                .animation(.tandem, value: selection)
                .animation(.tandem, value: composing)
            }
            .scrollContentBackground(.hidden)
        }
    }
}

/// A column whose top row sits in the strip of the toolbar, with the rest under it. That strip is empty in this window (the title is
/// gone and the window's buttons are in the sidebar), and a click there reaches the page. With the sidebar hidden the window's own
/// buttons float over the top left corner of the page, so a column that starts there moves its row out of their way.
private struct UnderToolbar<Strip: View, Content: View>: View {
    var avoidsWindowButtons = false
    @ViewBuilder var strip: Strip
    @ViewBuilder var content: Content

    var body: some View {
        GeometryReader { proxy in
            let band = max(proxy.safeAreaInsets.top, 52)
            let clear: CGFloat = avoidsWindowButtons && proxy.frame(in: .global).minX < 40 ? 120 : 0
            VStack(spacing: 0) {
                // In front: under a toolbar a scroll view below reaches up to the top of the window, and would take the clicks
                // meant for the row.
                strip
                    .padding(.leading, clear)
                    .frame(maxWidth: .infinity)
                    .frame(height: band)
                    .zIndex(1)
                // Clipped, so what scrolls up stops at the row instead of running on under it.
                VStack(spacing: 0) { content }
                    .clipped()
            }
            .ignoresSafeArea(.container, edges: .top)
        }
    }
}

private struct ConversationRow: View {
    let conversation: TandemSmsConversation
    let unread: Bool
    let selected: Bool
    let pill: Namespace.ID
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
        HStack(alignment: .center, spacing: 0) {
            Circle()
                .fill(Palette.indigo)
                .frame(width: 8, height: 8)
                .opacity(unread ? 1 : 0)
                .frame(width: 16)
            Avatar(conversation: conversation, size: 40)
            VStack(alignment: .leading, spacing: 1) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(verbatim: MessagesText.title(of: conversation))
                        .font(.body.weight(.semibold))
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    Text(verbatim: MessagesText.listTime(conversation.date))
                        .font(.callout)
                        .foregroundStyle(unread ? AnyShapeStyle(Palette.indigo) : AnyShapeStyle(.secondary))
                }
                Text(verbatim: conversation.fromMe ? String(localized: "You: \(conversation.snippet)") : conversation.snippet)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .lineLimit(2, reservesSpace: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.leading, 10)
        }
        .padding(.vertical, 8)
        .padding(.trailing, 10)
        .background {
            ZStack {
                shape.fill(Color.primary.opacity(hovering && hoverEnabled && !selected ? hoverOpacity : 0))
                if selected {
                    shape.fill(Palette.indigo.opacity(0.16)).matchedGeometryEffect(id: "selection", in: pill)
                }
            }
        }
        .contentShape(shape)
        .onHover { hovering = $0 }
        .animation(.tandemFade, value: hovering)
        .animation(.tandemFade, value: unread)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// A round button of glass, the size of the search field beside it.
private struct GlassCircleButtonStyle: ButtonStyle {
    let size: CGFloat
    var active = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(active ? AnyShapeStyle(Palette.indigo) : AnyShapeStyle(.primary))
            .frame(width: size, height: size)
            .contentShape(.circle)
            .messagesGlass(in: Circle())
            .scaleEffect(configuration.isPressed ? pressedScale : 1)
            .animation(.tandemBouncy, value: configuration.isPressed)
            .animation(.tandemFade, value: active)
    }
}

// MARK: Faces

/// A person as Messages shows someone without a photo: their initials on a colour of their own, a figure only for a number
/// without a name. The colour comes from the name, so it is the same every time and on every computer (see `messages.js`).
private struct Avatar: View {
    let name: String
    let address: String
    var group = false
    let size: CGFloat

    init(conversation: TandemSmsConversation, size: CGFloat) {
        name = conversation.name
        address = conversation.addresses.first ?? ""
        group = conversation.addresses.count > 1
        self.size = size
    }

    init(name: String, address: String, size: CGFloat) {
        self.name = name
        self.address = address
        self.size = size
    }

    var body: some View {
        let letters = MessagesText.initials(name: name, address: address)
        let colors = MessagesColor.avatar(for: name.isEmpty ? address : name)
        ZStack {
            Circle().fill(LinearGradient(colors: letters.isEmpty ? MessagesColor.unnamed : colors, startPoint: .top, endPoint: .bottom))
            if letters.isEmpty {
                Image(systemName: group ? "person.2.fill" : "person.fill")
                    .font(.system(size: size * (group ? 0.36 : 0.44), weight: .medium))
                    .foregroundStyle(.white)
                    .offset(y: size * 0.02)
            } else {
                Text(verbatim: letters)
                    .font(.system(size: size * (letters.count > 1 ? 0.38 : 0.44), weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
            }
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

enum MessagesColor {
    /// From top to bottom, light to deep. The same eight, in the same order, as `AVATAR_COLORS` in `messages.js`.
    private static let avatars: [(UInt32, UInt32)] = [
        (0x8A8AF0, 0x5B5BD6), (0xF59BBE, 0xD9628F), (0xF8B26E, 0xE07B35), (0x62CEC4, 0x2C9F96),
        (0x86D27F, 0x4CA646), (0x72B6F7, 0x3A86DC), (0xBC93F2, 0x8B5CD6), (0xF39A86, 0xD95F49),
    ]

    static let unnamed = [Color(white: 0.70), Color(white: 0.56)]

    static func avatar(for key: String) -> [Color] {
        let pair = avatars[Int(MessagesText.colorIndex(key, count: avatars.count))]
        return [Color(hex: pair.0), Color(hex: pair.1)]
    }

    /// The paper the conversation is on: white, or the dark of a text view, as in Messages.
    static let transcript = Color(nsColor: .textBackgroundColor)

    /// A received bubble: the grey of Messages, solid, so the tail that overlaps it does not show as a darker spot.
    static let received = Color(nsColor: NSColor(name: nil) { appearance in
        appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua
            ? NSColor(srgbRed: 0x3B / 255, green: 0x3B / 255, blue: 0x3D / 255, alpha: 1)
            : NSColor(srgbRed: 0xE9 / 255, green: 0xE9 / 255, blue: 0xEB / 255, alpha: 1)
    })
}

extension View {
    /// Glass on what floats over the conversation: the field and the buttons. A debug run with `TANDEM_DEBUG_FLAT_GLASS` draws a
    /// material instead, because a snapshot cannot draw glass.
    @ViewBuilder
    fileprivate func messagesGlass<S: Shape>(in shape: S, interactive: Bool = true) -> some View {
        if DebugSupport.flatGlass {
            background(.regularMaterial, in: shape)
        } else {
            glassEffect(interactive ? .regular.interactive() : .regular, in: shape)
        }
    }
}

// MARK: A conversation

private struct ConversationView: View {
    let phone: PhoneMessages
    let conversation: TandemSmsConversation
    /// Has the phone call the person; nil for a group or a sender without a number.
    let onCall: (() -> Void)?

    @LocalState private var draft = ""
    /// Said once the person tried to write in a group, which only the phone can answer.
    @LocalState private var groupNote = false
    @FocusState private var writing: Bool

    private var group: Bool { conversation.addresses.count > 1 }

    var body: some View {
        let state = phone.threads[conversation.thread]
        let lines = Transcript.lines(phone: phone, conversation: conversation)
        UnderToolbar {
            header
        } content: {
            GeometryReader { proxy in
                ScrollViewReader { reader in
                    ScrollView {
                        VStack(spacing: 0) {
                            if state?.more ?? false, !(state?.messages.isEmpty ?? true) {
                                PillSpinner(size: 18)
                                    .padding(12)
                                    .onAppear { Task { await phone.loadOlder(conversation.thread) } }
                            }
                            if lines.isEmpty {
                                Spacer(minLength: 0)
                                EmptyConversation(conversation: conversation, loading: state.map(\.loading) ?? true, failed: state?.failed)
                                Spacer(minLength: 0)
                            } else {
                                // Short conversations stand at the bottom, by the field, as in Messages.
                                Spacer(minLength: 0)
                                LazyVStack(spacing: 0) {
                                    ForEach(lines) { line in
                                        row(line)
                                            .padding(.top, line.spacing)
                                    }
                                }
                            }
                            Color.clear.frame(height: 1).id(Transcript.bottom)
                        }
                        .padding(.horizontal, 16)
                        .padding(.top, 4)
                        .padding(.bottom, 10)
                        .frame(minHeight: proxy.size.height, alignment: .bottom)
                    }
                    .defaultScrollAnchor(.bottom)
                    .defaultScrollAnchor(.bottom, for: .sizeChanges)
                    .scrollContentBackground(.hidden)
                    .onAppear { reader.scrollTo(Transcript.bottom, anchor: .bottom) }
                    .onChange(of: lines.last?.id) {
                        withAnimation(.tandem) { reader.scrollTo(Transcript.bottom, anchor: .bottom) }
                    }
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                VStack(spacing: 8) {
                    if groupNote {
                        Text("A group conversation can only be answered on the phone for now.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .messagesGlass(in: Capsule(), interactive: false)
                            .transition(.opacity.combined(with: .move(edge: .bottom)))
                    }
                    Composer(text: $draft, placeholder: "Text Message", focused: $writing, onSend: send)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 14)
                .padding(.top, 6)
                .animation(.tandem, value: groupNote)
            }
        }
        .onAppear { writing = true }
    }

    /// Who it is with, in the middle of the strip, and the call button at its end, in line with the edge of the field below.
    private var header: some View {
        HStack(spacing: 8) {
            Avatar(conversation: conversation, size: 26)
            VStack(alignment: .leading, spacing: 0) {
                Text(verbatim: MessagesText.title(of: conversation)).font(.headline).lineLimit(1)
                if !conversation.name.isEmpty {
                    Text(verbatim: conversation.addresses.joined(separator: ", ")).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
        }
        .padding(.horizontal, 62)
        .frame(maxWidth: .infinity)
        .overlay(alignment: .trailing) {
            if let onCall {
                Button(action: onCall) {
                    Image(systemName: "phone").font(.system(size: 14, weight: .semibold))
                }
                .buttonStyle(GlassCircleButtonStyle(size: 30))
                .help(String(localized: "Call \(MessagesText.title(of: conversation))"))
                .padding(.trailing, 16)
            }
        }
    }

    @ViewBuilder
    private func row(_ line: Transcript.Line) -> some View {
        switch line.kind {
        case let .time(date):
            Text(MessagesText.timeLine(date))
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
                .padding(.bottom, 4)
        case let .message(message):
            Bubble(
                text: message.body,
                mine: line.mine,
                pictures: message.pictures,
                phone: phone,
                sender: group && !line.mine && !line.joinsPrevious ? MessagesText.sender(message.address, in: conversation) : nil,
                note: message.status == .failed ? String(localized: "Not sent") : nil,
                time: line.date,
                tail: line.endsRun
            )
        case let .pending(pending):
            VStack(alignment: .trailing, spacing: 4) {
                Bubble(text: pending.text, mine: true, pictures: [], phone: phone, sender: nil, note: nil, time: pending.date, tail: line.endsRun)
                    .opacity(pending.state == .sending ? 0.6 : 1)
                switch pending.state {
                case .sending:
                    Text("Sending").font(.caption2).foregroundStyle(.secondary).padding(.trailing, 6)
                case .sent:
                    EmptyView()
                case let .failed(reason):
                    HStack(spacing: 8) {
                        Label(reason, systemImage: "exclamationmark.circle.fill")
                            .font(.caption)
                            .foregroundStyle(Palette.urgent)
                            .lineLimit(2)
                        Button("Try again") { phone.retry(pending) }.buttonStyle(.tandemGlass).controlSize(.small)
                        Button("Remove") { phone.discard(pending) }.buttonStyle(.tandemGlass).controlSize(.small)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .trailing)
        }
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        if group {
            groupNote = true
            return
        }
        draft = ""
        phone.send(text, in: conversation)
    }
}

/// The lines of a conversation, oldest first: the messages, what is still on its way, and a line with the time above each new day
/// or after a long silence. Messages of one side that follow each other closely form a run that stands close together, and only
/// the last one of a run has a tail.
private enum Transcript {
    static let bottom = "bottom"

    struct Line: Identifiable {
        enum Kind {
            case time(Date)
            case message(TandemSmsMessage)
            case pending(PhoneMessages.Pending)
        }

        let kind: Kind
        let id: String
        let date: Date
        let mine: Bool
        let sender: String
        var joinsPrevious = false
        var endsRun = true

        var spacing: CGFloat {
            if case .time = kind { return 14 }
            return joinsPrevious ? 2 : 10
        }
    }

    @MainActor static func lines(phone: PhoneMessages, conversation: TandemSmsConversation) -> [Line] {
        var items: [Line] = (phone.threads[conversation.thread]?.messages ?? []).map { message in
            Line(kind: .message(message), id: PhoneMessages.key(message), date: Date(timeIntervalSince1970: Double(message.date) / 1000),
                 mine: message.status != .received, sender: message.status == .received ? message.address : "")
        }
        items += phone.pending.filter { $0.thread == conversation.thread }.map { item in
            Line(kind: .pending(item), id: item.id.uuidString, date: item.date, mine: true, sender: "")
        }
        items.sort { $0.date < $1.date }

        var out: [Line] = []
        var previous: Line?
        let calendar = Calendar.current
        for var item in items {
            let gap = previous.map { item.date.timeIntervalSince($0.date) } ?? .infinity
            if previous.map({ !calendar.isDate($0.date, inSameDayAs: item.date) }) ?? true || gap > 3600 {
                out.append(Line(kind: .time(item.date), id: "time-\(item.id)", date: item.date, mine: false, sender: ""))
                previous = nil
            }
            if let before = previous, before.mine == item.mine, before.sender == item.sender, gap < 600 {
                item.joinsPrevious = true
                out[out.count - 1].endsRun = false
            }
            out.append(item)
            previous = item
        }
        return out
    }
}

private struct Bubble: View {
    let text: String
    let mine: Bool
    let pictures: [UInt64]
    let phone: PhoneMessages
    let sender: String?
    let note: String?
    let time: Date
    let tail: Bool

    var body: some View {
        HStack(spacing: 0) {
            if mine { Spacer(minLength: 64) }
            VStack(alignment: mine ? .trailing : .leading, spacing: 3) {
                if let sender {
                    Text(verbatim: sender).font(.caption).foregroundStyle(.secondary).padding(.horizontal, 12)
                }
                ForEach(pictures, id: \.self) { part in picture(part) }
                if !text.isEmpty || pictures.isEmpty {
                    Text(verbatim: text.isEmpty ? String(localized: "Message without text") : text)
                        .font(.system(size: 13.5))
                        .italic(text.isEmpty)
                        .textSelection(.enabled)
                        .foregroundStyle(mine ? Color.white : Color.primary)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background {
                            BubbleShape(tail: tail)
                                .fill(mine ? Palette.indigo : MessagesColor.received)
                                .scaleEffect(x: mine ? 1 : -1)
                        }
                }
                if let note {
                    Label(note, systemImage: "exclamationmark.circle.fill").font(.caption).foregroundStyle(Palette.urgent).padding(.horizontal, 6)
                }
            }
            .help(time.formatted(date: .abbreviated, time: .shortened))
            // A long text in a wide window wraps at a width that still reads as a message, not a line across the screen.
            .frame(maxWidth: 520, alignment: mine ? .trailing : .leading)
            if !mine { Spacer(minLength: 64) }
        }
    }

    @ViewBuilder
    private func picture(_ part: UInt64) -> some View {
        if let image = phone.pictures[part] {
            Image(nsImage: image)
                .resizable()
                .scaledToFit()
                .frame(maxWidth: 260, maxHeight: 260)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        } else {
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(MessagesColor.received)
                .frame(width: 180, height: 130)
                .overlay { Image(systemName: "photo").font(.title2).foregroundStyle(.secondary) }
                .onAppear { phone.picture(part) }
        }
    }
}

/// A bubble with the tail of Messages at the bottom right; mirrored for a message that came in.
private struct BubbleShape: Shape {
    let tail: Bool

    func path(in rect: CGRect) -> Path {
        let radius = min(17, rect.height / 2)
        let body = Path(roundedRect: rect, cornerRadius: radius, style: .continuous)
        guard tail else { return body }
        let right = rect.maxX, bottom = rect.maxY
        var hook = Path()
        // From inside the body down and out to a point past the corner, and back along the bottom: the curl of the tail.
        hook.move(to: CGPoint(x: right - 7, y: bottom - 18))
        hook.addCurve(to: CGPoint(x: right + 6, y: bottom), control1: CGPoint(x: right - 5, y: bottom - 6), control2: CGPoint(x: right + 1, y: bottom - 0.5))
        hook.addCurve(to: CGPoint(x: right - 13, y: bottom - 3), control1: CGPoint(x: right - 3, y: bottom + 1), control2: CGPoint(x: right - 9, y: bottom - 0.5))
        hook.closeSubpath()
        return body.union(hook)
    }
}

/// A conversation with nothing in it yet: who it is with, and what to do.
private struct EmptyConversation: View {
    let conversation: TandemSmsConversation
    let loading: Bool
    let failed: String?

    var body: some View {
        VStack(spacing: 10) {
            if loading {
                PillSpinner(size: 26)
            } else {
                Avatar(conversation: conversation, size: 64)
                Text(verbatim: MessagesText.title(of: conversation)).font(.title3.weight(.semibold)).multilineTextAlignment(.center)
                if let failed {
                    Text(verbatim: failed).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
                } else {
                    Text("No messages yet. Write the first one below.").font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
                }
            }
        }
        .frame(maxWidth: 320)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 30)
    }
}

/// The right side before a conversation is chosen.
private struct NothingChosen: View {
    let hasConversations: Bool
    let onNew: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "bubble.left.and.bubble.right.fill")
                .font(.system(size: 26, weight: .semibold))
                .foregroundStyle(Palette.indigo)
                .frame(width: 64, height: 64)
                .background(Palette.indigo.opacity(0.14), in: .circle)
            Text(hasConversations ? "Choose a conversation" : "No messages yet").font(.title3.weight(.semibold))
            Text("Pick one on the left, or start a new one.").font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
            Button(action: onNew) {
                Label("New message", systemImage: "square.and.pencil")
            }
            .buttonStyle(.tandemGlassProminent)
            .controlSize(.large)
            .padding(.top, 4)
        }
        .frame(maxWidth: 340)
    }
}

/// The field at the bottom with its send button inside, floating over the conversation. Return sends, Shift-Return starts a new line.
private struct Composer: View {
    @Binding var text: String
    let placeholder: LocalizedStringKey
    var enabled = true
    var focused: FocusState<Bool>.Binding
    let onSend: () -> Void

    @LocalState private var selection: TextSelection?

    private var empty: Bool { text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        HStack(alignment: .bottom, spacing: 6) {
            TextField(placeholder, text: $text, selection: $selection, axis: .vertical)
                .textFieldStyle(.plain)
                .lineLimit(1...6)
                .font(.system(size: 13.5))
                .focused(focused)
                .disabled(!enabled)
                .padding(.vertical, 8)
                .padding(.leading, 14)
                .onKeyPress(.return, phases: .down) { press in
                    if press.modifiers.contains(.shift) || press.modifiers.contains(.option) {
                        newLine()
                    } else {
                        onSend()
                    }
                    return .handled
                }
                .onSubmit(onSend)
            Button(action: onSend) {
                Image(systemName: "arrow.up")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white)
                    .frame(width: 26, height: 26)
                    .background(Circle().fill(empty || !enabled ? Color.primary.opacity(0.16) : Palette.indigo))
                    .contentShape(.circle)
            }
            .buttonStyle(.bouncy)
            .disabled(empty || !enabled)
            .help("Send")
            .padding(.trailing, 5)
            .padding(.bottom, 5)
            .animation(.tandemFade, value: empty)
        }
        .frame(minHeight: 36)
        .contentShape(RoundedRectangle(cornerRadius: 18, style: .circular))
        .onTapGesture { focused.wrappedValue = true }
        .messagesGlass(in: RoundedRectangle(cornerRadius: 18, style: .circular), interactive: false)
        .overlay {
            RoundedRectangle(cornerRadius: 18, style: .circular)
                .strokeBorder(Color.primary.opacity(focused.wrappedValue ? 0.16 : 0.08), lineWidth: 1)
                .allowsHitTesting(false)
        }
        .animation(.tandemFade, value: focused.wrappedValue)
    }

    /// A line break where the cursor is, in place of what is selected.
    private func newLine() {
        guard case let .selection(range)? = selection?.indices else {
            text += "\n"
            return
        }
        let offset = text.distance(from: text.startIndex, to: range.lowerBound)
        text.replaceSubrange(range, with: "\n")
        selection = TextSelection(insertionPoint: text.index(text.startIndex, offsetBy: offset + 1))
    }
}

// MARK: A new message

private struct NewMessageView: View {
    let phone: PhoneMessages
    let device: TandemDevice
    let onDone: (UInt64?) -> Void

    @LocalState private var query = ""
    @LocalState private var recipient: TandemSmsContact?
    @LocalState private var suggestions: [TandemSmsContact] = []
    @LocalState private var draft = ""
    @LocalState private var sending = false
    @LocalState private var problem: String?
    @FocusState private var writing: Bool
    @FocusState private var choosing: Bool

    /// What was typed, when it is a number that can be sent to as it is.
    private var typedNumber: String? {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        let allowed = CharacterSet(charactersIn: "+0123456789 -()")
        guard trimmed.unicodeScalars.allSatisfy(allowed.contains), trimmed.filter(\.isNumber).count >= 3 else { return nil }
        return trimmed
    }

    private var number: String? { recipient?.number ?? typedNumber }

    var body: some View {
        UnderToolbar {
            Text("New message").font(.headline)
        } content: {
            HStack(spacing: 8) {
                Text("To:").foregroundStyle(.secondary)
                if let recipient {
                    HStack(spacing: 4) {
                        Text(verbatim: recipient.name.isEmpty ? recipient.number : recipient.name)
                        Button { self.recipient = nil } label: { Image(systemName: "xmark.circle.fill") }
                            .buttonStyle(.plain)
                            .foregroundStyle(.secondary)
                            .help("Someone else")
                    }
                    .padding(.horizontal, 10)
                    .padding(.vertical, 3)
                    .background(Palette.indigo.opacity(0.16), in: .capsule)
                } else {
                    TextField("Name or number", text: $query)
                        .textFieldStyle(.plain)
                        .focused($choosing)
                        .onSubmit { if typedNumber != nil { writing = true } }
                }
                Spacer(minLength: 0)
            }
            .font(.body)
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            Divider().opacity(0.6).padding(.horizontal, 12)

            ScrollView {
                VStack(spacing: 2) {
                    if recipient == nil {
                        ForEach(suggestions, id: \.number) { contact in
                            Button {
                                recipient = contact
                                writing = true
                            } label: {
                                HStack(spacing: 10) {
                                    Avatar(name: contact.name, address: contact.number, size: 32)
                                    VStack(alignment: .leading, spacing: 0) {
                                        Text(verbatim: contact.name).font(.body.weight(.medium))
                                        Text(verbatim: [contact.label, contact.number].filter { !$0.isEmpty }.joined(separator: " "))
                                            .font(.callout).foregroundStyle(.secondary)
                                    }
                                    Spacer(minLength: 0)
                                }
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .hoverHighlight(radius: 12)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .padding(8)
                .animation(.tandem, value: suggestions.map(\.number))
            }
            .scrollContentBackground(.hidden)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                VStack(spacing: 8) {
                    if let problem {
                        Label(problem, systemImage: "exclamationmark.circle.fill").font(.caption).foregroundStyle(Palette.urgent)
                    }
                    if sending { PillSpinner(size: 18) }
                    Composer(text: $draft, placeholder: "Text Message", enabled: !sending, focused: $writing, onSend: send)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 14)
                .padding(.top, 6)
            }
        }
        .onAppear { choosing = true }
        .task(id: query) {
            guard recipient == nil, !query.trimmingCharacters(in: .whitespaces).isEmpty else {
                suggestions = []
                return
            }
            // Typing fast asks the phone once, for what was typed last.
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled else { return }
            suggestions = await phone.contacts(matching: query)
        }
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        guard let number else {
            problem = String(localized: "Choose someone from the list, or type a number")
            return
        }
        problem = nil
        sending = true
        Task {
            do {
                let thread = try await phone.start(to: number, name: recipient?.name ?? "", text: text)
                draft = ""
                onDone(thread)
            } catch {
                problem = PhoneMessages.text(of: error)
            }
            sending = false
        }
    }
}

// MARK: When there is nothing to show

/// What stands in the way, said only once the person opened Messages, with the one thing that helps.
private struct MessagesNotice: View {
    @Environment(EngineModel.self) private var model
    let phone: PhoneMessages
    let device: TandemDevice
    let onBack: () -> Void

    @LocalState private var asked = false

    var body: some View {
        UnderToolbar(avoidsWindowButtons: true) {
            HStack {
                Button(action: onBack) {
                    Image(systemName: "chevron.left").font(.system(size: 14, weight: .semibold))
                }
                .buttonStyle(GlassCircleButtonStyle(size: 30))
                .help("Back to the device")
                Spacer()
            }
            .padding(.horizontal, 12)
        } content: {
            content
                .frame(maxWidth: 380)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    @ViewBuilder
    private var content: some View {
        switch phone.load {
        case .idle, .loading, .ready:
            PillSpinner(size: 34)
        case let .refused(why):
            VStack(spacing: 12) {
                symbol(why == .offline ? "iphone.slash" : "message")
                Text(verbatim: title(why)).font(.title3.weight(.semibold)).multilineTextAlignment(.center)
                Text(verbatim: detail(why)).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
                if why == .off || why == .noPermission {
                    Button {
                        Task {
                            if await phone.askAccess() {
                                asked = true
                                model.showToast(String(localized: "Look at \(device.name)"))
                            } else {
                                model.showToast(String(localized: "That device is not connected right now"))
                            }
                        }
                    } label: {
                        Label(asked ? String(localized: "Ask again") : String(localized: "Ask \(device.name)"), systemImage: "iphone.radiowaves.left.and.right")
                    }
                    .buttonStyle(.tandemGlassProminent)
                    .controlSize(.large)
                    .padding(.top, 6)
                    if why == .off || asked {
                        Button("Try again") { Task { await phone.refresh() } }
                            .buttonStyle(.tandemGlass)
                    }
                }
            }
        case let .failed(reason):
            VStack(spacing: 12) {
                symbol("exclamationmark.bubble")
                Text("Your messages could not be loaded").font(.title3.weight(.semibold))
                Text(verbatim: reason).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
                Button("Try again") { Task { await phone.refresh() } }
                    .buttonStyle(.tandemGlassProminent)
                    .controlSize(.large)
                    .padding(.top, 6)
            }
        }
    }

    private func symbol(_ name: String) -> some View {
        Image(systemName: name)
            .font(.system(size: 26, weight: .semibold))
            .foregroundStyle(Palette.indigo)
            .frame(width: 64, height: 64)
            .background(Palette.indigo.opacity(0.14), in: .circle)
    }

    private func title(_ why: PhoneMessages.Refusal) -> String {
        switch why {
        case .off: String(localized: "Messages is off for this Mac")
        case .noPermission: String(localized: "Allow Tandem on \(device.name)")
        case .unavailable: String(localized: "\(device.name) has no text messages")
        case .offline: String(localized: "\(device.name) is not connected")
        }
    }

    private func detail(_ why: PhoneMessages.Refusal) -> String {
        switch why {
        case .off: String(localized: "It was turned off on \(device.name). Ask from here and allow it there, or turn on Messages for this Mac in Tandem on the phone.")
        case .noPermission: String(localized: "\(device.name) has not let Tandem read text messages yet. Ask from here, and allow it on the phone.")
        case .unavailable: String(localized: "This device has no SIM to send and receive text messages with.")
        case .offline: String(localized: "Your messages show up here as soon as it can be reached.")
        }
    }
}

// MARK: Words

enum MessagesText {
    static func title(of conversation: TandemSmsConversation) -> String {
        if !conversation.name.isEmpty { return conversation.name }
        return conversation.addresses.isEmpty ? String(localized: "Unknown number") : conversation.addresses.joined(separator: ", ")
    }

    /// Who wrote a message in a group: the name the phone has for that number, as far as the conversation says.
    static func sender(_ address: String, in conversation: TandemSmsConversation) -> String {
        let names = conversation.name.components(separatedBy: ", ")
        if names.count == conversation.addresses.count, let index = conversation.addresses.firstIndex(of: address) { return names[index] }
        return address
    }

    /// The number to call for a conversation with one other person. A short code is a number too; a sender with letters, such as
    /// a bank's name, cannot be called, and neither can a group.
    static func dialable(_ conversation: TandemSmsConversation) -> String? {
        guard conversation.addresses.count == 1, let address = conversation.addresses.first?.trimmingCharacters(in: .whitespaces) else { return nil }
        let allowed = CharacterSet(charactersIn: "+0123456789 -().*#")
        guard address.unicodeScalars.allSatisfy(allowed.contains), address.filter(\.isNumber).count >= 3 else { return nil }
        return address
    }

    /// The letters on a face: the first of the first two words of a name (of the first two people in a group), or of a sender
    /// that is a word, such as a bank. Empty for a plain number.
    static func initials(name: String, address: String) -> String {
        let people = name.components(separatedBy: ", ").filter { !$0.isEmpty }
        let letter: (Substring) -> String? = { word in word.first.flatMap { $0.isLetter ? String($0).uppercased() : nil } }
        if people.count > 1 {
            return people.prefix(2).compactMap { letter(Substring($0)) }.joined()
        }
        let source = people.first ?? (address.contains(where: \.isLetter) ? address : "")
        return source.split(separator: " ").compactMap(letter).prefix(2).joined()
    }

    /// The colour of a face: FNV-1a over the code points, the same sum `messages.js` makes, so a person has one colour everywhere.
    static func colorIndex(_ key: String, count: Int) -> UInt32 {
        var hash: UInt32 = 2_166_136_261
        for scalar in key.unicodeScalars {
            hash ^= scalar.value
            hash = hash &* 16_777_619
        }
        return hash % UInt32(count)
    }

    /// The time in the list: the hour today, then yesterday, the day of the week, and a date.
    static func listTime(_ ms: UInt64) -> String {
        let date = Date(timeIntervalSince1970: Double(ms) / 1000)
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return date.formatted(date: .omitted, time: .shortened) }
        if calendar.isDateInYesterday(date) { return String(localized: "Yesterday") }
        if let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: date), to: calendar.startOfDay(for: .now)).day, days < 7 {
            return date.formatted(.dateTime.weekday(.wide))
        }
        return date.formatted(date: .numeric, time: .omitted)
    }

    /// Above the first message of a day or after a silence: the day in bold and the time beside it, as Messages writes it.
    static func timeLine(_ date: Date) -> AttributedString {
        let calendar = Calendar.current
        let time = date.formatted(date: .omitted, time: .shortened)
        let day: String
        if calendar.isDateInToday(date) {
            day = String(localized: "Today")
        } else if calendar.isDateInYesterday(date) {
            day = String(localized: "Yesterday")
        } else if let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: date), to: calendar.startOfDay(for: .now)).day, days < 7 {
            day = date.formatted(.dateTime.weekday(.wide))
        } else {
            day = date.formatted(date: .abbreviated, time: .omitted)
        }
        var bold = AttributedString(day)
        bold.font = .caption.weight(.semibold)
        return bold + AttributedString(" " + time)
    }
}

import AppKit
import SwiftUI
import TandemCore

/// The text messages of a phone, the way Messages shows those of an iPhone: the conversations on the left, the one that is open
/// on the right, and a field at the bottom to write in. Sending goes out from the phone, as an ordinary SMS.
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
                    ConversationList(phone: phone, device: device, search: $search, selection: $selection, composing: $composing, onBack: onBack)
                        .frame(minWidth: 240, idealWidth: 290, maxWidth: 320)
                    Divider()
                    detail(phone)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
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
        .onChange(of: phone.conversations.first?.thread, initial: true) { _, first in
            // A wide window has room for the newest conversation from the start, as Messages does.
            if selection == nil, !composing, let first { selection = first }
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

    @ViewBuilder
    private func detail(_ phone: PhoneMessages) -> some View {
        if composing {
            NewMessageView(phone: phone, device: device) { thread in
                composing = false
                if let thread { selection = thread }
            }
            .transition(.opacity)
        } else if let thread = selection, let conversation = phone.conversation(thread) {
            ConversationView(phone: phone, device: device, conversation: conversation)
                .id(thread)
                .transition(.opacity)
        } else {
            Text(phone.conversations.isEmpty ? "No messages yet" : "Choose a conversation")
                .font(.callout)
                .foregroundStyle(.secondary)
        }
    }
}

// MARK: The list

private struct ConversationList: View {
    let phone: PhoneMessages
    let device: TandemDevice
    @Binding var search: String
    @Binding var selection: UInt64?
    @Binding var composing: Bool
    let onBack: () -> Void

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
        VStack(spacing: 10) {
            HStack(spacing: 10) {
                Button(action: onBack) {
                    Image(systemName: "chevron.left").font(.body.weight(.semibold))
                }
                .buttonStyle(.icon(size: 30))
                .help("Back to the device")
                VStack(alignment: .leading, spacing: 0) {
                    Text("Messages").font(.title3.weight(.bold))
                    Text(device.name).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer(minLength: 4)
                Button {
                    selection = nil
                    composing = true
                } label: {
                    Image(systemName: "square.and.pencil").font(.body.weight(.semibold))
                }
                .buttonStyle(.icon(size: 30))
                .help("New message")
            }
            .padding(.horizontal, 12)

            HStack(spacing: 6) {
                Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                TextField("Search", text: $search).textFieldStyle(.plain)
                if !search.isEmpty {
                    Button { search = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(.tertiary) }
                        .buttonStyle(.plain)
                }
            }
            .font(.callout)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(Color.primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            .padding(.horizontal, 12)

            ScrollView {
                LazyVStack(spacing: 2) {
                    ForEach(shown, id: \.thread) { conversation in
                        Button {
                            selection = conversation.thread
                        } label: {
                            ConversationRow(conversation: conversation, unread: phone.unread(conversation), selected: selection == conversation.thread && !composing)
                        }
                        .buttonStyle(.plain)
                    }
                    if phone.moreConversations && search.isEmpty {
                        PillSpinner(size: 18)
                            .padding(10)
                            .onAppear { Task { await phone.loadMoreConversations() } }
                    }
                }
                .padding(.horizontal, 8)
                .padding(.bottom, 10)
                .animation(.tandem, value: shown.map(\.thread))
            }
            .scrollContentBackground(.hidden)
        }
        .padding(.top, 8)
    }
}

private struct ConversationRow: View {
    let conversation: TandemSmsConversation
    let unread: Bool
    let selected: Bool
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Circle()
                .fill(Palette.indigo)
                .frame(width: 8, height: 8)
                .opacity(unread ? 1 : 0)
                .padding(.top, 17)
            Avatar(name: conversation.name, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 6) {
                    Text(verbatim: MessagesText.title(of: conversation))
                        .font(.callout.weight(.semibold))
                        .lineLimit(1)
                    Spacer(minLength: 4)
                    Text(verbatim: MessagesText.listTime(conversation.date))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Text(verbatim: conversation.fromMe ? String(localized: "You: \(conversation.snippet)") : conversation.snippet)
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .padding(.vertical, 8)
        .padding(.trailing, 10)
        .padding(.leading, 2)
        .background {
            RoundedRectangle(cornerRadius: 12, style: .continuous)
                .fill(selected ? Palette.indigo.opacity(0.16) : Color.primary.opacity(hovering && hoverEnabled ? 0.06 : 0))
        }
        .contentShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .onHover { hovering = $0 }
        .animation(.tandemFade, value: selected)
        .animation(.tandemFade, value: hovering)
        .animation(.tandemFade, value: unread)
    }
}

/// A person, as the round picture Messages shows without a photo: their initials, or a figure for a number without a name.
private struct Avatar: View {
    let name: String
    let size: CGFloat

    private var initials: String {
        // A group is a list of names; the first one stands for it.
        let first = name.split(separator: ",").first.map(String.init) ?? ""
        let words = first.split(separator: " ").filter { $0.first?.isLetter == true }
        return words.prefix(2).compactMap(\.first).map { String($0).uppercased() }.joined()
    }

    var body: some View {
        ZStack {
            Circle().fill(LinearGradient(colors: [Color(white: 0.66), Color(white: 0.52)], startPoint: .top, endPoint: .bottom))
            if initials.isEmpty {
                Image(systemName: name.contains(",") ? "person.2.fill" : "person.fill")
                    .font(.system(size: size * 0.42, weight: .medium))
                    .foregroundStyle(.white)
            } else {
                Text(verbatim: initials)
                    .font(.system(size: size * 0.4, weight: .semibold, design: .rounded))
                    .foregroundStyle(.white)
            }
        }
        .frame(width: size, height: size)
    }
}

// MARK: A conversation

private struct ConversationView: View {
    let phone: PhoneMessages
    let device: TandemDevice
    let conversation: TandemSmsConversation

    @LocalState private var draft = ""
    /// Said once the person tried to write in a group, which only the phone can answer.
    @LocalState private var groupNote = false

    private var group: Bool { conversation.addresses.count > 1 }

    private enum Item: Identifiable {
        case day(Date)
        case message(TandemSmsMessage)
        case pending(PhoneMessages.Pending)

        var id: String {
            switch self {
            case let .day(date): "day-\(date.timeIntervalSince1970)"
            case let .message(message): PhoneMessages.key(message)
            case let .pending(item): item.id.uuidString
            }
        }

        var mine: Bool? {
            switch self {
            case .day: nil
            case let .message(message): message.status != .received
            case .pending: true
            }
        }
    }

    /// Oldest first, with the day written above the first message of each day.
    private var items: [Item] {
        let state = phone.threads[conversation.thread]
        var dated: [(Date, Item)] = (state?.messages ?? []).map { (Date(timeIntervalSince1970: Double($0.date) / 1000), .message($0)) }
        dated += phone.pending.filter { $0.thread == conversation.thread }.map { ($0.date, .pending($0)) }
        dated.sort { $0.0 < $1.0 }
        var out: [Item] = []
        var lastDay: Date?
        let calendar = Calendar.current
        for (date, item) in dated {
            if lastDay.map({ !calendar.isDate($0, inSameDayAs: date) }) ?? true {
                out.append(.day(date))
                lastDay = date
            }
            out.append(item)
        }
        return out
    }

    var body: some View {
        let state = phone.threads[conversation.thread]
        VStack(spacing: 0) {
            header
            Divider().opacity(0.5)
            ScrollView {
                LazyVStack(spacing: 0) {
                    if state?.more ?? false {
                        PillSpinner(size: 18)
                            .padding(12)
                            .onAppear { Task { await phone.loadOlder(conversation.thread) } }
                    }
                    let list = items
                    ForEach(Array(list.enumerated()), id: \.element.id) { index, item in
                        // Messages of one side follow each other closely; a change of side gets some air.
                        let previous = index > 0 ? list[index - 1].mine : nil
                        row(item)
                            .padding(.top, previous == nil || previous == item.mine ? 3 : 12)
                    }
                    if let failed = state?.failed, (state?.messages.isEmpty ?? true) {
                        Text(verbatim: failed).font(.callout).foregroundStyle(.secondary).padding(20)
                    } else if state?.loading ?? false, state?.messages.isEmpty ?? true {
                        PillSpinner(size: 26).padding(30)
                    }
                }
                .padding(.horizontal, 18)
                .padding(.vertical, 12)
            }
            .defaultScrollAnchor(.bottom)
            .defaultScrollAnchor(.bottom, for: .sizeChanges)
            .scrollContentBackground(.hidden)
            .safeAreaInset(edge: .bottom, spacing: 0) {
                VStack(spacing: 8) {
                    if groupNote {
                        Text("A group conversation can only be answered on the phone for now.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .transition(.opacity.combined(with: .move(edge: .bottom)))
                    }
                    Composer(text: $draft, placeholder: "Text Message", onSend: send)
                }
                .padding(12)
                .animation(.tandem, value: groupNote)
            }
        }
    }

    private var header: some View {
        HStack(spacing: 10) {
            Avatar(name: conversation.name, size: 30)
            VStack(alignment: .leading, spacing: 0) {
                Text(verbatim: MessagesText.title(of: conversation)).font(.headline).lineLimit(1)
                if !conversation.name.isEmpty {
                    Text(verbatim: conversation.addresses.joined(separator: ", ")).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 10)
    }

    @ViewBuilder
    private func row(_ item: Item) -> some View {
        switch item {
        case let .day(date):
            Text(verbatim: MessagesText.dayLine(date))
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 8)
        case let .message(message):
            Bubble(
                text: message.body,
                mine: message.status != .received,
                pictures: message.pictures,
                phone: phone,
                sender: group && message.status == .received ? MessagesText.sender(message.address, in: conversation) : nil,
                note: message.status == .failed ? String(localized: "Not sent") : nil,
                time: Date(timeIntervalSince1970: Double(message.date) / 1000)
            )
        case let .pending(pending):
            VStack(alignment: .trailing, spacing: 4) {
                Bubble(text: pending.text, mine: true, pictures: [], phone: phone, sender: nil, note: nil, time: pending.date)
                    .opacity(pending.state == .sending ? 0.6 : 1)
                switch pending.state {
                case .sending:
                    Text("Sending").font(.caption2).foregroundStyle(.secondary)
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

private struct Bubble: View {
    let text: String
    let mine: Bool
    let pictures: [UInt64]
    let phone: PhoneMessages
    let sender: String?
    let note: String?
    let time: Date

    var body: some View {
        HStack(spacing: 0) {
            if mine { Spacer(minLength: 70) }
            VStack(alignment: mine ? .trailing : .leading, spacing: 3) {
                if let sender {
                    Text(verbatim: sender).font(.caption2).foregroundStyle(.secondary).padding(.horizontal, 10)
                }
                ForEach(pictures, id: \.self) { part in picture(part) }
                if !text.isEmpty || pictures.isEmpty {
                    Text(verbatim: text.isEmpty ? String(localized: "Message without text") : text)
                        .font(.body)
                        .italic(text.isEmpty)
                        .textSelection(.enabled)
                        .foregroundStyle(mine ? Color.white : Color.primary)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 7)
                        .background(mine ? Palette.indigo : Color.primary.opacity(0.09), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                }
                if let note {
                    Label(note, systemImage: "exclamationmark.circle.fill").font(.caption2).foregroundStyle(Palette.urgent)
                }
            }
            .help(time.formatted(date: .abbreviated, time: .shortened))
            if !mine { Spacer(minLength: 70) }
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
                .fill(Color.primary.opacity(0.07))
                .frame(width: 180, height: 130)
                .overlay { Image(systemName: "photo").font(.title2).foregroundStyle(.secondary) }
                .onAppear { phone.picture(part) }
        }
    }
}

/// The field at the bottom and its send button, floating over the conversation.
private struct Composer: View {
    @Binding var text: String
    let placeholder: LocalizedStringKey
    var enabled = true
    let onSend: () -> Void

    private var empty: Bool { text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    var body: some View {
        GlassEffectContainer(spacing: 8) {
            HStack(alignment: .bottom, spacing: 8) {
                TextField(placeholder, text: $text, axis: .vertical)
                    .textFieldStyle(.plain)
                    .lineLimit(1...6)
                    .font(.body)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                    .onSubmit(onSend)
                    .disabled(!enabled)
                Button(action: onSend) {
                    Image(systemName: "arrow.up")
                        .font(.system(size: 15, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 34, height: 34)
                        .contentShape(.circle)
                }
                .buttonStyle(.plain)
                .glassEffect(.regular.tint(empty || !enabled ? Color.secondary.opacity(0.4) : Palette.indigo).interactive(), in: .circle)
                .disabled(empty || !enabled)
                .help("Send")
                .animation(.tandemFade, value: empty)
            }
        }
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

    /// What was typed, when it is a number that can be sent to as it is.
    private var typedNumber: String? {
        let trimmed = query.trimmingCharacters(in: .whitespaces)
        let allowed = CharacterSet(charactersIn: "+0123456789 -()")
        guard trimmed.unicodeScalars.allSatisfy(allowed.contains), trimmed.filter(\.isNumber).count >= 3 else { return nil }
        return trimmed
    }

    private var number: String? { recipient?.number ?? typedNumber }

    var body: some View {
        VStack(spacing: 0) {
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
                    TextField("Name or number", text: $query).textFieldStyle(.plain)
                }
                Spacer(minLength: 0)
            }
            .font(.body)
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            Divider().opacity(0.5)

            ScrollView {
                VStack(spacing: 2) {
                    if recipient == nil {
                        ForEach(suggestions, id: \.number) { contact in
                            Button {
                                recipient = contact
                            } label: {
                                HStack(spacing: 10) {
                                    Avatar(name: contact.name, size: 30)
                                    VStack(alignment: .leading, spacing: 0) {
                                        Text(verbatim: contact.name).font(.callout.weight(.medium))
                                        Text(verbatim: [contact.label, contact.number].filter { !$0.isEmpty }.joined(separator: " "))
                                            .font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer(minLength: 0)
                                }
                                .padding(.horizontal, 10)
                                .padding(.vertical, 6)
                                .hoverHighlight(radius: 10)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .padding(8)
            }
            .scrollContentBackground(.hidden)
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            VStack(spacing: 8) {
                if let problem {
                    Label(problem, systemImage: "exclamationmark.circle.fill").font(.caption).foregroundStyle(Palette.urgent)
                }
                if sending { PillSpinner(size: 18) }
                Composer(text: $draft, placeholder: "Text Message", enabled: !sending, onSend: send)
            }
            .padding(12)
        }
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
        VStack(spacing: 0) {
            HStack {
                Button(action: onBack) {
                    Image(systemName: "chevron.left").font(.body.weight(.semibold))
                }
                .buttonStyle(.icon(size: 30))
                .help("Back to the device")
                Spacer()
            }
            .padding(.horizontal, 12)
            .padding(.top, 8)
            Spacer()
            content
                .frame(maxWidth: 380)
            Spacer()
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

    /// Above the first message of a day.
    static func dayLine(_ date: Date) -> String {
        let calendar = Calendar.current
        let time = date.formatted(date: .omitted, time: .shortened)
        if calendar.isDateInToday(date) { return String(localized: "Today \(time)") }
        if calendar.isDateInYesterday(date) { return String(localized: "Yesterday \(time)") }
        if let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: date), to: calendar.startOfDay(for: .now)).day, days < 7 {
            return date.formatted(.dateTime.weekday(.wide).hour().minute())
        }
        return date.formatted(date: .abbreviated, time: .shortened)
    }
}

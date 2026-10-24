import SwiftUI
import TandemCore

/// The phone's contacts, in the column where the conversations are, built like those rows: the same faces, the name in bold and
/// the numbers under it with what they are. A click writes to the person, the phone button calls them (after asking).
struct ContactsList: View {
    let device: TandemDevice
    let contacts: PhoneContacts
    let search: String
    /// The numbers of the conversation that is open.
    let selected: Set<String>
    let onOpen: (PhoneContacts.Person) -> Void

    var body: some View {
        let people = contacts.matching(search)
        let typed = CallConfirmation.typedNumber(search)
        ScrollView {
            LazyVStack(spacing: 2) {
                // A number typed in the search field is someone to call or write to as well, also when nobody has it.
                if let typed, !people.contains(where: { $0.numbers.contains { PhoneContacts.digits($0.number).contains(PhoneContacts.digits(typed)) } }) {
                    ContactRow(person: PhoneContacts.Person(name: "", numbers: [.init(number: typed, label: "")]), device: device, selected: false, onOpen: onOpen)
                }
                ForEach(people) { person in
                    ContactRow(
                        person: person,
                        device: device,
                        selected: person.numbers.contains { selected.contains(PhoneContacts.key($0.number)) },
                        onOpen: onOpen
                    )
                }
                if people.isEmpty && typed == nil && contacts.load != .loading {
                    empty
                }
                if contacts.load == .loading {
                    PillSpinner(size: 18).padding(10)
                }
            }
            .padding(.horizontal, 8)
            .padding(.top, 2)
            .padding(.bottom, 10)
            .animation(.tandem, value: people.map(\.id))
        }
        .scrollContentBackground(.hidden)
        .task(id: device.online) {
            if device.online { await contacts.loadAll() }
        }
        .task(id: search) {
            // Typing fast asks the phone once, for what was typed last.
            try? await Task.sleep(for: .milliseconds(250))
            guard !Task.isCancelled else { return }
            await contacts.search(search, online: device.online)
        }
    }

    private var empty: some View {
        VStack(spacing: 6) {
            Text(search.isEmpty ? "No contacts" : "No contacts found")
                .font(.callout.weight(.semibold))
            if search.isEmpty {
                // Android gives an app without the permission for contacts an empty book rather than a refusal.
                Text("Tandem on \(device.name) may not have access to contacts yet.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 30)
    }
}

private struct ContactRow: View {
    let person: PhoneContacts.Person
    let device: TandemDevice
    let selected: Bool
    let onOpen: (PhoneContacts.Person) -> Void
    @Environment(\.hoverEnabled) private var hoverEnabled
    @LocalState private var hovering = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 14, style: .continuous)
        HStack(alignment: .center, spacing: 0) {
            // The column of the unread dot in the conversations, so the faces of both lists stand in one line.
            Color.clear.frame(width: 16, height: 1)
            Avatar(name: person.name, address: person.numbers.first?.number ?? "", size: 40)
            VStack(alignment: .leading, spacing: 1) {
                Text(verbatim: person.name.isEmpty ? person.numbers.first?.number ?? "" : person.name)
                    .font(.body.weight(.semibold))
                    .lineLimit(1)
                ForEach(person.name.isEmpty ? [] : person.numbers, id: \.self) { number in
                    Text(verbatim: [number.label, number.number].filter { !$0.isEmpty }.joined(separator: " "))
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }
            .padding(.leading, 10)
            Spacer(minLength: 6)
            call
        }
        .padding(.vertical, 8)
        .padding(.trailing, 10)
        .background {
            ZStack {
                shape.fill(Color.primary.opacity(hovering && hoverEnabled && !selected ? hoverOpacity : 0))
                shape.fill(Palette.indigo.opacity(selected ? 0.16 : 0))
            }
        }
        .contentShape(shape)
        .onTapGesture { onOpen(person) }
        .onHover { hovering = $0 }
        .animation(.tandemFade, value: hovering)
        .animation(.tandemFade, value: selected)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// With one number the button asks at once; with more, it first asks which one.
    @ViewBuilder
    private var call: some View {
        let title = person.name.isEmpty ? person.numbers.first?.number ?? "" : person.name
        if person.numbers.count > 1 {
            Menu {
                ForEach(person.numbers, id: \.self) { number in
                    Button {
                        ask(number)
                    } label: {
                        Text(verbatim: [number.label, number.number].filter { !$0.isEmpty }.joined(separator: " "))
                    }
                }
            } label: {
                CallGlyph()
            }
            .menuStyle(.button)
            .buttonStyle(.plain)
            .menuIndicator(.hidden)
            .fixedSize()
            .help(String(localized: "Call \(title)"))
        } else if let number = person.numbers.first {
            Button { ask(number) } label: { Image(systemName: "phone").font(.system(size: 13, weight: .semibold)) }
                .buttonStyle(GlassCircleButtonStyle(size: 28))
                .help(String(localized: "Call \(title)"))
        }
    }

    private func ask(_ number: PhoneContacts.Number) {
        CallConfirmation.shared.ask(device: device, number: number.number, name: person.name, label: number.label)
    }
}

/// The phone of the call button, drawn by hand for the menu, whose own style would put it in a box.
private struct CallGlyph: View {
    var body: some View {
        Image(systemName: "phone")
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(.primary)
            .frame(width: 28, height: 28)
            .contentShape(.circle)
            .messagesGlass(in: Circle())
    }
}

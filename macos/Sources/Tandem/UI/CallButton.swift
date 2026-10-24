import SwiftUI
import TandemCore

/// "Call" on a phone's page: a number typed or pasted here, and the phone places the call. The talking happens on the phone or its
/// headset; Android lets no app carry the sound of a call to another device.
struct CallButton: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    @LocalState private var asking = false
    @LocalState private var number = ""
    @LocalState private var found: [TandemSmsContact] = []
    @FocusState private var focused: Bool

    var body: some View {
        GlassActionButton(title: "Call", symbol: "phone") {
            // What the phone still needs is said now, when it is wanted, and not on the page all the time.
            if let missing = device.features.first(where: { $0.id == "calls" && !$0.ready }) {
                model.showToast(missing.why)
                return
            }
            number = Self.numberOnClipboard() ?? ""
            asking = true
        }
        .disabled(!device.online)
        .popover(isPresented: $asking, arrowEdge: .bottom) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 8) {
                    TextField("Name or number", text: $number)
                        .textFieldStyle(.roundedBorder)
                        .frame(width: 200)
                        .focused($focused)
                        .onSubmit { call() }
                    Button("Call") { call() }
                        .buttonStyle(.borderedProminent)
                        .tint(Palette.indigo)
                        .disabled(Self.clean(number).isEmpty)
                }
                VStack(spacing: 2) {
                    ForEach(found, id: \.number) { contact in
                        NumberRow(title: contact.name, label: contact.label, number: contact.number) { call(contact.number) }
                    }
                }
            }
            .padding(12)
            .frame(width: 330, alignment: .leading)
            .onAppear { focused = true }
            // A name typed instead of a number: the contacts of the phone that match, as they are typed.
            .task(id: number) { await search() }
            .animation(.tandemFade, value: found.map(\.number))
        }
    }

    private func search() async {
        let query = number.trimmingCharacters(in: .whitespaces)
        guard query.count >= 2, query.contains(where: \.isLetter), let engine = model.tandem else {
            found = []
            return
        }
        try? await Task.sleep(for: .milliseconds(250))
        guard !Task.isCancelled else { return }
        found = Array(((try? await engine.smsContacts(target: device.id, query: query)) ?? []).prefix(6))
    }

    private func call(_ chosen: String? = nil) {
        // Return on a name calls the first contact found for it.
        let dialed = Self.clean(chosen ?? (Self.clean(number).isEmpty ? found.first?.number ?? "" : number))
        guard !dialed.isEmpty else { return }
        asking = false
        let contact = found.first { Self.clean($0.number) == dialed }?.name
        Self.dial(dialed, on: device, contact: contact) { model.showToast($0) }
    }

    /// The phone dials `number`, and `say` gets what to show. Also what a contact picked in Spotlight uses (`PhoneSpotlight`).
    @MainActor
    static func dial(_ number: String, on device: TandemDevice, contact: String? = nil, say: @escaping @MainActor (String) -> Void) {
        let dialed = clean(number)
        guard !dialed.isEmpty, let engine = EngineModel.shared.tandem else { return }
        let name = device.name
        let id = device.id
        Task { @MainActor in
            do {
                try await engine.dial(target: id, number: dialed)
                CallWindow.shared.dialed(device: id, number: dialed, name: contact)
                say(String(localized: "\(name) is calling \(dialed)"))
            } catch {
                say(String(localized: "\(name) could not be reached"))
            }
        }
    }

    /// What a phone can dial: digits, a plus in front, and the star and hash of a keypad.
    static func clean(_ text: String) -> String {
        let kept = text.filter { $0.isNumber || "+*#".contains($0) }
        return kept.count >= 3 ? kept : ""
    }

    /// A number that was just copied (from a mail, a website) is filled in already.
    private static func numberOnClipboard() -> String? {
        guard let text = NSPasteboard.general.string(forType: .string), text.count < 40 else { return nil }
        let cleaned = clean(text)
        return cleaned.isEmpty ? nil : text.trimmingCharacters(in: .whitespacesAndNewlines)
    }
}

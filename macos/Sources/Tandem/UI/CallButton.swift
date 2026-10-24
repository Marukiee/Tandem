import SwiftUI
import TandemCore

/// "Call" on a phone's page: a number typed or pasted here, and the phone places the call. The talking happens on the phone or its
/// headset; Android lets no app carry the sound of a call to another device.
struct CallButton: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice
    @LocalState private var asking = false
    @LocalState private var number = ""
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
            HStack(spacing: 8) {
                TextField("Number", text: $number)
                    .textFieldStyle(.roundedBorder)
                    .frame(width: 180)
                    .focused($focused)
                    .onSubmit(call)
                Button("Call", action: call)
                    .buttonStyle(.borderedProminent)
                    .tint(Palette.indigo)
                    .disabled(Self.clean(number).isEmpty)
            }
            .padding(12)
            .onAppear { focused = true }
        }
    }

    private func call() {
        let dialed = Self.clean(number)
        guard !dialed.isEmpty, let engine = model.tandem else { return }
        asking = false
        let name = device.name
        Task {
            do {
                try await engine.dial(target: device.id, number: dialed)
                model.showToast(String(localized: "\(name) is calling \(dialed)"))
            } catch {
                model.showToast(String(localized: "\(name) could not be reached"))
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

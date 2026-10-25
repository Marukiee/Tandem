import SwiftUI
import TandemCore

/// Every call this Mac has a phone place goes through here, so each one is asked first: a phone that dials by a slip of the pointer
/// rings someone for real. The phone places the call itself and the talking happens on the phone or its headset; Android lets no app
/// carry the sound of a call to another device.
@MainActor
@Observable
final class CallConfirmation {
    struct Request: Identifiable, Equatable {
        let id = UUID()
        let device: String
        let deviceName: String
        let number: String
        let name: String?
        let label: String?
    }

    static let shared = CallConfirmation()
    private(set) var pending: Request?

    /// Shows the question for `number` on `device`. What stands in the way is said now, when someone wants to call, and not on a page
    /// all the time.
    func ask(device: TandemDevice, number: String, name: String? = nil, label: String? = nil) {
        let model = EngineModel.shared
        if let calls = device.features.first(where: { $0.id == "calls" }), !calls.ready {
            model.showToast(calls.why)
            return
        }
        guard device.online else {
            model.showToast(String(localized: "\(device.name) could not be reached"))
            return
        }
        show(device: device, number: number, name: name, label: label)
    }

    /// The question without the checks, for a snapshot of it with a made up phone (`TANDEM_DEBUG_MESSAGES=confirm`).
    func show(device: TandemDevice, number: String, name: String?, label: String?) {
        guard !Self.clean(number).isEmpty else { return }
        let named = name.flatMap { $0.trimmingCharacters(in: .whitespaces).isEmpty ? nil : $0 }
        pending = Request(device: device.id, deviceName: device.name, number: number, name: named, label: label.flatMap { $0.isEmpty ? nil : $0 })
    }

    func cancel() {
        pending = nil
    }

    func confirm() {
        // Return and the default button can both arrive for one press; only the first one calls.
        guard let request = pending, let engine = EngineModel.shared.tandem else {
            pending = nil
            return
        }
        pending = nil
        Self.dial(request.number, device: request.device, deviceName: request.deviceName, name: request.name, engine: engine) {
            EngineModel.shared.showToast($0)
        }
    }

    /// The phone dials, without asking: only for a call that was just asked about, here or in the card of a Spotlight pick.
    static func dial(_ number: String, device: String, deviceName: String, name: String?, engine: TandemEngine,
                     say: @escaping @MainActor (String) -> Void) {
        let dialed = clean(number)
        guard !dialed.isEmpty else { return }
        Task { @MainActor in
            do {
                try await engine.dial(target: device, number: dialed)
                CallWindow.shared.dialed(device: device, number: dialed, name: name)
                say(String(localized: "\(deviceName) is calling \(name ?? number)"))
            } catch {
                say(String(localized: "\(deviceName) could not be reached"))
            }
        }
    }

    /// What a phone can dial: digits, a plus in front, and the star and hash of a keypad.
    static func clean(_ text: String) -> String {
        let kept = text.filter { $0.isNumber || "+*#".contains($0) }
        return kept.count >= 3 ? kept : ""
    }

    /// A typed text that is a number to call or write to as it is, and not the start of a name.
    static func typedNumber(_ text: String) -> String? {
        let trimmed = text.trimmingCharacters(in: .whitespaces)
        let allowed = CharacterSet(charactersIn: "+0123456789 -().*#")
        guard trimmed.unicodeScalars.allSatisfy(allowed.contains), trimmed.filter(\.isNumber).count >= 3 else { return nil }
        return trimmed
    }
}

extension View {
    /// Where the question about a call appears: over the window that asked for it, with the window dimmed behind it.
    func callConfirmation() -> some View {
        modifier(CallConfirmationHost())
    }
}

private struct CallConfirmationHost: ViewModifier {
    func body(content: Content) -> some View {
        let pending = CallConfirmation.shared.pending
        content
            .transformEnvironment(\.hoverEnabled) { if pending != nil { $0 = false } }
            .overlay {
                if let pending {
                    ZStack {
                        Color.black.opacity(0.32)
                            .ignoresSafeArea()
                            .contentShape(Rectangle())
                            .onTapGesture { CallConfirmation.shared.cancel() }
                        CallConfirmCard(request: pending)
                            .transition(.scale(scale: 0.94).combined(with: .opacity))
                    }
                    .environment(\.hoverEnabled, true)
                    .transition(.opacity)
                }
            }
            .animation(.tandemSpringy, value: pending)
    }
}

private struct CallConfirmCard: View {
    let request: CallConfirmation.Request
    /// Taken from the field that had it: Return in the field of a conversation would send the text there instead of calling.
    @FocusState private var focused: Bool

    private var title: String {
        String(localized: "Call \(request.name ?? request.number)?")
    }

    private var message: String {
        let number = request.name == nil ? "" : [request.label, request.number].compactMap { $0 }.joined(separator: " ")
        return number.isEmpty
            ? String(localized: "Via \(request.deviceName)")
            : String(localized: "\(number) via \(request.deviceName)")
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            PromptHeader(symbol: "phone.fill", title: Text(verbatim: title), message: Text(verbatim: message))
            HStack(spacing: 8) {
                Spacer(minLength: 0)
                Button("Cancel") { CallConfirmation.shared.cancel() }
                    .buttonStyle(.tandemGlass)
                    .keyboardShortcut(.cancelAction)
                    .focusEffectDisabled()
                Button {
                    CallConfirmation.shared.confirm()
                } label: {
                    Label("Call", systemImage: "phone.fill")
                }
                .buttonStyle(.tandemGlassProminent)
                .keyboardShortcut(.defaultAction)
                .focusEffectDisabled()
            }
            .controlSize(.large)
        }
        .floatingCard(width: 400)
        .focusable()
        .focused($focused)
        .focusEffectDisabled()
        .onKeyPress(.return) {
            CallConfirmation.shared.confirm()
            return .handled
        }
        .onKeyPress(.escape) {
            CallConfirmation.shared.cancel()
            return .handled
        }
        .onAppear { focused = true }
    }
}

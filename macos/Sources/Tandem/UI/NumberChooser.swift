import AppKit
import SwiftUI

/// "Which number?" for a person with more than one, in a glass card at the top of the screen like the other questions: a row per
/// number, the kind of number small on the left and the number itself on the right. A press calls it; Escape or Cancel does nothing.
@MainActor
final class NumberChooser {
    struct Choice: Identifiable {
        var id: String { number }
        let number: String
        let label: String
    }

    static let shared = NumberChooser()
    private var panel: NSPanel?

    /// `choices` with the one to suggest first. Nobody answering closes it after a minute.
    func ask(name: String, choices: [Choice], call: @escaping (String) -> Void) {
        close()
        let panel = KeyPanel(contentRect: NSRect(x: 0, y: 0, width: 360, height: 200), styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.level = .statusBar
        panel.isReleasedWhenClosed = false
        panel.hidesOnDeactivate = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        let view = NSHostingView(rootView: NumberChooserView(name: name, choices: choices, pick: { [weak self] number in
            self?.close()
            call(number)
        }, cancel: { [weak self] in self?.close() }))
        panel.contentView = view
        panel.setContentSize(view.fittingSize)
        // Key without bringing Tandem forward, so Escape and Return reach it.
        FloatingStack.shared.show(panel, key: true)
        self.panel = panel
        Task { @MainActor [weak self, weak panel] in
            try? await Task.sleep(for: .seconds(60))
            if let panel, self?.panel === panel { self?.close() }
        }
    }

    func close() {
        guard let panel else { return }
        FloatingStack.shared.remove(panel)
        panel.orderOut(nil)
        self.panel = nil
    }
}

private struct NumberChooserView: View {
    let name: String
    let choices: [NumberChooser.Choice]
    let pick: (String) -> Void
    let cancel: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            PromptHeader(symbol: "phone.fill", title: Text("Call \(name)"), message: Text("Which number?"))
            VStack(spacing: 2) {
                ForEach(Array(choices.enumerated()), id: \.element.id) { index, choice in
                    NumberRow(label: choice.label, number: choice.number, suggested: index == 0) { pick(choice.number) }
                        .keyboardShortcut(index == 0 ? .defaultAction : nil)
                }
            }
            HStack {
                Spacer(minLength: 0)
                Button("Cancel", action: cancel)
                    .buttonStyle(.plain)
                    .foregroundStyle(.secondary)
                    .keyboardShortcut(.cancelAction)
            }
        }
        // The panel is key for Return and Escape; a blue ring of focus around a row would look like a mistake.
        .focusEffectDisabled()
        .padding(18)
        .frame(minWidth: 300, alignment: .leading)
        .fixedSize()
        // A veil of the window colour on the glass, so the words stay clear over whatever is behind the card, light or dark.
        .background(Color(nsColor: .windowBackgroundColor).opacity(0.55), in: RoundedRectangle(cornerRadius: Metrics.panel, style: .continuous))
        .floatingGlass(in: RoundedRectangle(cornerRadius: Metrics.panel, style: .continuous))
        .shadow(color: .black.opacity(0.18), radius: 18, y: 8)
        .padding(EdgeInsets(top: 12, leading: 28, bottom: 36, trailing: 28))
    }
}

/// One number of a person: what kind on the left, small, and the number on the right in digits of one width, on one line. Also the
/// rows under the field of the Call button.
struct NumberRow: View {
    var title: String? = nil
    let label: String
    let number: String
    var suggested = false
    let action: () -> Void
    @LocalState private var hovering = false
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        Button(action: action) {
            HStack(spacing: 16) {
                VStack(alignment: .leading, spacing: 1) {
                    if let title {
                        Text(verbatim: title).font(.callout.weight(.medium)).foregroundStyle(.primary)
                    }
                    if !label.isEmpty {
                        Text(verbatim: label).font(.callout).foregroundStyle(.secondary)
                    }
                }
                .lineLimit(1)
                Spacer(minLength: 0)
                Text(verbatim: number)
                    .font(.body.monospacedDigit().weight(.medium))
                    // The deep indigo is too dark to read on dark glass.
                    .foregroundStyle(suggested ? (scheme == .dark ? Palette.indigoLight : Palette.indigo) : .primary)
                    .lineLimit(1)
                    .fixedSize()
            }
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, minHeight: title == nil ? 38 : 46)
            .background(fill, in: RoundedRectangle(cornerRadius: Metrics.inner(Metrics.panel, inset: 18), style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .focusEffectDisabled()
        .onHover { hovering = $0 }
        .animation(.tandemFade, value: hovering)
    }

    private var fill: Color {
        let tint = scheme == .dark ? 0.22 : 0.1
        if hovering { return suggested ? Palette.indigo.opacity(tint + 0.08) : Color.primary.opacity(hoverOpacity * 1.5) }
        return suggested ? Palette.indigo.opacity(tint) : .clear
    }
}

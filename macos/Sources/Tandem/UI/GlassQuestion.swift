import AppKit
import SwiftUI

/// A question in a glass panel in the column of floating cards at the top right (see `FloatingStack`), with a few answers. Nobody
/// answering closes it after a while, which is the same as the last answer.
@MainActor
final class GlassQuestion {
    struct Answer {
        let title: LocalizedStringKey
        var prominent = false
        let action: () -> Void
    }

    static let shared = GlassQuestion()
    private var panel: NSPanel?
    /// Counts the questions, so whoever asked can tell whether the one on screen is still theirs before closing it.
    private(set) var asked = 0

    func ask(symbol: String, title: String, body: String, answers: [Answer], closeAfter: Duration = .seconds(60)) {
        close()
        asked += 1
        let panel = KeyPanel(contentRect: NSRect(x: 0, y: 0, width: 440, height: 160), styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.level = .statusBar
        panel.isReleasedWhenClosed = false
        panel.hidesOnDeactivate = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        let view = NSHostingView(rootView: GlassQuestionView(symbol: symbol, title: title, message: body, answers: answers, close: { [weak self] in self?.close() }))
        panel.contentView = view
        panel.setContentSize(view.fittingSize)
        // Key, so Return and Escape answer it; the buttons draw no ring of focus for that (see below).
        FloatingStack.shared.show(panel, key: true)
        self.panel = panel
        Task { @MainActor [weak self, weak panel] in
            try? await Task.sleep(for: closeAfter)
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

private struct GlassQuestionView: View {
    let symbol: String
    let title: String
    let message: String
    let answers: [GlassQuestion.Answer]
    let close: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            PromptHeader(symbol: symbol, title: Text(verbatim: title), message: Text(verbatim: message))
            HStack(spacing: 8) {
                Spacer(minLength: 0)
                let quiet = answers.firstIndex { !$0.prominent }
                ForEach(Array(answers.enumerated()), id: \.offset) { index, answer in
                    Button(answer.title) {
                        close()
                        answer.action()
                    }
                    .buttonStyle(answer.prominent ? .tandemGlassProminent : .tandemGlass)
                    // The panel is key so the keyboard can answer, and the first button would get the blue ring of focus: a frame
                    // around a pill of glass that looks like a mistake.
                    .focusEffectDisabled()
                    // Return takes the main answer, Escape the first other one.
                    .keyboardShortcut(answer.prominent ? .defaultAction : index == quiet ? .cancelAction : nil)
                }
            }
            .controlSize(.large)
        }
        .floatingCard()
    }
}

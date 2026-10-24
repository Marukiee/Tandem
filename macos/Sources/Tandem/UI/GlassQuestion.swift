import AppKit
import SwiftUI

/// A question in a glass panel at the top of the screen, under the bar that says a device is looking at this Mac when that is there,
/// with a few answers. Nobody answering closes it after a while, which is the same as the last answer.
@MainActor
final class GlassQuestion {
    struct Answer {
        let title: LocalizedStringKey
        var prominent = false
        let action: () -> Void
    }

    static let shared = GlassQuestion()
    private var panel: NSPanel?

    func ask(symbol: String, title: String, body: String, answers: [Answer], closeAfter: Duration = .seconds(60)) {
        close()
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
        if let screen = NSScreen.main {
            let area = screen.visibleFrame
            let top = ScreenPanels.shared.lowestEdge.map { $0 + 30 } ?? area.maxY - 10
            panel.setFrameOrigin(NSPoint(x: area.midX - panel.frame.width / 2, y: top - panel.frame.height))
        }
        panel.makeKeyAndOrderFront(nil)
        self.panel = panel
        Task { @MainActor [weak self, weak panel] in
            try? await Task.sleep(for: closeAfter)
            if let panel, self?.panel === panel { self?.close() }
        }
    }

    func close() {
        panel?.orderOut(nil)
        panel = nil
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
                ForEach(Array(answers.enumerated()), id: \.offset) { _, answer in
                    Button(answer.title) {
                        close()
                        answer.action()
                    }
                    .buttonStyle(answer.prominent ? .tandemGlassProminent : .tandemGlass)
                }
            }
            .controlSize(.large)
        }
        .floatingCard()
    }
}

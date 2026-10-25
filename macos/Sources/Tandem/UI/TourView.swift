import AppKit
import SwiftUI
import TandemCore

/// The window of the tour (see `SetupTour`): one, brought to the front again when it is asked for once more.
@MainActor
final class TourWindow {
    static let shared = TourWindow()
    private var window: NSWindow?

    func show() {
        if let window {
            window.makeKeyAndOrderFront(nil)
            NSApp.activate()
            return
        }
        let host = NSHostingView(rootView: TourView { [weak self] in self?.window?.close() }.environment(EngineModel.shared))
        let made = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 560, height: 660), styleMask: [.titled, .closable, .resizable, .fullSizeContentView], backing: .buffered, defer: false)
        made.title = String(localized: "What Tandem can do")
        made.titlebarAppearsTransparent = true
        made.isReleasedWhenClosed = false
        made.contentView = host
        made.contentMinSize = NSSize(width: 460, height: 420)
        made.center()
        window = made
        SetupTour.markSeen()
        made.makeKeyAndOrderFront(nil)
        NSApp.activate()
    }
}

/// Everything Tandem can do on this Mac, with what each thing still needs: set it up now, later (one reminder in a few days), or not for
/// me. The rows are those of What works, with the three choices under them.
struct TourView: View {
    let close: () -> Void
    @LocalState private var features: [TandemFeature] = []
    @LocalState private var later = SetupTour.later
    @LocalState private var skipped = SetupTour.skipped

    var body: some View {
        let todo = features.filter { !$0.ready && !later.contains($0.id) && !skipped.contains($0.id) }
        let waiting = features.filter { !$0.ready && later.contains($0.id) }
        let notForMe = features.filter { !$0.ready && skipped.contains($0.id) }
        let works = features.filter(\.ready)
        Form {
            Section {
                Text(todo.isEmpty
                    ? String(localized: "Everything is set up, or put away as not for you.")
                    : String(localized: "\(todo.count) things can still be set up. Each one says what it needs: set it up now, later, or put it away."))
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            if !todo.isEmpty {
                Section("To set up") {
                    ForEach(todo, id: \.id) { feature in
                        TourRow(feature: feature) {
                            setUpButton(feature)
                            Button("Later") { change { SetupTour.putOff(feature.id) } }
                            Button("Not for me") { change { SetupTour.skip(feature.id) } }
                        }
                    }
                }
            }
            if !waiting.isEmpty {
                Section("Later") {
                    ForEach(waiting, id: \.id) { feature in
                        TourRow(feature: feature, note: String(localized: "A reminder comes in a few days")) {
                            setUpButton(feature)
                            Button("Not for me") { change { SetupTour.skip(feature.id) } }
                        }
                    }
                }
            }
            if !works.isEmpty {
                Section("Works") {
                    ForEach(works, id: \.id) { TourRow(feature: $0) {} }
                }
            }
            if !notForMe.isEmpty {
                Section("Not for you") {
                    ForEach(notForMe, id: \.id) { feature in
                        TourRow(feature: feature, quiet: true) {
                            Button("Back on the list") { change { SetupTour.undo(feature.id) } }
                        }
                    }
                }
                .opacity(0.7)
            }
            HStack {
                Spacer()
                Button("Done", action: close).buttonStyle(.borderedProminent).tint(Palette.indigo).keyboardShortcut(.defaultAction)
            }
            .listRowBackground(Color.clear)
        }
        .formStyle(.grouped)
        .animation(.tandem, value: todo.map(\.id))
        .animation(.tandem, value: features.map(\.ready))
        .task {
            // Permissions change in System Settings, so the list is looked at again while the window is open.
            while !Task.isCancelled {
                features = SetupTour.items(await FeatureReport.current())
                try? await Task.sleep(for: .seconds(1.5))
            }
        }
    }

    private func setUpButton(_ feature: TandemFeature) -> some View {
        Button("Set up") { if let page = feature.page { FeatureReport.open(page) } }
            .buttonStyle(.borderedProminent).tint(Palette.indigo)
            .disabled(feature.page == nil)
    }

    private func change(_ edit: () -> Void) {
        edit()
        later = SetupTour.later
        skipped = SetupTour.skipped
    }
}

/// A row of What works (`FeatureRow`), with the buttons of the tour under the text.
private struct TourRow<Actions: View>: View {
    let feature: TandemFeature
    var note: String?
    var quiet = false
    @ViewBuilder var actions: Actions

    var body: some View {
        let tint: Color = feature.ready ? .green : Palette.indigo
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: feature.ready ? "checkmark" : FeatureRow.symbol(feature.id))
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(tint)
                .frame(width: 30, height: 30)
                .background(tint.opacity(0.14), in: .circle)
            VStack(alignment: .leading, spacing: 6) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: feature.name).font(.callout.weight(.medium))
                    if let line = note ?? (!feature.ready && !quiet && !feature.why.isEmpty ? feature.why : nil) {
                        Text(verbatim: line).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    }
                }
                if !feature.ready {
                    HStack(spacing: 8) { actions }.controlSize(.small)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }
}

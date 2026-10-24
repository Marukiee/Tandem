import SwiftUI

/// The history as a page of the main window: the same copies as the quick panel, with a click to copy and a right
/// click for the rest.
struct ClipboardPage: View {
    @Environment(EngineModel.self) private var model
    @LocalState private var query = ""
    @LocalState private var filter = ClipFilter.all
    @LocalState private var confirmClear = false

    var body: some View {
        let history = ClipboardHistory.shared
        let shown = ClipQuery.apply(history.items, query: query, filter: filter)
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header(history)
                if history.enabled {
                    // Spacing 0: two pieces of glass side by side, not one that flows into the other.
                    GlassEffectContainer(spacing: 0) {
                        HStack(spacing: 10) {
                            PageSearchField(text: $query)
                            ClipFilterChips(filter: $filter).fixedSize()
                        }
                    }
                }
                content(history: history, shown: shown)
            }
            .pagePadding()
            .frame(maxWidth: 760, alignment: .leading)
            .frame(maxWidth: .infinity)
            .animation(.tandem, value: shown.map(\.id))
            .animation(.tandem, value: history.enabled)
        }
        .toolbar {
            // The buttons at the trailing end, where the system's own apps have them.
            ToolbarSpacer(.flexible)
            ToolbarItem(placement: .primaryAction) {
                Button { ClipboardPanelController.shared.show() } label: {
                    Label("Open the quick panel", systemImage: "rectangle.and.text.magnifyingglass")
                }
                .help("Open the quick panel")
            }
            if !history.items.isEmpty {
                ToolbarItem(placement: .primaryAction) {
                    Button { confirmClear = true } label: {
                        Label("Clear", systemImage: "xmark.bin")
                    }
                    .help("Clear")
                }
            }
        }
        .clearHistoryDialog(isPresented: $confirmClear)
    }

    private func header(_ history: ClipboardHistory) -> some View {
        PageHeader(title: "Clipboard", subtitle: "Everything you copied and what your devices sent. Click one to copy it, right click for more.") {
            if history.enabled && history.hotkeyOn {
                HStack(spacing: 6) {
                    Text("Open it anywhere with").font(.callout).foregroundStyle(.secondary)
                    KeyCap(history.hotkey.display)
                }
                .padding(.top, 2)
            }
        }
    }

    @ViewBuilder
    private func content(history: ClipboardHistory, shown: [ClipItem]) -> some View {
        if !history.enabled {
            ContentUnavailableView {
                Label("The history is off", systemImage: "pause.circle")
            } description: {
                Text("Tandem is not keeping what you copy. Turn it on to find a copy again later.")
            } actions: {
                Button("Turn on") { history.enabled = true }
                    .buttonStyle(.tandemGlassProminent)
            }
            .frame(maxWidth: .infinity, minHeight: 260)
        } else if history.items.isEmpty {
            ContentUnavailableView(
                "Nothing copied yet",
                systemImage: "doc.on.clipboard",
                description: Text("What you copy shows up here, and what arrives from your other devices.")
            )
            .frame(maxWidth: .infinity, minHeight: 260)
        } else if shown.isEmpty {
            ContentUnavailableView.search(text: query)
                .frame(maxWidth: .infinity, minHeight: 220)
        } else {
            Card(radius: Metrics.card, padding: Metrics.cardInset) {
                LazyVStack(spacing: 2) {
                    ForEach(shown) { item in
                        PageRow(item: item)
                            .transition(.asymmetric(insertion: .scale(scale: 0.96).combined(with: .opacity), removal: .opacity))
                    }
                }
            }
        }
    }
}

private struct PageRow: View {
    @Environment(EngineModel.self) private var model
    let item: ClipItem

    var body: some View {
        Hoverable { hovering in
            ClipRowView(item: item, hovering: hovering, actions: ClipRowActions(
                togglePin: { ClipboardHistory.shared.togglePin(item.id) },
                delete: { ClipboardHistory.shared.delete([item.id]) }
            ))
        }
        .onTapGesture { ClipboardPanelController.shared.copy(item) }
        .contextMenu {
            ForEach(ClipActions.list(for: item, model: model, primary: [
                ClipAction(id: "copy", title: String(localized: "Copy"), symbol: "doc.on.doc") { ClipboardPanelController.shared.copy(item) },
            ])) { action in
                Button(role: action.destructive ? .destructive : nil) { action.run() } label: {
                    Label(action.title, systemImage: action.symbol)
                }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityAction(named: Text("Copy")) { ClipboardPanelController.shared.copy(item) }
    }
}

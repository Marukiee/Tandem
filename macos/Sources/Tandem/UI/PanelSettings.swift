import AppKit
import Carbon.HIToolbox
import SwiftUI

/// The tab Shortcuts: a shortcut for each thing Tandem does with one press, and which four of them are tiles in the menu bar panel.
struct ShortcutsSettings: View {
    var body: some View {
        Form {
            ShortcutsSection()
            PanelTilesSection()
        }
        .formStyle(.grouped)
    }
}

/// Which four things the tiles of the menu bar panel do.
struct PanelTilesSection: View {
    @LocalState private var tiles = PanelAction.tiles

    var body: some View {
        Section {
            ForEach(0 ..< 4, id: \.self) { index in
                Picker(selection: Binding(get: { tiles[index] }, set: { choose($0, at: index) })) {
                    ForEach(PanelAction.allCases) { action in
                        Label(action.title, systemImage: action.symbol).tag(action)
                    }
                } label: {
                    Text("Tile \(index + 1)")
                }
            }
            if tiles != PanelAction.standardTiles {
                Button("Back to the standard tiles") { set(PanelAction.standardTiles) }
            }
        } header: {
            Text("Menu bar tiles")
        } footer: {
            Text("The four tiles in the panel under the menu bar icon. A tile shows its shortcut under its name.")
        }
        .animation(.tandem, value: tiles)
    }

    /// One thing on two tiles would waste one: picking it where it already is swaps the two.
    private func choose(_ action: PanelAction, at index: Int) {
        var next = tiles
        if let other = next.firstIndex(of: action), other != index { next[other] = next[index] }
        next[index] = action
        set(next)
    }

    private func set(_ next: [PanelAction]) {
        tiles = next
        PanelAction.tiles = next
    }
}

/// A shortcut for every action, in one list: recorded by pressing it, cleared with the cross. They work in every app.
struct ShortcutsSection: View {
    @LocalState private var recording: PanelAction?
    @LocalState private var monitor: Any?
    /// Read again after every change, from wherever each shortcut is kept (see `ActionShortcuts`).
    @LocalState private var shortcuts: [PanelAction: Shortcut] = [:]
    @LocalState private var clash: String?

    var body: some View {
        Section {
            ForEach(PanelAction.allCases) { action in
                LabeledContent {
                    HStack(spacing: 6) {
                        ShortcutButton(display: shortcuts[action]?.display ?? String(localized: "None"), recording: recording == action) {
                            recording == action ? stop() : start(action)
                        }
                        Button {
                            ActionShortcuts.shared.set(nil, for: action)
                            reload()
                        } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                        }
                        .buttonStyle(.borderless)
                        .help("No shortcut")
                        .opacity(shortcuts[action] == nil ? 0 : 1)
                        .disabled(shortcuts[action] == nil)
                    }
                } label: {
                    ActionLabel(action: action)
                }
            }
        } header: {
            Text("Shortcuts")
        } footer: {
            Text(clash ?? String(localized: "Click a shortcut and press the new keys, with Command, Control or Option. Escape keeps the old one. They work in every app."))
                .foregroundStyle(clash == nil ? Color.secondary : Palette.urgent)
        }
        .onAppear(perform: reload)
        .onDisappear(perform: stop)
    }

    private func reload() {
        var map: [PanelAction: Shortcut] = [:]
        for action in PanelAction.allCases { map[action] = ActionShortcuts.shared.shortcut(for: action) }
        shortcuts = map
    }

    private func start(_ action: PanelAction) {
        stop()
        clash = nil
        recording = action
        ActionShortcuts.shared.pause(true)
        monitor = NSEvent.addLocalMonitorForEvents(matching: .keyDown) { event in
            if event.keyCode == 53 {
                stop()
                return nil
            }
            let modifiers = Shortcut.carbonModifiers(from: event.modifierFlags)
            // A shortcut that works everywhere needs a modifier that no typing uses.
            guard modifiers & UInt32(cmdKey | controlKey | optionKey) != 0 else {
                NSSound.beep()
                return nil
            }
            let key = Shortcut.name(forKeyCode: event.keyCode) ?? event.charactersIgnoringModifiers?.uppercased() ?? "?"
            let shortcut = Shortcut(keyCode: UInt32(event.keyCode), modifiers: modifiers, key: key)
            // One combination starts one thing.
            if let owner = ActionShortcuts.shared.owner(of: shortcut), owner != action {
                NSSound.beep()
                clash = String(localized: "\(shortcut.display) already starts \(owner.title).")
                return nil
            }
            ActionShortcuts.shared.set(shortcut, for: action)
            stop()
            return nil
        }
    }

    private func stop() {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
        if recording != nil {
            recording = nil
            ActionShortcuts.shared.pause(false)
        }
        reload()
    }
}

/// The symbol in a column of its own width and the name after it, so the names in the list start in one line whatever the symbol.
private struct ActionLabel: View {
    let action: PanelAction

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: action.symbol)
                .foregroundStyle(.secondary)
                .frame(width: 22)
            Text(verbatim: action.title)
        }
    }
}

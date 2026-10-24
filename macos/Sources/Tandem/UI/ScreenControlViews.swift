import AppKit
import SwiftUI
import TandemCore

// MARK: - Floating panels

/// The two things of a remote desktop that must be seen without opening Tandem: the question when a device wants in, and
/// the bar that lists every device that looks at this Mac or shows an extra screen of it, each with its own Stop. Both are
/// glass panels at the top of the main screen, above everything, in every space.
@MainActor
final class ScreenPanels {
    static let shared = ScreenPanels()

    private var promptPanel: NSPanel?
    private var indicatorPanel: NSPanel?
    private var shownPrompt: UInt64?
    private var shownIndicator: [String] = []

    /// Shows, moves or hides the panels to match what the host is doing. Cheap to call after any change.
    func update() {
        let host = ScreenHost.shared

        if let prompt = host.prompt {
            if shownPrompt != prompt.id || promptPanel?.isVisible != true {
                let panel = promptPanel ?? makePanel()
                promptPanel = panel
                let view = NSHostingView(rootView: ScreenPromptView(host: host))
                panel.contentView = view
                panel.setContentSize(view.fittingSize)
                panel.orderFrontRegardless()
                shownPrompt = prompt.id
            }
        } else if let panel = promptPanel, panel.isVisible {
            panel.orderOut(nil)
            shownPrompt = nil
        }

        // A new view whenever a device comes, goes, or may do something else: the panel takes the size of the view, and a view that was
        // there measures what it showed before until SwiftUI draws it again.
        let shown = host.running.map { "\($0.id) \($0.control)" }
        if !shown.isEmpty {
            if shownIndicator != shown || indicatorPanel?.isVisible != true {
                let panel = indicatorPanel ?? makePanel()
                indicatorPanel = panel
                let view = NSHostingView(rootView: ScreenIndicatorView(host: host))
                panel.contentView = view
                panel.setContentSize(view.fittingSize)
                panel.orderFrontRegardless()
                shownIndicator = shown
            }
        } else if let panel = indicatorPanel, panel.isVisible {
            panel.orderOut(nil)
            shownIndicator = []
        }
        layout()
    }

    private func makePanel() -> NSPanel {
        let panel = NSPanel(
            contentRect: NSRect(x: 0, y: 0, width: 420, height: 120),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.level = .statusBar
        panel.isReleasedWhenClosed = false
        panel.hidesOnDeactivate = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        return panel
    }

    /// Where the lowest of these panels starts, when one is showing: another panel at the top of the screen goes below it, not over it.
    var lowestEdge: CGFloat? {
        [promptPanel, indicatorPanel].compactMap { $0 }.filter(\.isVisible).map(\.frame.minY).min()
    }

    /// The question at the top and the bar under it: a device that asks while others are already in is the usual case now, and the
    /// question would otherwise cover the bar. The question has 36 points of room for its shadow under it and the bar 12 above it, so
    /// starting the bar 36 above the bottom of the question leaves 12 points between the glass.
    private func layout() {
        guard let screen = NSScreen.main else { return }
        let area = screen.visibleFrame
        var top = area.maxY - 10
        if let panel = promptPanel, panel.isVisible {
            panel.setFrameOrigin(NSPoint(x: area.midX - panel.frame.width / 2, y: top - panel.frame.height))
            top = panel.frame.minY + 36
        }
        if let panel = indicatorPanel, panel.isVisible {
            panel.setFrameOrigin(NSPoint(x: area.midX - panel.frame.width / 2, y: top - panel.frame.height))
        }
    }
}

private struct ScreenPromptView: View {
    let host: ScreenHost

    var body: some View {
        if let prompt = host.prompt {
            VStack(alignment: .leading, spacing: 14) {
                PromptHeader(
                    symbol: prompt.symbol,
                    title: Text(prompt.extend ? "\(prompt.name) wants to use a screen of this Mac" : prompt.wantsControl ? "\(prompt.name) wants to control this Mac" : "\(prompt.name) wants to see this Mac"),
                    message: Text("It sees everything on your screen until you stop it. A bar at the top of your screen shows when it does, with a Stop button.")
                )
                if prompt.wantsControl {
                    controlRow(prompt)
                }
                HStack(spacing: 8) {
                    Button("Deny") { host.answer(.deny) }
                        .buttonStyle(.tandemGlass)
                    Spacer(minLength: 0)
                    Button("Allow once") { host.answer(.allowOnce) }
                        .buttonStyle(.tandemGlass)
                    Button("Always allow") { host.answer(.always) }
                        .buttonStyle(.tandemGlassProminent)
                }
                .controlSize(.large)
            }
            .floatingCard(width: 420)
        }
    }

    @ViewBuilder
    private func controlRow(_ prompt: ScreenHost.Prompt) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Toggle(isOn: Binding(get: { prompt.allowControl && host.accessibility }, set: { host.setPromptControl($0) })) {
                Text("Let it use the mouse and keyboard")
            }
            .disabled(!host.accessibility)
            if !host.accessibility {
                HStack(spacing: 8) {
                    Text("Needs Accessibility. Without it, it can only watch.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    Button("Allow…") { host.requestAccessibility() }
                        .buttonStyle(.tandemGlass)
                        .controlSize(.small)
                }
            }
        }
        .padding(12)
        .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: Metrics.inner(Metrics.panel, inset: 10), style: .continuous))
        .task {
            while !Task.isCancelled {
                host.refreshPermissions()
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }
}

private struct ScreenIndicatorView: View {
    let host: ScreenHost

    var body: some View {
        let running = host.running
        if !running.isEmpty {
            GlassEffectContainer(spacing: 0) {
                VStack(spacing: 8) {
                    ForEach(running) { session in
                        row(session)
                    }
                }
                // Every row as wide as the widest, so the Stop buttons stand under each other.
                .fixedSize(horizontal: true, vertical: false)
            }
            .padding(EdgeInsets(top: 12, leading: 24, bottom: 30, trailing: 24))
        }
    }

    private func row(_ session: ScreenHost.Remote) -> some View {
        HStack(spacing: 10) {
            PulsingSymbol(
                name: session.extend ? "rectangle.on.rectangle" : session.control ? "cursorarrow.rays" : "eye",
                pointSize: 15, weight: .semibold, color: NSColor(Palette.urgent)
            )
            .frame(width: 22, height: 22)
            Text(session.extend ? "Extra screen on \(session.name)" : session.control ? "\(session.name) is controlling this Mac" : "\(session.name) is watching this Mac")
                .font(.callout.weight(.medium))
                .lineLimit(1)
            Spacer(minLength: 0)
            if session.offered {
                Button(session.control ? "Pause control" : "Resume control") { host.setControl(!session.control, session: session.id) }
                    .buttonStyle(.tandemGlass)
                    .controlSize(.small)
            }
            Button("Stop") { host.stop(session: session.id) }
                .buttonStyle(.tandemGlassProminent(tint: Palette.urgent))
                .controlSize(.small)
        }
        .padding(.leading, 16)
        .padding(.trailing, 8)
        .padding(.vertical, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        .floatingGlass(in: .capsule)
        .shadow(color: .black.opacity(0.14), radius: 14, y: 6)
    }
}

// MARK: - Settings

/// Who may look at this Mac and control it. The choices are kept, and enforced, by the core, so this page only shows
/// them and changes them. The permissions macOS wants for it are here too, with the honest state of each.
struct ScreenSettings: View {
    @Environment(EngineModel.self) private var model
    @LocalState private var target: String?
    @LocalState private var version = 0
    @LocalState private var tick = 0

    private var host: ScreenHost { ScreenHost.shared }
    private var engine: TandemEngine? { model.tandem }

    private var policy: TandemMediaPolicy? {
        _ = version
        guard let engine else { return nil }
        if let target { return try? engine.mediaPolicy(id: target) }
        return engine.mediaDefaultPolicy()
    }

    private var own: Bool {
        guard let target else { return true }
        _ = version
        return engine?.hasOwnMediaPolicy(id: target) ?? false
    }

    private var editable: Bool { target == nil || own }

    var body: some View {
        _ = tick
        return Form {
            Section("Sound") {
                Picker("Sound of a screen you look at", selection: Binding(
                    get: { UserDefaults.standard.string(forKey: ScreenSound.whereKey) ?? "there" },
                    set: { UserDefaults.standard.set($0, forKey: ScreenSound.whereKey); tick += 1 }
                )) {
                    Text("Plays on that device").tag("there")
                    Text("Comes to this Mac").tag("here")
                }
                DescribedToggle(
                    "Ask when headphones are in",
                    subtitle: "With Bluetooth headphones or something in the jack, asks whether the sound should come here this time.",
                    isOn: Binding(
                        get: { ScreenSound.askWithHeadphones },
                        set: { UserDefaults.standard.set($0, forKey: ScreenSound.askKey); tick += 1 }
                    )
                )
                .disabled(ScreenSound.choice == "here")
            }
            Section {
                DescribedToggle(
                    "Let my other devices see and control this Mac",
                    subtitle: "Your phone shows a Control this Mac button on this Mac's page once it can. You still decide for every device below.",
                    isOn: Binding(get: { host.enabled }, set: { host.enabled = $0 })
                )
                if host.needsRestart {
                    HStack {
                        Label("Restart Tandem so your other devices can see that this Mac is ready.", systemImage: "arrow.clockwise")
                            .font(.callout)
                        Spacer(minLength: 8)
                        Button("Restart") { host.relaunch() }.buttonStyle(.borderedProminent).tint(Palette.indigo)
                    }
                }
            }

            Section("Permissions") {
                permissionRow(
                    symbol: "rectangle.dashed.badge.record", title: "Screen Recording",
                    why: "To send the picture of your screen. macOS asks for it once.",
                    allowed: ScreenCapturer.hasPermission,
                    allow: { host.requestScreenRecording() },
                    pane: "Privacy_ScreenCapture"
                )
                permissionRow(
                    symbol: "cursorarrow.motionlines", title: "Accessibility",
                    why: "To move the pointer and press keys for a device you let control this Mac. Without it, devices can only watch.",
                    allowed: host.accessibility,
                    allow: { host.requestAccessibility() },
                    pane: "Privacy_Accessibility"
                )
            }

            if !host.running.isEmpty {
                Section("Right now") {
                    ForEach(host.running) { running in
                        HStack {
                            Label(
                                running.extend ? "Extra screen on \(running.name)" : running.control ? "\(running.name) is controlling this Mac" : "\(running.name) is watching this Mac",
                                systemImage: running.extend ? "rectangle.on.rectangle" : "dot.radiowaves.left.and.right"
                            )
                            Spacer(minLength: 8)
                            Button("Stop") { host.stop(session: running.id) }.buttonStyle(.borderedProminent).tint(Palette.urgent)
                        }
                    }
                }
            }

            Section {
                Picker("For", selection: $target) {
                    Text("All devices").tag(String?.none)
                    ForEach(model.devices, id: \.id) { device in
                        Text(device.name).tag(String?.some(device.id))
                    }
                }
                if let target {
                    DescribedToggle(
                        "Own settings for this device",
                        subtitle: "Otherwise it follows the settings for all devices",
                        isOn: Binding(
                            get: { own },
                            set: { wantOwn in
                                if wantOwn {
                                    if let policy { try? engine?.setMediaPolicy(id: target, policy: policy) }
                                } else {
                                    try? engine?.clearMediaPolicy(id: target)
                                }
                                version += 1
                            }
                        )
                    )
                }
            }

            if let policy {
                Section("When a device asks") {
                    Picker("Looking at this Mac", selection: Binding(get: { policy.screen }, set: { value in change { $0.screen = value } })) {
                        permissionTags
                    }
                    Picker("Using the mouse and keyboard", selection: Binding(get: { policy.control }, set: { value in change { $0.control = value } })) {
                        permissionTags
                    }
                    Text("Ask shows a question on this Mac every time. Always allow lets that device in without asking. Never turns it away without a word.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .disabled(!editable)
            }
        }
        .formStyle(.grouped)
        .task {
            // Permissions change outside the app, so look again while this page is open.
            while !Task.isCancelled {
                host.refreshPermissions()
                tick += 1
                try? await Task.sleep(for: .seconds(1.5))
            }
        }
    }

    @ViewBuilder
    private var permissionTags: some View {
        Text("Ask me").tag(TandemMediaPermission.ask)
        Text("Always allow").tag(TandemMediaPermission.always)
        Text("Never").tag(TandemMediaPermission.never)
    }

    private func change(_ edit: (inout TandemMediaPolicy) -> Void) {
        guard let engine, var next = policy else { return }
        edit(&next)
        if let target {
            try? engine.setMediaPolicy(id: target, policy: next)
        } else {
            try? engine.setMediaDefaultPolicy(policy: next)
        }
        version += 1
    }

    private func permissionRow(symbol: String, title: LocalizedStringKey, why: LocalizedStringKey, allowed: Bool, allow: @escaping () -> Void, pane: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .medium))
                .foregroundStyle(Palette.indigo)
                .frame(width: 30, height: 30)
                .background(Palette.indigo.opacity(0.12), in: .circle)
            VStack(alignment: .leading, spacing: 4) {
                // Someone may not want to be looked at: a permission that is not given is not a warning, the row just has its buttons.
                HStack(spacing: 8) {
                    Text(title).font(.callout.weight(.semibold))
                    if allowed {
                        Text("Allowed").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Text(why).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                if !allowed {
                    HStack(spacing: 8) {
                        Button("Allow", action: allow)
                        Button("Open System Settings") { host.openSettings(pane: pane) }
                    }
                    .padding(.top, 2)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }
}

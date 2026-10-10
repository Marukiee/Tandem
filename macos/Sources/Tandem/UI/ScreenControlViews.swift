import AppKit
import SwiftUI
import TandemCore

// MARK: - Floating panels

/// The two things of a remote desktop that must be seen without opening Tandem: the question when a device wants in, and
/// the bar that says someone is looking at this Mac and lets you stop it. Both are glass panels at the top of the main
/// screen, above everything, in every space.
@MainActor
final class ScreenPanels {
    static let shared = ScreenPanels()

    private var promptPanel: NSPanel?
    private var indicatorPanel: NSPanel?
    private var shownPrompt: UInt64?
    private var shownIndicator: UInt64?

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
                place(panel, row: 0)
                panel.orderFrontRegardless()
                shownPrompt = prompt.id
            }
        } else if let panel = promptPanel, panel.isVisible {
            panel.orderOut(nil)
            shownPrompt = nil
        }

        if let running = host.current {
            if shownIndicator != running.id || indicatorPanel?.isVisible != true {
                let panel = indicatorPanel ?? makePanel()
                indicatorPanel = panel
                let view = NSHostingView(rootView: ScreenIndicatorView(host: host))
                panel.contentView = view
                panel.setContentSize(view.fittingSize)
                place(panel, row: host.prompt == nil ? 0 : 1)
                panel.orderFrontRegardless()
                shownIndicator = running.id
            } else if let panel = indicatorPanel, let view = panel.contentView {
                // The text changes with who is controlling and with what, so the width follows.
                let size = view.fittingSize
                if abs(size.width - panel.frame.width) > 1 { panel.setContentSize(size); place(panel, row: 0) }
            }
        } else if let panel = indicatorPanel, panel.isVisible {
            panel.orderOut(nil)
            shownIndicator = nil
        }
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

    private func place(_ panel: NSPanel, row: Int) {
        guard let screen = NSScreen.main else { return }
        let area = screen.visibleFrame
        let size = panel.frame.size
        let y = area.maxY - size.height - 10 - CGFloat(row) * 76
        panel.setFrameOrigin(NSPoint(x: area.midX - size.width / 2, y: y))
    }
}

private struct ScreenPromptView: View {
    let host: ScreenHost

    var body: some View {
        if let prompt = host.prompt {
            VStack(alignment: .leading, spacing: 14) {
                HStack(alignment: .top, spacing: 14) {
                    Image(systemName: prompt.symbol)
                        .font(.system(size: 17, weight: .medium))
                        .foregroundStyle(Palette.indigo)
                        .frame(width: 40, height: 40)
                        .background(Palette.indigo.opacity(0.14), in: .circle)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(prompt.extend ? "\(prompt.name) wants to use a screen of this Mac" : prompt.wantsControl ? "\(prompt.name) wants to control this Mac" : "\(prompt.name) wants to see this Mac")
                            .font(.headline)
                        Text("It sees everything on your screen until you stop it. A bar at the top of your screen shows when it does, with a Stop button.")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                if prompt.wantsControl {
                    controlRow(prompt)
                }
                HStack(spacing: 8) {
                    Button("Deny") { host.answer(.deny) }
                        .buttonStyle(.glass)
                    Spacer(minLength: 0)
                    Button("Allow once") { host.answer(.allowOnce) }
                        .buttonStyle(.glass)
                    Button("Always allow") { host.answer(.always) }
                        .buttonStyle(.glassProminent)
                }
                .controlSize(.large)
            }
            .padding(18)
            .frame(width: 420)
            .glassEffect(.regular, in: RoundedRectangle(cornerRadius: 26, style: .continuous))
            .shadow(color: .black.opacity(0.18), radius: 18, y: 8)
            .padding(10)
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
                        .buttonStyle(.glass)
                        .controlSize(.small)
                }
            }
        }
        .padding(12)
        .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
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
        if let running = host.current {
            HStack(spacing: 10) {
                PulsingSymbol(name: running.control ? "cursorarrow.rays" : "eye", pointSize: 15, weight: .semibold, color: NSColor(Palette.urgent))
                    .frame(width: 22, height: 22)
                Text(running.control ? "\(running.name) is controlling this Mac" : "\(running.name) is watching this Mac")
                    .font(.callout.weight(.medium))
                    .lineLimit(1)
                if running.offered {
                    Button(running.control ? "Pause control" : "Resume control") { host.setControl(!running.control) }
                        .buttonStyle(.glass)
                        .controlSize(.small)
                }
                Button("Stop") { host.stopCurrent() }
                    .buttonStyle(.glassProminent)
                    .tint(Palette.urgent)
                    .controlSize(.small)
            }
            .padding(.leading, 16)
            .padding(.trailing, 8)
            .padding(.vertical, 8)
            .glassEffect(.regular, in: .capsule)
            .shadow(color: .black.opacity(0.14), radius: 14, y: 6)
            .padding(10)
            .animation(.tandem, value: running.control)
        }
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
                        Button("Restart") { host.relaunch() }.buttonStyle(.glassProminent).controlSize(.small)
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

            if let running = host.current {
                Section("Right now") {
                    HStack {
                        Label(running.control ? "\(running.name) is controlling this Mac" : "\(running.name) is watching this Mac", systemImage: "dot.radiowaves.left.and.right")
                        Spacer(minLength: 8)
                        Button("Stop") { host.stopCurrent() }.buttonStyle(.glassProminent).tint(Palette.urgent).controlSize(.small)
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
                HStack(spacing: 8) {
                    Text(title).font(.callout.weight(.semibold))
                    Text(allowed ? "Allowed" : "Not allowed yet")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(allowed ? Color.green : Color.orange)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 2)
                        .background((allowed ? Color.green : Color.orange).opacity(0.14), in: .capsule)
                }
                Text(why).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                if !allowed {
                    HStack(spacing: 8) {
                        Button("Allow", action: allow).buttonStyle(.glassProminent).controlSize(.small)
                        Button("Open System Settings") { host.openSettings(pane: pane) }.buttonStyle(.glass).controlSize(.small)
                    }
                    .padding(.top, 2)
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.vertical, 4)
    }
}

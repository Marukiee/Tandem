import AppKit
import SwiftUI
import TandemCore

/// The content of a live window: the picture, what is going on when there is none, and the controls that float over it.
struct LiveView: View {
    @Environment(EngineModel.self) private var model
    let session: LiveSession
    let controller: LiveWindowController

    @LocalState private var controlsVisible = true
    @LocalState private var hideTask: Task<Void, Never>?
    @LocalState private var overToolbar = false
    @LocalState private var showInfo = false

    private var phoneOnline: Bool { model.device(session.peer)?.online ?? (session.peer == "debug") }

    var body: some View {
        ZStack {
            Color.black
            VideoSurface(surface: session.surface, orientation: session.orientation)
                .opacity(session.hasPicture ? 1 : 0)
                .animation(.tandemFade, value: session.hasPicture)

            overlay

            if session.showStats, let stats = session.stats, case .active = session.phase {
                VStack {
                    HStack {
                        Text(stats.line)
                            .font(.system(.caption, design: .monospaced))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 7)
                            .liveGlass(in: .capsule)
                        Spacer()
                    }
                    Spacer()
                }
                .padding(12)
                .transition(.opacity)
            }

            VStack {
                Spacer()
                if controlsVisible || !session.isRunning || !session.hasPicture {
                    toolbar
                        .padding(.bottom, 14)
                        .transition(.move(edge: .bottom).combined(with: .opacity).combined(with: .scale(scale: 0.94)))
                }
            }
        }
        .frame(minWidth: 160, minHeight: 90)
        .onContinuousHover { phase in
            switch phase {
            case .active: wake()
            case .ended: scheduleHide()
            }
        }
        .animation(.tandem, value: controlsVisible)
        .animation(.tandem, value: session.phase)
        .animation(.tandemFade, value: session.showStats)
        .onAppear { scheduleHide() }
    }

    // MARK: Controls that come and go

    private func wake() {
        controlsVisible = true
        scheduleHide()
    }

    private func scheduleHide() {
        hideTask?.cancel()
        // A snapshot run wants to see the controls.
        guard !DebugSupport.flatGlass else { return }
        hideTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(2.6))
            guard !Task.isCancelled, !overToolbar else { return }
            controlsVisible = false
        }
    }

    // MARK: Overlay for the states without a picture

    @ViewBuilder
    private var overlay: some View {
        let name = session.phoneName
        switch session.phase {
        case .requesting:
            StateCard(copy: .requesting(name: name, kind: session.kind), busy: true, footnote: footnote)
        case .active:
            if !session.hasPicture {
                StateCard(copy: .firstPicture(kind: session.kind), busy: true, footnote: nil)
            } else if !phoneOnline {
                StateCard(
                    copy: LiveCopy(
                        title: String(localized: "Connection to \(name) lost"),
                        detail: String(localized: "Trying to get it back. The picture continues if it works."),
                        symbol: "wifi.slash", canRetry: false
                    ),
                    busy: true, footnote: nil
                )
            }
        case let .ended(end):
            StateCard(
                copy: .ended(end, name: name, kind: session.kind), busy: false, footnote: nil,
                // Close is the one that is coloured: after a stop it is what you came to do, and Try again is the other choice.
                primary: (String(localized: "Close"), { controller.close() }),
                secondary: LiveCopy.ended(end, name: name, kind: session.kind).canRetry ? (String(localized: "Try again"), { LiveManager.shared.restart(session) }) : nil
            )
        }
    }

    /// Only the camera needs saying: people expect it to turn up as a webcam in a call, and it does not.
    private var footnote: String? {
        session.kind == .camera ? Self.webcamNote : nil
    }

    static let webcamNote = String(localized: "The camera opens in this window. It is not a virtual webcam: to use it in Zoom or OBS, share or capture this window.")

    // MARK: Toolbar

    /// All the buttons when the window is wide enough, and the three that matter with the rest in a menu when it is not.
    private var toolbar: some View {
        ViewThatFits(in: .horizontal) {
            fullToolbar
            compactToolbar
        }
        .onHover { overToolbar = $0 }
    }

    private var compactToolbar: some View {
        GlassEffectContainer(spacing: 10) {
            HStack(spacing: 10) {
                if session.isRunning {
                    button("stop.fill", help: "Stop", tint: Palette.urgent) { LiveManager.shared.stop(session) }
                }
                button("camera.viewfinder", help: "Copy a picture to the clipboard") { controller.copyScreenshot() }
                    .disabled(!session.hasPicture)
                Menu {
                    Button("Turn the picture a quarter", systemImage: "rotate.right") { turn() }
                        .disabled(!session.hasPicture)
                    if session.kind == .camera {
                        Toggle("Mirror the picture", systemImage: "arrow.left.and.right.righttriangle.left.righttriangle.right", isOn: Binding(get: { session.mirrored }, set: { session.mirrored = $0 }))
                        Picker("Camera", selection: Binding(get: { session.cameraFacing }, set: { choose(facing: $0, quality: session.quality) })) {
                            Text("Back camera").tag(LiveCameraFacing.back)
                            Text("Front camera").tag(LiveCameraFacing.front)
                        }
                        Picker("Quality", selection: Binding(get: { session.quality }, set: { choose(facing: session.cameraFacing, quality: $0) })) {
                            Text("720p").tag(LiveQuality.standard)
                            Text("1080p").tag(LiveQuality.high)
                        }
                    }
                    Toggle("Keep this window on top", systemImage: "pin", isOn: Binding(get: { session.alwaysOnTop }, set: { controller.setAlwaysOnTop($0) }))
                    Toggle("Floating window without a frame", systemImage: "rectangle.dashed", isOn: Binding(get: { session.frameless }, set: { controller.setFrameless($0) }))
                    Toggle("Show statistics", isOn: Binding(get: { session.showStats }, set: { session.showStats = $0 }))
                    Divider()
                    Button("Close window") { controller.close() }
                } label: {
                    Image(systemName: "ellipsis")
                        .font(.system(size: 15, weight: .semibold))
                        .frame(width: 20, height: 20)
                }
                .menuStyle(.button)
                .menuIndicator(.hidden)
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .controlSize(.large)
                .fixedSize()
            }
            .padding(8)
        }
    }

    private func turn() {
        session.extraRotation = (session.extraRotation + 90) % 360
        controller.contentSizeChanged()
    }

    private var fullToolbar: some View {
        GlassEffectContainer(spacing: 10) {
            HStack(spacing: 10) {
                if session.isRunning {
                    button("stop.fill", help: "Stop", tint: Palette.urgent) { LiveManager.shared.stop(session) }
                        .keyboardShortcut(".", modifiers: .command)
                }
                button("camera.viewfinder", help: "Copy a picture to the clipboard") { controller.copyScreenshot() }
                    .keyboardShortcut("c", modifiers: [.command, .shift])
                    .disabled(!session.hasPicture)
                button("rotate.right", help: "Turn the picture a quarter") { turn() }
                    .disabled(!session.hasPicture)
                if session.kind == .camera {
                    button(
                        "arrow.left.and.right.righttriangle.left.righttriangle.right",
                        help: "Mirror the picture", active: session.mirrored
                    ) { session.mirrored.toggle() }
                    cameraMenu
                }
                button(session.alwaysOnTop ? "pin.fill" : "pin", help: "Keep this window on top", active: session.alwaysOnTop) {
                    controller.setAlwaysOnTop(!session.alwaysOnTop)
                }
                button(
                    session.frameless ? "macwindow" : "rectangle.dashed",
                    help: session.frameless ? "Show the title bar again" : "Floating window without a frame", active: session.frameless
                ) { controller.setFrameless(!session.frameless) }
                moreMenu
            }
            .padding(8)
        }
    }

    private func button(
        _ symbol: String, help: LocalizedStringKey, tint: Color? = nil, active: Bool = false, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .frame(width: 20, height: 20)
                .foregroundStyle(tint ?? (active ? Palette.indigoLight : Color.primary))
        }
        .buttonStyle(.glass)
        .buttonBorderShape(.circle)
        .controlSize(.large)
        .help(help)
    }

    /// The side of the phone's camera and how sharp the picture is. A change asks the phone again: it is the same window.
    private var cameraMenu: some View {
        Menu {
            Picker("Camera", selection: Binding(
                get: { session.cameraFacing },
                set: { choose(facing: $0, quality: session.quality) }
            )) {
                Text("Back camera").tag(LiveCameraFacing.back)
                Text("Front camera").tag(LiveCameraFacing.front)
            }
            .pickerStyle(.inline)
            Picker("Quality", selection: Binding(
                get: { session.quality },
                set: { choose(facing: session.cameraFacing, quality: $0) }
            )) {
                Text("720p").tag(LiveQuality.standard)
                Text("1080p").tag(LiveQuality.high)
            }
            .pickerStyle(.inline)
        } label: {
            Image(systemName: "arrow.triangle.2.circlepath.camera")
                .font(.system(size: 15, weight: .semibold))
                .frame(width: 20, height: 20)
        }
        .menuStyle(.button)
        .menuIndicator(.hidden)
        .buttonStyle(.glass)
        .buttonBorderShape(.circle)
        .controlSize(.large)
        .fixedSize()
        .help("Camera and quality")
    }

    private func choose(facing: LiveCameraFacing, quality: LiveQuality) {
        guard facing != session.cameraFacing || quality != session.quality else { return }
        guard let device = model.device(session.peer) else { return }
        LiveManager.shared.start(device: device, kind: .camera, facing: facing, quality: quality)
    }

    private var moreMenu: some View {
        Menu {
            Toggle("Show statistics", isOn: Binding(get: { session.showStats }, set: { session.showStats = $0 }))
            if session.kind == .camera {
                Divider()
                Button("About using this in a call…") { showInfo = true }
            }
            Divider()
            Button("Close window") { controller.close() }
        } label: {
            Image(systemName: "ellipsis")
                .font(.system(size: 15, weight: .semibold))
                .frame(width: 20, height: 20)
        }
        .menuStyle(.button)
        .menuIndicator(.hidden)
        .buttonStyle(.glass)
        .buttonBorderShape(.circle)
        .controlSize(.large)
        .fixedSize()
        .popover(isPresented: $showInfo, arrowEdge: .top) {
            VStack(alignment: .leading, spacing: 8) {
                Label("A window, not a webcam", systemImage: "camera.badge.ellipsis").font(.headline)
                Text(Self.webcamNote).font(.callout).foregroundStyle(.secondary)
            }
            .padding(16)
            .frame(width: 280)
        }
    }
}

/// What a window says while there is no picture: waiting, starting, or why it ended, with the way on.
private struct StateCard: View {
    let copy: LiveCopy
    let busy: Bool
    let footnote: String?
    var primary: (String, () -> Void)?
    var secondary: (String, () -> Void)?

    var body: some View {
        VStack(spacing: 14) {
            if busy {
                PillSpinner(size: 34)
            } else {
                Image(systemName: copy.symbol)
                    .font(.system(size: 30, weight: .semibold))
                    .foregroundStyle(Palette.indigoLight)
                    .frame(height: 34)
            }
            VStack(spacing: 6) {
                Text(copy.title)
                    .font(.headline)
                    .multilineTextAlignment(.center)
                if !copy.detail.isEmpty {
                    Text(copy.detail)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
            if primary != nil || secondary != nil {
                HStack(spacing: 10) {
                    if let secondary {
                        Button(secondary.0, action: secondary.1).buttonStyle(.glass)
                    }
                    if let primary {
                        Button(primary.0, action: primary.1).buttonStyle(.glassProminent).tint(Palette.indigo)
                    }
                }
                .controlSize(.large)
            }
            if let footnote {
                Text(footnote)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.top, 2)
            }
        }
        .padding(24)
        .frame(maxWidth: 340)
        .liveGlass(in: .rect(cornerRadius: 28, style: .continuous))
        .padding(20)
        .transition(.scale(scale: 0.94).combined(with: .opacity))
    }
}

extension View {
    /// Glass, except in a debug run that takes snapshots, where it is a plain material (see `DebugSupport.flatGlass`).
    @ViewBuilder
    func liveGlass<S: Shape>(in shape: S) -> some View {
        if DebugSupport.flatGlass {
            background(.regularMaterial, in: shape)
        } else {
            glassEffect(.regular, in: shape)
        }
    }
}

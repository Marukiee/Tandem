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
    /// How tall the view is, to tell when the pointer is at the bottom edge where the toolbar lives.
    @LocalState private var viewHeight: CGFloat = 0
    @LocalState private var showInfo = false
    @LocalState private var showMore = false
    @LocalState private var showCamera = false
    @LocalState private var showControlHelp = false
    /// The help for clicking on the phone opens by itself once, when the picture is there and the phone does not allow it yet.
    @LocalState private var controlHelpOffered = false

    /// The other side is a computer (a remote desktop) and not a phone, which changes what there is to tell about clicking.
    private var peerIsComputer: Bool { model.device(session.peer)?.isComputer ?? false }

    private var phoneOnline: Bool { model.device(session.peer)?.online ?? (session.peer == "debug") }

    var body: some View {
        ZStack {
            // Black behind a picture (it is the edge of it); a calm dark backdrop while there is none, so a waiting window is not a hole.
            LiveBackdrop(waiting: !session.hasPicture)
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

            // The name of the phone, only while the pointer is at the top (the title bar of the window is not drawn).
            VStack {
                if session.titleBarShown {
                    VStack(spacing: 1) {
                        Text(session.title).font(.callout.weight(.semibold))
                        Text(session.subtitle).font(.caption2).foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 6)
                    .liveGlass(in: .capsule)
                    .padding(.top, 8)
                    .transition(.move(edge: .top).combined(with: .opacity))
                }
                Spacer()
            }
            .allowsHitTesting(false)
        }
        .ignoresSafeArea()
        .frame(minWidth: 160, minHeight: 90)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { viewHeight = $0 }
        .onContinuousHover { phase in
            switch phase {
            case let .active(point):
                // The toolbar belongs to the bottom edge. Elsewhere over the picture the pointer is the one of the other computer, and
                // buttons that follow it around hide the taskbar and the dock that are down there.
                if !session.hasPicture || viewHeight <= 0 || point.y >= viewHeight - 120 { wake() } else { scheduleHide(after: 0.4) }
            case .ended: scheduleHide()
            }
        }
        .animation(.tandem, value: controlsVisible)
        .animation(.tandem, value: session.phase)
        .animation(.tandemFade, value: session.showStats)
        .animation(.tandem, value: session.titleBarShown)
        .onAppear { scheduleHide() }
    }

    // MARK: Controls that come and go

    private func wake() {
        controlsVisible = true
        scheduleHide()
    }

    private func scheduleHide(after seconds: Double = 2.6) {
        hideTask?.cancel()
        // A snapshot run wants to see the controls.
        guard !DebugSupport.flatGlass else { return }
        hideTask = Task { @MainActor in
            try? await Task.sleep(for: .seconds(seconds))
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
            StateCard(copy: .requesting(name: name, kind: session.kind, computer: peerIsComputer), busy: true, footnote: footnote)
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
                copy: .ended(end, name: name, kind: session.kind, computer: peerIsComputer), busy: false, footnote: nil,
                // Close is the one that is coloured: after a stop it is what you came to do, and Try again is the other choice.
                primary: (String(localized: "Close"), { controller.close() }),
                secondary: LiveCopy.ended(end, name: name, kind: session.kind, computer: peerIsComputer).canRetry ? (String(localized: "Try again"), { LiveManager.shared.restart(session) }) : nil
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
        .onChange(of: session.hasPicture) { _, has in
            guard has, session.kind == .screen, !session.controlGranted, !controlHelpOffered else { return }
            controlHelpOffered = true
            Task { @MainActor in
                try? await Task.sleep(for: .seconds(1.5))
                if !session.controlGranted { showControlHelp = true }
            }
        }
    }

    private var compactToolbar: some View {
        GlassEffectContainer(spacing: 10) {
            HStack(spacing: 10) {
                if session.isRunning { stopButton }
                button("camera.viewfinder", help: "Copy a picture to the clipboard") { controller.copyScreenshot() }
                    .disabled(!session.hasPicture)
                button("ellipsis", help: "More") { showMore.toggle() }
                    .popover(isPresented: $showMore, arrowEdge: .top) { menuRows(everything: true) }
            }
            .padding(8)
        }
    }

    private var remoteIsMac: Bool { model.device(session.peer)?.platform == .macOs }

    /// Presses a key there with some modifiers and lets go of it.
    private func pressThere(usage: UInt32, mods: UInt16) {
        LiveManager.shared.sendInput(session, .key(code: usage, down: true, mods: mods, text: ""))
        LiveManager.shared.sendInput(session, .key(code: usage, down: false, mods: mods, text: ""))
    }

    /// The text on the clipboard of this Mac is typed over there, for what does not come over by itself.
    private func typeClipboard() {
        guard let text = NSPasteboard.general.string(forType: .string), !text.isEmpty else {
            FloatingToast.show(String(localized: "There is no text on the clipboard"), symbol: "doc.on.clipboard")
            return
        }
        LiveManager.shared.sendInput(session, .text(text: String(text.prefix(4000))))
    }

    private func turn() {
        session.extraRotation = (session.extraRotation + 90) % 360
        controller.contentSizeChanged()
    }

    private var fullToolbar: some View {
        GlassEffectContainer(spacing: 10) {
            HStack(spacing: 10) {
                if session.isRunning {
                    stopButton.keyboardShortcut(".", modifiers: .command)
                }
                button("camera.viewfinder", help: "Copy a picture to the clipboard") { controller.copyScreenshot() }
                    .keyboardShortcut("c", modifiers: [.command, .shift])
                    .disabled(!session.hasPicture)
                button("rotate.right", help: "Turn the picture a quarter") { turn() }
                    .disabled(!session.hasPicture)
                if session.kind == .screen {
                    // Always pressable: when the phone has not allowed clicking yet, the press says what to do about it.
                    button(
                        "cursorarrow.click.2",
                        help: session.controlGranted
                            ? (session.controlOn
                                ? (peerIsComputer ? "Stop clicking and typing on the computer" : "Stop clicking and typing on the phone")
                                : (peerIsComputer ? "Click and type on the computer" : "Click and type on the phone"))
                            : (peerIsComputer ? "The computer does not allow clicking yet" : "The phone does not allow clicking yet"),
                        active: session.controlGranted && session.controlOn
                    ) {
                        if session.controlGranted {
                            session.controlOn.toggle()
                            FloatingToast.show(
                                session.controlOn
                                    ? (peerIsComputer ? String(localized: "You can click and type on the computer now") : String(localized: "You can click and type on the phone now"))
                                    : (peerIsComputer ? String(localized: "Clicking and typing on the computer is off") : String(localized: "Clicking and typing on the phone is off")),
                                symbol: session.controlOn ? "cursorarrow.click.2" : "cursorarrow.slash"
                            )
                        } else {
                            showControlHelp = true
                        }
                    }
                    .opacity(session.controlGranted ? 1 : 0.55)
                    .popover(isPresented: $showControlHelp, arrowEdge: .top) { controlHelp }
                }
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

    /// Ends the session: a red pill with the word on it, because a bare square in a window that shows a screen reads as "record".
    private var stopButton: some View {
        Button { LiveManager.shared.stop(session) } label: {
            Label("Stop", systemImage: "xmark")
                .font(.system(size: 14, weight: .semibold))
                .foregroundStyle(Palette.urgent)
                .padding(.horizontal, 4)
                .frame(height: 20)
        }
        .buttonStyle(.glass)
        .buttonBorderShape(.capsule)
        .controlSize(.large)
        .help("Stop showing this screen")
    }

    private func button(
        _ symbol: String, help: LocalizedStringKey, tint: Color? = nil, active: Bool = false, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .frame(width: 20, height: 20)
                .foregroundStyle(tint ?? (active ? Color.white : Color.primary))
        }
        .buttonStyle(.glass)
        .tint(active ? Palette.indigo : nil)
        .buttonBorderShape(.circle)
        .controlSize(.large)
        // The name of the button is the system tooltip: no animation of our own over the window.
        .help(help)
    }

    /// The side of the phone's camera and how sharp the picture is. A change asks the phone again: it is the same window.
    private var cameraMenu: some View {
        button("arrow.triangle.2.circlepath.camera", help: "Camera and quality") { showCamera.toggle() }
            .popover(isPresented: $showCamera, arrowEdge: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    MenuRow(title: "Back camera", checked: session.cameraFacing == .back) { choose(facing: .back, quality: session.quality); showCamera = false }
                    MenuRow(title: "Front camera", checked: session.cameraFacing == .front) { choose(facing: .front, quality: session.quality); showCamera = false }
                    Divider().padding(.vertical, 4)
                    MenuRow(title: "720p", checked: session.quality == .standard) { choose(facing: session.cameraFacing, quality: .standard); showCamera = false }
                    MenuRow(title: "1080p", checked: session.quality == .high) { choose(facing: session.cameraFacing, quality: .high); showCamera = false }
                }
                .padding(8)
                .frame(width: 220)
            }
    }

    private func choose(facing: LiveCameraFacing, quality: LiveQuality) {
        guard facing != session.cameraFacing || quality != session.quality else { return }
        guard let device = model.device(session.peer) else { return }
        LiveManager.shared.start(device: device, kind: .camera, facing: facing, quality: quality)
    }

    /// The same round button as the others, with the rest in a list under it.
    private var moreMenu: some View {
        button("ellipsis", help: "More") { showMore.toggle() }
            .popover(isPresented: $showMore, arrowEdge: .top) { menuRows(everything: false) }
            .popover(isPresented: $showInfo, arrowEdge: .bottom) {
                VStack(alignment: .leading, spacing: 8) {
                    Label("A window, not a webcam", systemImage: "camera.badge.ellipsis").font(.headline)
                    Text(Self.webcamNote).font(.callout).foregroundStyle(.secondary)
                }
                .padding(16)
                .frame(width: 280)
            }
    }

    /// What is in the list under the round button. In a narrow window the buttons that did not fit are in it too.
    private func menuRows(everything: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            if everything {
                MenuRow(title: "Turn the picture a quarter", symbol: "rotate.right", disabled: !session.hasPicture) { turn(); showMore = false }
                if session.kind == .camera {
                    MenuRow(title: "Mirror the picture", symbol: "arrow.left.and.right.righttriangle.left.righttriangle.right", checked: session.mirrored) { session.mirrored.toggle() }
                    MenuRow(title: "Back camera", checked: session.cameraFacing == .back) { choose(facing: .back, quality: session.quality); showMore = false }
                    MenuRow(title: "Front camera", checked: session.cameraFacing == .front) { choose(facing: .front, quality: session.quality); showMore = false }
                    MenuRow(title: "720p", checked: session.quality == .standard) { choose(facing: session.cameraFacing, quality: .standard); showMore = false }
                    MenuRow(title: "1080p", checked: session.quality == .high) { choose(facing: session.cameraFacing, quality: .high); showMore = false }
                }
                MenuRow(title: "Keep this window on top", symbol: "pin", checked: session.alwaysOnTop) { controller.setAlwaysOnTop(!session.alwaysOnTop) }
                MenuRow(title: "Floating window without a frame", symbol: "rectangle.dashed", checked: session.frameless) { showMore = false; controller.setFrameless(!session.frameless) }
            }
            // For a computer that is looked at and used from here: what the keys of this Mac cannot say by themselves.
            if peerIsComputer, session.kind == .screen {
                MenuRow(title: "Type my clipboard there", symbol: "doc.on.clipboard", disabled: !(session.controlGranted && session.controlOn)) {
                    showMore = false
                    typeClipboard()
                }
                MenuRow(title: "Switch window there", symbol: "rectangle.2.swap", disabled: !(session.controlGranted && session.controlOn)) {
                    showMore = false
                    pressThere(usage: 0x2B, mods: remoteIsMac ? 8 : 4)
                }
                if !remoteIsMac {
                    MenuRow(title: "Ctrl, Alt and Delete", symbol: "keyboard", disabled: !(session.controlGranted && session.controlOn)) {
                        showMore = false
                        pressThere(usage: 0x4C, mods: 2 | 4)
                    }
                }
                MenuRow(title: "Lock the screen there", symbol: "lock", disabled: !(session.controlGranted && session.controlOn)) {
                    showMore = false
                    // Control and Command with Q on a Mac, the Windows or Super key with L on the others.
                    if remoteIsMac { pressThere(usage: 0x14, mods: 2 | 8) } else { pressThere(usage: 0x0F, mods: 8) }
                }
                Divider().padding(.vertical, 4)
            }
            MenuRow(title: "Show statistics", symbol: "chart.bar", checked: session.showStats) { session.showStats.toggle() }
            if session.kind == .camera {
                MenuRow(title: "About using this in a call…", symbol: "info.circle") { showMore = false; showInfo = true }
            }
            Divider().padding(.vertical, 4)
            MenuRow(title: "Close window", symbol: "xmark") { showMore = false; controller.close() }
        }
        .padding(8)
        .frame(width: 270)
    }

    /// Why the button for clicking on the phone does nothing yet, and what to do about it.
    private var controlHelp: some View {
        if peerIsComputer { return AnyView(computerControlHelp) }
        return AnyView(phoneControlHelp)
    }

    /// Why a computer does not let this Mac use it yet.
    private var computerControlHelp: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("This computer does not allow control yet", systemImage: "cursorarrow.click.2").font(.headline)
            Text("It showed its screen, but the mouse and the keyboard have to be allowed there as well.")
                .font(.callout).foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 4) {
                Text("On a Mac: allow Tandem in System Settings, Privacy and Security, Accessibility, then restart Tandem on it.")
                Text("On Windows and Linux: when it asks, choose Allow. Under Wayland Linux does not let an app use the mouse and keyboard: use an X11 session.")
                Text("Then show the screen again from here.").foregroundStyle(.secondary)
            }
            .font(.callout)
        }
        .padding(16)
        .frame(width: 320)
    }

    private var phoneControlHelp: some View {
        VStack(alignment: .leading, spacing: 10) {
            Label("One more permission is needed on the phone", systemImage: "cursorarrow.click.2").font(.headline)
            Text("To click and type on the phone from this Mac, Tandem needs its Accessibility control on the phone. Only you can turn that on.")
                .font(.callout).foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 4) {
                Text("1. On the phone open Settings, then Accessibility.")
                Text("2. Open Installed apps (or Downloaded apps), then Tandem.")
                Text("3. Turn Tandem on and allow it.")
                Text("If the switch is grey: open App info for Tandem on the phone, tap the three dots at the top and choose Allow restricted settings. Then do step 2 and 3 again.")
                    .foregroundStyle(.secondary)
                Text("The button here wakes up by itself once the phone allows it.")
                    .foregroundStyle(.secondary)
            }
            .font(.callout)
        }
        .padding(16)
        .frame(width: 320)
    }
}

/// One line of the lists under the round buttons: a symbol, the words, and a check when it is on.
private struct MenuRow: View {
    let title: LocalizedStringKey
    var symbol: String?
    var checked: Bool?
    var disabled = false
    let action: () -> Void
    @LocalState private var hovering = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: symbol ?? "circle").opacity(symbol == nil ? 0 : 1).frame(width: 18)
                Text(title)
                Spacer(minLength: 8)
                if checked == true { Image(systemName: "checkmark").font(.caption.weight(.semibold)) }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(hovering && !disabled ? Color.primary.opacity(0.1) : Color.clear, in: .rect(cornerRadius: 8, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(disabled)
        .opacity(disabled ? 0.4 : 1)
        .onHover { hovering = $0 }
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


/// What is behind the picture of a screen or camera: black, so the picture ends cleanly, and while there is no picture yet a dark
/// backdrop with a little light at the top, which makes the waiting card sit on something.
struct LiveBackdrop: View {
    let waiting: Bool

    var body: some View {
        ZStack {
            Color.black
            LinearGradient(
                colors: [Color(white: 0.16), Color(white: 0.07), Color(white: 0.04)],
                startPoint: .top, endPoint: .bottom
            )
            .opacity(waiting ? 1 : 0)
            // A soft pool of light under the card, in the grey of the window and not in a colour.
            RadialGradient(colors: [Color.white.opacity(0.07), Color.white.opacity(0)], center: .center, startRadius: 0, endRadius: 360)
                .opacity(waiting ? 1 : 0)
        }
        .animation(.tandemFade, value: waiting)
        .ignoresSafeArea()
    }
}

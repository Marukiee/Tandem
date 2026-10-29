import AppKit
import ApplicationServices
import Foundation
import Observation
import TandemCore

/// This Mac as the host of a remote desktop: another device (the Android app) looks at the screen and, when allowed,
/// controls it. The core moves the video and the input; this file is the app side of `docs/SCREEN.md`.
///
/// One device at a time sees this Mac's own display; beside that, every device that asks gets an extra screen of its own (a display of
/// software, see `VirtualDisplay`), up to a few. Each session has its own capture, encoder and bitrate, and the core decides per frame
/// whether its link can take it. Everything the core calls (keyframe, bitrate, input, stop) lands here, by session.
///
/// What is asked of the person, and when:
/// - A device that is not trusted for this yet gets a prompt on the Mac, with a choice to let it control or only watch.
/// - "Always allow" is kept by the core as the policy of that device, so it is also what Settings shows and changes.
/// - A viewer that drops and comes back within two minutes after a network blip is not asked again.
@MainActor
@Observable
final class ScreenHost {
    static let shared = ScreenHost()

    /// More extra screens than this and the Mac is drawing, capturing and encoding more than a person can follow; the next one is asked to
    /// take the place of the oldest.
    static let maxExtended = 4

    /// A session that runs.
    struct Remote: Identifiable, Equatable {
        let id: UInt64
        let peer: String
        let name: String
        /// Whether the viewer may use the mouse and keyboard right now.
        var control: Bool
        /// Whether it ever could in this session, so a pause can be taken back.
        let offered: Bool
        let startedAt: Date
        var width: Int
        var height: Int
        /// A screen made for the viewer (a second screen), not the one that is here.
        var extend = false
    }

    /// A request that waits for the person at the Mac.
    struct Prompt: Identifiable, Equatable {
        let id: UInt64
        let peer: String
        let name: String
        let symbol: String
        let wantsControl: Bool
        var allowControl: Bool
        /// It wants a screen of its own from this Mac, not a view of the one that is here.
        var extend = false
    }

    /// Every session that runs, by session id. The core keeps one screen session per device, so a device is in here at most once.
    private(set) var remotes: [UInt64: Remote] = [:]
    /// Requests that wait for the person, in the order they came; the first is the one on screen.
    private(set) var prompts: [Prompt] = []
    /// Whether the Accessibility permission is there, for the prompt and the indicator to show the truth.
    private(set) var accessibility = AXIsProcessTrusted()

    var prompt: Prompt? { prompts.first }
    /// Oldest first, which is the order the bar lists them in and the extra screens are placed in.
    var running: [Remote] { remotes.values.sorted { $0.startedAt < $1.startedAt } }
    /// The one session that shows this Mac's own display.
    var mirror: Remote? { remotes.values.first { !$0.extend } }
    var extended: [Remote] { running.filter(\.extend) }

    func remote(of peer: String) -> Remote? { remotes.values.first { $0.peer == peer } }
    func isExtending(to peer: String) -> Bool { remote(of: peer)?.extend == true }

    /// The master switch: off, and requests are turned away and the cap is not announced.
    var enabled = UserDefaults.standard.object(forKey: "screenHostEnabled") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(enabled, forKey: "screenHostEnabled")
            if !enabled { stopAll(reason: .ended) }
        }
    }

    /// The cap went out in the Hello at start, or did not. Permissions change while the app runs, the Hello does not.
    @ObservationIgnored private(set) var advertised = false

    @ObservationIgnored private var engine: TandemEngine?
    @ObservationIgnored private weak var model: EngineModel?
    @ObservationIgnored private let registry = SessionRegistry()
    @ObservationIgnored private var recent: [String: (control: Bool, until: Date)] = [:]
    @ObservationIgnored private var promptTimeouts: [UInt64: Task<Void, Never>] = [:]
    @ObservationIgnored private var pendingRequests: [UInt64: TandemMediaRequest] = [:]
    @ObservationIgnored private var observers: [NSObjectProtocol] = []
    @ObservationIgnored private var recovering = false
    @ObservationIgnored private var watch: Task<Void, Never>?
    @ObservationIgnored private lazy var bridge = ScreenHostBridge(host: self, registry: registry)

    // MARK: Hello

    /// What goes into `Hello.caps`. Only when this Mac can really do it: the switch is on and Screen Recording is
    /// allowed. Decided once, at start, because the Hello is.
    func capabilities() -> [String] {
        advertised = enabled && ScreenCapturer.hasPermission
        guard advertised else { return [] }
        // Without Accessibility a phone can look but its clicks and keys go nowhere, which it is told.
        // A second screen needs the system to make a display of software, which it does not on every system.
        // That display takes another shape when the device is turned (see `resize`).
        let extend = VirtualDisplay.isAvailable ? ["screen.extend", "screen.extend.turn"] : []
        return (AXIsProcessTrusted() ? ["screen.host", "screen.control"] : ["screen.host"]) + extend
    }

    /// Screen Recording was allowed after the app started, so the Hello has to be said again, which is a restart.
    var needsRestart: Bool { enabled && ScreenCapturer.hasPermission && !advertised }

    func attach(engine: TandemEngine, model: EngineModel) {
        self.engine = engine
        self.model = model
        engine.setMediaHost(host: bridge)
        observers.append(NotificationCenter.default.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification, object: nil, queue: .main
        ) { [weak self] _ in MainActor.assumeIsolated { self?.displayChanged(woke: false) } })
        // Wake from sleep stops a capture stream without a word on some Macs.
        observers.append(NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in MainActor.assumeIsolated { self?.displayChanged(woke: true) } })
    }

    func shutdown() {
        stopAll(reason: .ended)
        for token in observers { NotificationCenter.default.removeObserver(token) }
        observers = []
    }

    // MARK: A request

    func request(from peer: String, request: TandemMediaRequest, preApproved: Bool) {
        guard let engine else { return }
        let id = request.session
        func deny(_ reason: TandemMediaEnd) { try? engine.mediaDeny(session: id, reason: reason) }

        guard request.kind == .screen, request.codecs.isEmpty || request.codecs.contains(.h264) else { return deny(.unsupported) }
        guard enabled else { return deny(.policy) }
        guard ScreenCapturer.hasPermission else {
            FloatingToast.show(String(localized: "Allow Tandem to record this Mac's screen in System Settings, under Screen Recording"), symbol: "rectangle.dashed.badge.record")
            return deny(.unavailable)
        }

        // A new request from a device replaces what it had, as the core already did with its side of it.
        if let running = remote(of: peer) { teardown(session: running.id, tell: false) }
        for waiting in prompts where waiting.peer == peer { closePrompt(waiting.id) }

        // This Mac's own display is shown to one device at a time. Extra screens are made for every device that asks, up to a few.
        if request.extend {
            if extended.count + prompts.filter(\.extend).count >= Self.maxExtended { return deny(.busy) }
        } else if mirror != nil || prompts.contains(where: { !$0.extend }) {
            return deny(.busy)
        }

        let name = model?.device(peer)?.name ?? String(localized: "A device")
        accessibility = AXIsProcessTrusted()
        let controlPolicy = (try? engine.mediaPolicy(id: peer))?.control ?? .ask
        let controlPossible = request.control && accessibility && controlPolicy != .never

        // The person pressed the button for this on this Mac a moment ago: that was the question, it is not asked again.
        let asked = request.extend && (expectedExtend.removeValue(forKey: peer) ?? .distantPast) > Date()
        if preApproved || asked {
            start(request: request, peer: peer, name: name, control: controlPossible)
        } else if let again = recent[peer], again.until > Date() {
            start(request: request, peer: peer, name: name, control: controlPossible && again.control)
        } else {
            let symbol = model?.device(peer)?.platform.symbol ?? "iphone"
            prompts.append(Prompt(id: id, peer: peer, name: name, symbol: symbol, wantsControl: request.control && controlPolicy != .never, allowControl: true, extend: request.extend))
            pendingRequests[id] = request
            ScreenPanels.shared.update()
            Notifier.shared.post(
                id: "screen.\(id)",
                title: request.extend ? String(localized: "\(name) wants to use a screen of this Mac") : request.control ? String(localized: "\(name) wants to control this Mac") : String(localized: "\(name) wants to see this Mac"),
                body: String(localized: "Choose what to do in Tandem."),
                sound: true
            )
            promptTimeouts[id] = Task { [weak self] in
                // The core gives up after sixty seconds; the prompt goes just before.
                try? await Task.sleep(for: .seconds(58))
                guard !Task.isCancelled else { return }
                self?.dismissPrompt(session: id)
            }
        }
    }

    /// The displays of software made for devices that use them as a second screen, by session, to place them on the side of their device.
    @ObservationIgnored private var displays: [UInt64: VirtualDisplay] = [:]

    func placeExtended(for peer: String, onLeft: Bool) {
        guard let running = remote(of: peer), let display = displays[running.id] else { return }
        display.onLeft = onLeft
        arrangeExtended()
    }

    /// Every extra screen on the side of its device, in the order they came, so the first one on a side stays next to this Mac.
    private func arrangeExtended() {
        VirtualDisplay.arrange(extended.compactMap { displays[$0.id] })
    }

    /// Devices that this Mac asked to show a screen of its own, and until when their request for it needs no question.
    @ObservationIgnored private var expectedExtend: [String: Date] = [:]

    /// Asks `device` to be a second screen of this Mac: it is told, and its request for the screen that follows is taken without a prompt.
    func offerExtend(to device: TandemDevice) {
        guard let engine else { return }
        guard device.online else {
            model?.showToast(String(localized: "\(device.name) cannot be reached right now"))
            return
        }
        guard ScreenCapturer.hasPermission else {
            FloatingToast.show(String(localized: "Allow Tandem to record this Mac's screen in System Settings, under Screen Recording"), symbol: "rectangle.dashed.badge.record")
            return
        }
        // A screen beside the others, until there are as many as this Mac makes: then ask whether the oldest one moves over, instead of an
        // answer of busy on the device.
        let others = extended.filter { $0.peer != device.id }
        if others.count >= Self.maxExtended, let oldest = others.first {
            GlassQuestion.shared.ask(
                symbol: "rectangle.on.rectangle",
                title: String(localized: "The extra screen is on \(oldest.name)"),
                body: String(localized: "Move it to \(device.name)? \(oldest.name) stops showing it."),
                answers: [
                    GlassQuestion.Answer(title: "Cancel", action: {}),
                    GlassQuestion.Answer(title: "Move it", prominent: true, action: { [weak self] in
                        self?.stop(session: oldest.id)
                        // A moment for the screen of the other device to go, so the new request does not find this Mac still full.
                        Task { @MainActor [weak self] in
                            try? await Task.sleep(for: .milliseconds(600))
                            self?.offerExtend(to: device)
                        }
                    }),
                ]
            )
            return
        }
        expectedExtend[device.id] = Date().addingTimeInterval(90)
        do {
            try engine.mediaOffer(peer: device.id, kind: .screen, facing: .any, extend: true)
            model?.showToast(String(localized: "Asked \(device.name) to be a second screen"))
        } catch {
            expectedExtend[device.id] = nil
            model?.showToast(String(localized: "\(device.name) cannot be reached right now"))
        }
    }

    enum Answer { case allowOnce, always, deny, never }

    /// The answer to the question on screen, the first one that waits.
    func answer(_ answer: Answer) {
        guard let engine, let waiting = prompt, let request = pendingRequests[waiting.id] else { return }
        let control = waiting.wantsControl && waiting.allowControl && AXIsProcessTrusted()
        closePrompt(waiting.id)
        switch answer {
        case .deny:
            try? engine.mediaDeny(session: waiting.id, reason: .declined)
        case .never:
            changePolicy(peer: waiting.peer) { $0.screen = .never }
            try? engine.mediaDeny(session: waiting.id, reason: .declined)
        case .allowOnce:
            start(request: request, peer: waiting.peer, name: waiting.name, control: control)
        case .always:
            // The choice about control is kept also when this Mac cannot give it yet (no accessibility): once it can, it does.
            let controlChosen = waiting.wantsControl && waiting.allowControl
            changePolicy(peer: waiting.peer) { policy in
                policy.screen = .always
                if controlChosen { policy.control = .always }
            }
            start(request: request, peer: waiting.peer, name: waiting.name, control: control)
        }
    }

    func setPromptControl(_ allowed: Bool) {
        guard !prompts.isEmpty else { return }
        prompts[0].allowControl = allowed
        ScreenPanels.shared.update()
    }

    private func dismissPrompt(session: UInt64) {
        guard prompts.contains(where: { $0.id == session }) else { return }
        closePrompt(session)
    }

    private func closePrompt(_ session: UInt64) {
        promptTimeouts.removeValue(forKey: session)?.cancel()
        Notifier.shared.remove(id: "screen.\(session)")
        prompts.removeAll { $0.id == session }
        pendingRequests[session] = nil
        ScreenPanels.shared.update()
    }

    private func changePolicy(peer: String, _ change: (inout TandemMediaPolicy) -> Void) {
        guard let engine, var policy = try? engine.mediaPolicy(id: peer) else { return }
        change(&policy)
        try? engine.setMediaPolicy(id: peer, policy: policy)
    }

    // MARK: Start

    private func start(request: TandemMediaRequest, peer: String, name: String, control: Bool) {
        guard let engine else { return }
        let id = request.session
        // Another device may have been let in to this Mac's own display while this one waited for its answer.
        if !request.extend, let other = mirror, other.peer != peer {
            try? engine.mediaDeny(session: id, reason: .busy)
            return
        }
        let fps = ScreenGeometry.frameRate(requested: Int(request.maxFps))

        // A device that wants a screen of its own gets a display of software of exactly its size next to this Mac's own; one that wants to
        // look gets the main display, scaled to fit.
        var virtual: VirtualDisplay?
        var display = ScreenCapturer.mainDisplay()
        let size: (width: Int, height: Int)
        if request.extend {
            let shape = ScreenGeometry.extendedShape(
                width: Int(request.maxWidth), height: Int(request.maxHeight),
                logicalWidth: Int(request.logicalWidth), logicalHeight: Int(request.logicalHeight)
            )
            guard VirtualDisplay.isAvailable, let made = VirtualDisplay(name: name, key: peer, width: shape.pixelsWidth, height: shape.pixelsHeight, hiDPI: shape.hiDPI, refresh: min(60, max(30, fps))) else {
                try? engine.mediaDeny(session: id, reason: .unavailable)
                return
            }
            NSLog(
                "Tandem: second screen for %@, which asked %ldx%ld pixels and %ldx%ld logical: %ldx%ld points%@ drawn in %ldx%ld, sent as %ldx%ld",
                name, Int(request.maxWidth), Int(request.maxHeight), Int(request.logicalWidth), Int(request.logicalHeight),
                shape.pointsWidth, shape.pointsHeight, shape.hiDPI ? " (hiDPI)" : "", shape.pixelsWidth, shape.pixelsHeight, shape.streamWidth, shape.streamHeight
            )
            virtual = made
            // On the side the device stands on. When that is not known (or the person wanted to be asked each time), the question comes up:
            // found by sound, or chosen. A device that can listen is first checked by ear once its screen is on (see `SideWatch`).
            if let known = DeviceSides.side(of: peer) {
                made.onLeft = known == "left"
            } else if let device = model?.device(peer), !SideWatch.shared.findsFirst(device) {
                SidePrompt.shared.ask(device)
            }
            displays[id] = made
            display = ScreenCapturer.DisplayInfo(id: made.displayID, bounds: made.bounds, pixels: made.pixels)
            size = (shape.streamWidth, shape.streamHeight)
        } else {
            size = ScreenGeometry.fit(source: display.pixels, maxWidth: Int(request.maxWidth), maxHeight: Int(request.maxHeight))
        }
        let bitrate = ScreenGeometry.startingBitrate(width: size.width, height: size.height, fps: fps, requestedMax: Int(request.maxBitrate))

        let live: ScreenHostSession
        do {
            let encoder = try ScreenEncoder(.init(width: size.width, height: size.height, fps: fps, bitrate: bitrate))
            live = ScreenHostSession(
                id: id, peer: peer, engine: engine, encoder: encoder, display: display,
                limits: (Int(request.maxWidth), Int(request.maxHeight)), baseFps: fps, startBitrate: bitrate, virtualDisplay: virtual
            )
        } catch {
            displays[id] = nil
            try? engine.mediaDeny(session: id, reason: .unavailable)
            return
        }
        registry.add(live)
        live.onGone = { [weak self] reason in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.teardown(session: id, tell: true, reason: reason) } }
        }
        remotes[id] = Remote(id: id, peer: peer, name: name, control: control, offered: control, startedAt: Date(), width: size.width, height: size.height, extend: request.extend)
        ScreenPanels.shared.update()
        // The side is listened for while the display gets ready, not after: by the time the screen shows, it is on the right side.
        if request.extend { SideWatch.shared.screenStarted(peer) }

        Task { [weak self] in
            do {
                // The display of software is a monitor of the system only after a moment, and in the size that was asked for only after
                // it was told to be.
                if let virtual {
                    guard await virtual.prepare() else { throw ScreenCapturer.Failure.noDisplay }
                    self?.arrangeExtended()
                    live.displayReady(virtual)
                    // Kept in a file: the log of the system does not keep these, and a screen of the wrong shape is found by them.
                    PointerLog.write(
                        "extra screen for \(name): asked \(request.maxWidth)x\(request.maxHeight) pixels, \(request.logicalWidth)x\(request.logicalHeight) logical; shows \(virtual.modeDescription); sent as \(size.width)x\(size.height)"
                    )
                }
                try await live.startCapture(width: size.width, height: size.height)
                guard let self, self.registry.get(id) != nil else {
                    await live.stop()
                    return
                }
                try engine.mediaAccept(session: id, answer: TandemMediaAccept(
                    codec: .h264, width: UInt32(size.width), height: UInt32(size.height), fps: UInt32(fps),
                    bitrate: UInt32(bitrate), control: control
                ))
                live.markAccepted()
                // Pictures that came before the accept were not sent, and a still screen sends no new one: the first
                // frame the viewer gets must be a keyframe made from what is on the display right now.
                live.requestKeyframe(force: true)
            } catch {
                self?.failed(session: id, error: error)
            }
        }
    }

    private func failed(session: UInt64, error: Error) {
        guard let engine else { return }
        let reason: TandemMediaEnd = ScreenCapturer.isPermissionError(error) ? .unavailable : .error
        try? engine.mediaDeny(session: session, reason: reason)
        teardown(session: session, tell: false)
        if reason == .unavailable {
            FloatingToast.show(String(localized: "Allow Tandem to record this Mac's screen in System Settings, under Screen Recording"), symbol: "rectangle.dashed.badge.record")
        }
    }

    // MARK: Stop

    /// The person pressed Stop for one device.
    func stop(session: UInt64, reason: TandemMediaEnd = .ended) {
        guard remotes[session] != nil else { return }
        teardown(session: session, tell: true, reason: reason)
    }

    /// Whatever runs for `peer`, or the question of it that waits.
    func stop(peer: String) {
        if let running = remote(of: peer) { return stop(session: running.id) }
        for waiting in prompts where waiting.peer == peer {
            try? engine?.mediaDeny(session: waiting.id, reason: .declined)
            closePrompt(waiting.id)
        }
    }

    /// Every extra screen, and only those: the Control Center button is about them, not about a device that looks at this display.
    func stopExtended() {
        for running in extended { stop(session: running.id) }
    }

    /// The switch went off, or the app quits: every session and every question that waits.
    func stopAll(reason: TandemMediaEnd = .ended) {
        for waiting in prompts {
            try? engine?.mediaDeny(session: waiting.id, reason: .declined)
            closePrompt(waiting.id)
        }
        for session in self.running { teardown(session: session.id, tell: true, reason: reason) }
    }

    /// Everything that belongs to a session, gone. `tell` is for a stop that started here: the core still has the
    /// session and the viewer has to hear about it.
    func teardown(session: UInt64, tell: Bool, reason: TandemMediaEnd = .ended) {
        let live = registry.remove(session)
        if tell { try? engine?.mediaStop(session: session) }
        let hadDisplay = displays.removeValue(forKey: session) != nil
        wantedShapes[session] = nil
        if let running = remotes.removeValue(forKey: session) {
            // A viewer that was lost, and not stopped, may come back from a network blip without being asked again.
            if !tell, [.peerGone, .error, .timeout].contains(reason) {
                recent[running.peer] = (running.control, Date().addingTimeInterval(120))
            }
            model?.injector.endRemote(from: running.peer)
            ScreenPanels.shared.update()
        }
        if let live {
            Task { [weak self] in
                await live.stop()
                // The extra screens that are left close the gap, each still on the side of its device.
                if hadDisplay { self?.arrangeExtended() }
            }
        }
    }

    /// The core ended a session.
    func ended(session: UInt64, reason: TandemMediaEnd) {
        if prompts.contains(where: { $0.id == session }) {
            closePrompt(session)
            return
        }
        teardown(session: session, tell: false, reason: reason)
    }

    /// Turns control on or off for one session, without ending it.
    func setControl(_ allowed: Bool, session: UInt64) {
        guard let engine, var running = remotes[session] else { return }
        let granted = allowed && AXIsProcessTrusted()
        try? engine.mediaUpdate(session: running.id, update: TandemMediaUpdate(width: nil, height: nil, rotation: nil, fps: nil, control: granted))
        running.control = granted
        remotes[session] = running
        if !granted { model?.injector.endRemote(from: running.peer) }
        ScreenPanels.shared.update()
    }

    // MARK: A device that is turned

    /// The shape asked for last, by session, while that session's screen is changing: a phone turned and turned back within a second ends
    /// in the shape it was left in, one change at a time.
    @ObservationIgnored private var wantedShapes: [UInt64: TandemMediaResize] = [:]
    @ObservationIgnored private var reshaping: Set<UInt64> = []

    /// The device that uses an extra screen was turned. The display changes shape in place, not made again: the system keeps it where
    /// it is with its windows, and nothing is asked of the person. The capture stops for that moment and starts in the new shape, and
    /// the viewer is told before the first picture of it.
    func resize(session id: UInt64, to shape: TandemMediaResize) {
        guard remotes[id]?.extend == true, displays[id] != nil else { return }
        wantedShapes[id] = shape
        guard reshaping.insert(id).inserted else { return }
        Task { [weak self] in
            await self?.reshape(session: id)
            self?.reshaping.remove(id)
        }
    }

    private func reshape(session id: UInt64) async {
        while let wanted = wantedShapes.removeValue(forKey: id) {
            guard let engine, let display = displays[id], let live = registry.get(id), let running = remotes[id] else { return }
            let shape = ScreenGeometry.extendedShape(
                width: Int(wanted.maxWidth), height: Int(wanted.maxHeight),
                logicalWidth: Int(wanted.logicalWidth), logicalHeight: Int(wanted.logicalHeight)
            )
            let pixels = CGSize(width: shape.pixelsWidth, height: shape.pixelsHeight)
            if display.pixels == pixels, running.width == shape.streamWidth, running.height == shape.streamHeight { continue }
            NSLog("Tandem: second screen for %@ turned: %ldx%ld points drawn in %ldx%ld, sent as %ldx%ld",
                  running.name, shape.pointsWidth, shape.pointsHeight, shape.pixelsWidth, shape.pixelsHeight, shape.streamWidth, shape.streamHeight)
            do {
                try await live.restart(size: (shape.streamWidth, shape.streamHeight), reshape: { [weak self] in
                    guard await display.resize(width: shape.pixelsWidth, height: shape.pixelsHeight, hiDPI: shape.hiDPI) else {
                        NSLog("Tandem: the second screen for %@ kept its shape, the system would not change it", running.name)
                        return false
                    }
                    // Narrower or wider than it was: the screens beside it move up to it again, each still on the side of its device.
                    self?.arrangeExtended()
                    live.displayReady(display)
                    return true
                }) { next in
                    guard next.changed else { return }
                    try engine.mediaUpdate(session: id, update: TandemMediaUpdate(
                        width: UInt32(next.width), height: UInt32(next.height), rotation: 0, fps: UInt32(live.baseFps), control: nil
                    ))
                    if var now = self.remotes[id] {
                        now.width = next.width
                        now.height = next.height
                        self.remotes[id] = now
                    }
                }
                live.requestKeyframe(force: true)
            } catch {
                // A display that cannot be captured any more ends the session; the device asks again when it can.
                teardown(session: id, tell: true, reason: .error)
                return
            }
        }
    }

    // MARK: Input

    func input(session: UInt64, input: TandemMediaInput) {
        guard let running = remotes[session], running.control, let live = registry.get(session) else { return }
        model?.injector.handle(remote: input, in: live.geometry, from: running.peer)
    }

    // MARK: The display changes

    /// A different resolution, another display as the main one, a wake from sleep: the capture of this Mac's own display is started again
    /// with the size that fits now, and the viewer is told the new shape before the first picture of it. Extra screens keep their own
    /// capture as long as the display stays what it was made as.
    func displayChanged(woke: Bool) {
        guard let running = mirror, let live = registry.get(running.id), !recovering else { return }
        // Extra screens that come, go and move change the displays of the system without changing the one this session shows. After a
        // wake the stream may have stopped without a word, so that always starts again.
        if !woke, live.shows(ScreenCapturer.mainDisplay()) { return }
        recovering = true
        Task { [weak self] in
            // Displays settle for a moment after a change; a restart too early captures the old mode.
            try? await Task.sleep(for: .milliseconds(900))
            defer { self?.recovering = false }
            guard let self, self.registry.get(running.id) != nil else { return }
            do {
                try await live.restart { shape in
                    guard shape.changed else { return }
                    try self.engine?.mediaUpdate(session: running.id, update: TandemMediaUpdate(
                        width: UInt32(shape.width), height: UInt32(shape.height), rotation: 0, fps: UInt32(live.baseFps), control: nil
                    ))
                    if var now = self.remotes[running.id] {
                        now.width = shape.width
                        now.height = shape.height
                        self.remotes[running.id] = now
                    }
                }
                live.requestKeyframe(force: true)
            } catch {
                // A display that cannot be captured any more ends the session. The viewer asks again when it can.
                self.teardown(session: running.id, tell: true, reason: .error)
            }
        }
    }

    // MARK: Permissions

    func requestScreenRecording() {
        if !CGRequestScreenCaptureAccess() { openSettings(pane: "Privacy_ScreenCapture") }
    }

    func requestAccessibility() {
        _ = AXIsProcessTrustedWithOptions(["AXTrustedCheckOptionPrompt": true] as CFDictionary)
    }

    func openSettings(pane: String) {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?\(pane)") { NSWorkspace.shared.open(url) }
    }

    func refreshPermissions() {
        let trusted = AXIsProcessTrusted()
        if trusted != accessibility { accessibility = trusted }
    }

    func relaunch() {
        let path = Bundle.main.bundlePath
        let task = Process()
        task.executableURL = URL(fileURLWithPath: "/usr/bin/open")
        task.arguments = ["-n", path]
        try? task.run()
        NSApp.terminate(nil)
    }
}

// MARK: - What the core calls

/// The core calls from threads of its own. Keyframes and bitrate go straight to the encoder, which is thread safe and
/// should not wait for the main thread; the rest is handed to the main thread in the order it came.
final class ScreenHostBridge: TandemMediaHost, @unchecked Sendable {
    private weak var host: ScreenHost?
    private let registry: SessionRegistry

    @MainActor init(host: ScreenHost, registry: SessionRegistry) {
        self.host = host
        self.registry = registry
    }

    func onRequest(from: String, request: TandemMediaRequest, preApproved: Bool) {
        DispatchQueue.main.async { [weak host] in
            MainActor.assumeIsolated { host?.request(from: from, request: request, preApproved: preApproved) }
        }
    }

    func onKeyframe(session: UInt64) {
        registry.get(session)?.requestKeyframe(force: false)
    }

    func onBitrate(session: UInt64, bitsPerSecond: UInt32) {
        registry.get(session)?.setBitrate(Int(bitsPerSecond))
    }

    func onInput(session: UInt64, input: TandemMediaInput) {
        DispatchQueue.main.async { [weak host] in
            MainActor.assumeIsolated { host?.input(session: session, input: input) }
        }
    }

    func onStop(session: UInt64, reason: TandemMediaEnd) {
        DispatchQueue.main.async { [weak host] in
            MainActor.assumeIsolated { host?.ended(session: session, reason: reason) }
        }
    }

    func onResize(session: UInt64, resize: TandemMediaResize) {
        DispatchQueue.main.async { [weak host] in
            MainActor.assumeIsolated { host?.resize(session: session, to: resize) }
        }
    }
}

/// The sessions by id, readable from any thread.
final class SessionRegistry: @unchecked Sendable {
    private let lock = NSLock()
    private var sessions: [UInt64: ScreenHostSession] = [:]

    func add(_ session: ScreenHostSession) {
        lock.lock()
        sessions[session.id] = session
        lock.unlock()
    }

    func get(_ id: UInt64) -> ScreenHostSession? {
        lock.lock()
        defer { lock.unlock() }
        return sessions[id]
    }

    @discardableResult
    func remove(_ id: UInt64) -> ScreenHostSession? {
        lock.lock()
        defer { lock.unlock() }
        return sessions.removeValue(forKey: id)
    }
}

// MARK: - One running session

/// The capture, the encoder and the way into the core for one viewer.
final class ScreenHostSession: @unchecked Sendable {
    let id: UInt64
    let peer: String
    let baseFps: Int
    let startBitrate: Int
    /// Told when the core says the session has no home any more (the frame it pushed got `NoSession`).
    var onGone: ((TandemMediaEnd) -> Void)?

    private let engine: TandemEngine
    private let limits: (width: Int, height: Int)
    private let lock = NSLock()
    private var encoder: ScreenEncoder
    private var capturer = ScreenCapturer()
    private var display: ScreenCapturer.DisplayInfo
    private var accepted = false
    private var stopped = false
    private var lastAsk = Date.distantPast
    private var fps: Int
    private var bitrate: Int
    private var invalidRun = 0
    /// For the line in the log every ten seconds: what the encoder had counted the last time, and when.
    private var logged = (at: Date(), counters: ScreenEncoder.Counters(), sent: 0, refused: 0)
    private var sent = 0
    private var refused = 0
    /// A second screen for the viewer, made for this session and gone with it.
    private var virtualDisplay: VirtualDisplay?
    let startedAt = Date()

    var isExtended: Bool { lock.withLock { virtualDisplay != nil } }

    init(id: UInt64, peer: String, engine: TandemEngine, encoder: ScreenEncoder, display: ScreenCapturer.DisplayInfo,
         limits: (Int, Int), baseFps: Int, startBitrate: Int, virtualDisplay: VirtualDisplay? = nil) {
        self.virtualDisplay = virtualDisplay
        self.id = id
        self.peer = peer
        self.engine = engine
        self.encoder = encoder
        self.display = display
        self.limits = limits
        self.baseFps = baseFps
        self.fps = baseFps
        self.startBitrate = startBitrate
        self.bitrate = startBitrate
        wire(encoder)
    }

    var geometry: RemoteGeometry {
        lock.lock()
        defer { lock.unlock() }
        return RemoteGeometry(bounds: CGDisplayBounds(display.id), streamWidth: encoder.config.width)
    }

    private func wire(_ encoder: ScreenEncoder) {
        encoder.onFrame = { [weak self] frame in self?.push(frame) }
    }

    private func wire(_ capturer: ScreenCapturer) {
        capturer.onFrame = { [weak self] buffer in
            guard let self else { return }
            self.lock.lock()
            let target = self.encoder
            self.lock.unlock()
            target.submit(buffer)
        }
        capturer.onStopped = { [weak self] _ in self?.onGone?(.error) }
    }

    func startCapture(width: Int, height: Int) async throws {
        let (target, shown, rate) = lock.withLock { (capturer, display, fps) }
        wire(target)
        try await target.start(display: shown, width: width, height: height, fps: rate)
    }

    /// The display of software is there and in its size: where it sits now is where the pointer and the clicks of the viewer go.
    func displayReady(_ virtual: VirtualDisplay) {
        lock.withLock { display = ScreenCapturer.DisplayInfo(id: virtual.displayID, bounds: virtual.bounds, pixels: virtual.pixels) }
    }

    /// Whether the capture was started for `info` as it is now. Where the display sits does not count: the capture follows the display,
    /// and the input asks where it is each time.
    func shows(_ info: ScreenCapturer.DisplayInfo) -> Bool {
        lock.withLock { display.id == info.id && display.pixels == info.pixels && display.bounds.size == info.bounds.size }
    }

    func markAccepted() {
        lock.lock()
        accepted = true
        lock.unlock()
    }

    func stop() async {
        let (target, running) = lock.withLock { () -> (ScreenCapturer, ScreenEncoder) in
            stopped = true
            return (capturer, encoder)
        }
        running.onFrame = nil
        await target.stop()
        running.invalidate()
        // The display of software goes when nothing holds it: the screen the viewer used is gone from this Mac, and its windows with it.
        let virtual = lock.withLock { () -> VirtualDisplay? in
            defer { virtualDisplay = nil }
            return virtualDisplay
        }
        virtual?.destroy()
    }

    // MARK: From the core

    /// A viewer needs a picture it can start from. Asks come in bursts while it waits for one (every half second, and
    /// the core itself asks too), and a keyframe is several times the size of a frame, so they are spaced out.
    func requestKeyframe(force: Bool) {
        lock.lock()
        let now = Date()
        let allowed = force || now.timeIntervalSince(lastAsk) > 0.2
        if allowed { lastAsk = now }
        let target = encoder
        lock.unlock()
        if allowed { target.requestKeyframe() }
    }

    func setBitrate(_ value: Int) {
        lock.lock()
        bitrate = max(250_000, value)
        let target = encoder
        let wanted = ScreenGeometry.frameRate(for: bitrate, started: startBitrate, base: baseFps)
        let changed = wanted != fps
        fps = wanted
        let capture = capturer
        let applied = bitrate
        lock.unlock()
        target.setBitrate(applied)
        if changed {
            target.setFrameRate(wanted)
            Task { await capture.setFrameRate(wanted) }
        }
    }

    // MARK: To the core

    private func push(_ frame: ScreenEncoder.Frame) {
        lock.lock()
        let go = accepted && !stopped
        lock.unlock()
        guard go else { return }
        let result = engine.mediaPushFrame(session: id, data: Data(frame.data), ptsUs: frame.ptsUs, keyframe: frame.keyframe)
        switch result {
        case .noSession:
            onGone?(.error)
        case .invalid:
            // Something about this picture is not valid on the wire. A keyframe is the clean way out of it.
            lock.lock()
            invalidRun += 1
            let giveUp = invalidRun > 30
            lock.unlock()
            if giveUp { onGone?(.error) } else { requestKeyframe(force: false) }
        case .sent, .dropped, .waitingForKeyframe:
            lock.lock()
            invalidRun = 0
            if result == .sent { sent += 1 } else { refused += 1 }
            lock.unlock()
        }
        report()
    }

    /// Where the time goes on this side, for a real run to say: pictures captured and encoded a second, the time in the encoder, and what
    /// the core sent or turned away (a link that is behind). `log show --predicate 'process == "Tandem"' --info` reads it back.
    private func report() {
        let now = Date()
        let (target, last, total, turned) = lock.withLock { (encoder, logged, sent, refused) }
        let span = now.timeIntervalSince(last.at)
        guard span >= 10 else { return }
        let counters = target.stats
        lock.withLock { logged = (now, counters, total, turned) }
        let encoded = counters.encoded - last.counters.encoded
        let average = encoded > 0 ? Double(counters.encodeMicros - last.counters.encodeMicros) / Double(encoded) / 1000 : 0
        NSLog(
            "Tandem: screen %llu: %.1f captured and %.1f encoded a second (%ld dropped in the encoder), %.1f ms in the encoder (longest so far %.1f), %.1f Mbit/s, %ld keyframes, %ld sent and %ld turned away by the core",
            id, Double(counters.captured - last.counters.captured) / span, Double(encoded) / span, counters.dropped - last.counters.dropped,
            average, Double(counters.longestMicros) / 1000, Double(counters.bytes - last.counters.bytes) * 8 / span / 1e6,
            counters.keyframes - last.counters.keyframes, total - last.sent, turned - last.refused
        )
    }

    // MARK: The display changed

    struct Shape {
        var width: Int
        var height: Int
        var changed: Bool
    }

    /// The capture and the encoder again, for whatever the main display is now. The encoder is only replaced when the
    /// size is different, because that is the only case where the viewer has to be told something new. `announce` runs
    /// after the old pictures have stopped and before the first picture of the new shape can exist, which is the order
    /// the contract wants.
    ///
    /// For a second screen that is turned, `reshape` gives the display its new shape while nothing captures it (a capture whose display
    /// changes under it stops, and the session with it), and `size` is the picture of that shape. When `reshape` says the display would
    /// not change, the picture stays as it was.
    func restart(size wanted: (width: Int, height: Int)? = nil, reshape: (@MainActor () async -> Bool)? = nil, announce: (Shape) throws -> Void) async throws {
        let (oldCapture, oldEncoder, rate, rateBitrate) = lock.withLock { (capturer, encoder, fps, bitrate) }
        await oldCapture.stop()
        let reshaped = await reshape?() ?? true

        // A second screen stays the one that was made for the viewer; only the main display follows the changes of the system.
        let virtual = lock.withLock { virtualDisplay }
        let fresh = virtual.map { ScreenCapturer.DisplayInfo(id: $0.displayID, bounds: $0.bounds, pixels: $0.pixels) } ?? ScreenCapturer.mainDisplay()
        let before = oldEncoder.config
        // The picture of a second screen stays the size it was made for, which can be smaller than the pixels it is drawn in.
        let kept = virtual != nil ? (width: before.width, height: before.height)
            : ScreenGeometry.fit(source: fresh.pixels, maxWidth: limits.width, maxHeight: limits.height)
        let size = (reshaped ? wanted : nil) ?? kept
        let changed = size.width != before.width || size.height != before.height

        var nextEncoder = oldEncoder
        if changed {
            oldEncoder.onFrame = nil
            nextEncoder = try ScreenEncoder(.init(width: size.width, height: size.height, fps: rate, bitrate: rateBitrate))
            wire(nextEncoder)
        }
        try announce(Shape(width: size.width, height: size.height, changed: changed))

        let nextCapture = ScreenCapturer()
        wire(nextCapture)
        lock.withLock {
            capturer = nextCapture
            display = fresh
            encoder = nextEncoder
        }
        try await nextCapture.start(display: fresh, width: size.width, height: size.height, fps: rate)
        if changed { oldEncoder.invalidate() }
    }
}

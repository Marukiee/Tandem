import AppKit
import ApplicationServices
import Foundation
import Observation
import TandemCore

/// This Mac as the host of a remote desktop: another device (the Android app) looks at the screen and, when allowed,
/// controls it. The core moves the video and the input; this file is the app side of `docs/SCREEN.md`.
///
/// One viewer at a time. The picture of one display is captured, encoded once and pushed into the core, which decides
/// per frame whether the link can take it. Everything the core calls (keyframe, bitrate, input, stop) lands here.
///
/// What is asked of the person, and when:
/// - A device that is not trusted for this yet gets a prompt on the Mac, with a choice to let it control or only watch.
/// - "Always allow" is kept by the core as the policy of that device, so it is also what Settings shows and changes.
/// - A viewer that drops and comes back within two minutes after a network blip is not asked again.
@MainActor
@Observable
final class ScreenHost {
    static let shared = ScreenHost()

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
    }

    /// A request that waits for the person at the Mac.
    struct Prompt: Identifiable, Equatable {
        let id: UInt64
        let peer: String
        let name: String
        let symbol: String
        let wantsControl: Bool
        var allowControl: Bool
    }

    private(set) var current: Remote?
    private(set) var prompt: Prompt?
    /// Whether the Accessibility permission is there, for the prompt and the indicator to show the truth.
    private(set) var accessibility = AXIsProcessTrusted()

    /// The master switch: off, and requests are turned away and the cap is not announced.
    var enabled = UserDefaults.standard.object(forKey: "screenHostEnabled") as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(enabled, forKey: "screenHostEnabled")
            if !enabled { stopCurrent(reason: .ended) }
        }
    }

    /// The cap went out in the Hello at start, or did not. Permissions change while the app runs, the Hello does not.
    @ObservationIgnored private(set) var advertised = false

    @ObservationIgnored private var engine: TandemEngine?
    @ObservationIgnored private weak var model: EngineModel?
    @ObservationIgnored private let registry = SessionRegistry()
    @ObservationIgnored private var recent: [String: (control: Bool, until: Date)] = [:]
    @ObservationIgnored private var promptTimeout: Task<Void, Never>?
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
        return AXIsProcessTrusted() ? ["screen.host", "screen.control"] : ["screen.host"]
    }

    /// Screen Recording was allowed after the app started, so the Hello has to be said again, which is a restart.
    var needsRestart: Bool { enabled && ScreenCapturer.hasPermission && !advertised }

    func attach(engine: TandemEngine, model: EngineModel) {
        self.engine = engine
        self.model = model
        engine.setMediaHost(host: bridge)
        observers.append(NotificationCenter.default.addObserver(
            forName: NSApplication.didChangeScreenParametersNotification, object: nil, queue: .main
        ) { [weak self] _ in MainActor.assumeIsolated { self?.displayChanged() } })
        // Wake from sleep stops a capture stream without a word on some Macs.
        observers.append(NSWorkspace.shared.notificationCenter.addObserver(
            forName: NSWorkspace.didWakeNotification, object: nil, queue: .main
        ) { [weak self] _ in MainActor.assumeIsolated { self?.displayChanged() } })
    }

    func shutdown() {
        stopCurrent(reason: .ended)
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

        // A new request from the viewer that already has the screen replaces its session. Anyone else waits.
        if let running = current {
            guard running.peer == peer else { return deny(.busy) }
            teardown(session: running.id, tell: false)
        }
        if let waiting = prompt, waiting.peer != peer { return deny(.busy) }

        let name = model?.device(peer)?.name ?? String(localized: "A device")
        accessibility = AXIsProcessTrusted()
        let controlPolicy = (try? engine.mediaPolicy(id: peer))?.control ?? .ask
        let controlPossible = request.control && accessibility && controlPolicy != .never

        if preApproved {
            start(request: request, peer: peer, name: name, control: controlPossible)
        } else if let again = recent[peer], again.until > Date() {
            start(request: request, peer: peer, name: name, control: controlPossible && again.control)
        } else {
            let symbol = model?.device(peer)?.platform.symbol ?? "iphone"
            prompt = Prompt(id: id, peer: peer, name: name, symbol: symbol, wantsControl: request.control && controlPolicy != .never, allowControl: true)
            pendingRequest = request
            ScreenPanels.shared.update()
            Notifier.shared.post(
                id: "screen.\(id)",
                title: request.control ? String(localized: "\(name) wants to control this Mac") : String(localized: "\(name) wants to see this Mac"),
                body: String(localized: "Choose what to do in Tandem."),
                sound: true
            )
            promptTimeout?.cancel()
            promptTimeout = Task { [weak self] in
                // The core gives up after sixty seconds; the prompt goes just before.
                try? await Task.sleep(for: .seconds(58))
                guard !Task.isCancelled else { return }
                self?.dismissPrompt(session: id)
            }
        }
    }

    @ObservationIgnored private var pendingRequest: TandemMediaRequest?

    enum Answer { case allowOnce, always, deny, never }

    func answer(_ answer: Answer) {
        guard let engine, let waiting = prompt, let request = pendingRequest, request.session == waiting.id else { return }
        let control = waiting.wantsControl && waiting.allowControl && AXIsProcessTrusted()
        closePrompt()
        switch answer {
        case .deny:
            try? engine.mediaDeny(session: waiting.id, reason: .declined)
        case .never:
            changePolicy(peer: waiting.peer) { $0.screen = .never }
            try? engine.mediaDeny(session: waiting.id, reason: .declined)
        case .allowOnce:
            start(request: request, peer: waiting.peer, name: waiting.name, control: control)
        case .always:
            changePolicy(peer: waiting.peer) { policy in
                policy.screen = .always
                if control { policy.control = .always }
            }
            start(request: request, peer: waiting.peer, name: waiting.name, control: control)
        }
    }

    func setPromptControl(_ allowed: Bool) {
        prompt?.allowControl = allowed
        ScreenPanels.shared.update()
    }

    private func dismissPrompt(session: UInt64) {
        guard prompt?.id == session else { return }
        closePrompt()
    }

    private func closePrompt() {
        promptTimeout?.cancel()
        if let waiting = prompt { Notifier.shared.remove(id: "screen.\(waiting.id)") }
        prompt = nil
        pendingRequest = nil
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
        let display = ScreenCapturer.mainDisplay()
        let size = ScreenGeometry.fit(source: display.pixels, maxWidth: Int(request.maxWidth), maxHeight: Int(request.maxHeight))
        let fps = ScreenGeometry.frameRate(requested: Int(request.maxFps))
        let bitrate = ScreenGeometry.startingBitrate(width: size.width, height: size.height, fps: fps, requestedMax: Int(request.maxBitrate))

        let live: ScreenHostSession
        do {
            let encoder = try ScreenEncoder(.init(width: size.width, height: size.height, fps: fps, bitrate: bitrate))
            live = ScreenHostSession(
                id: id, peer: peer, engine: engine, encoder: encoder, display: display,
                limits: (Int(request.maxWidth), Int(request.maxHeight)), baseFps: fps, startBitrate: bitrate
            )
        } catch {
            try? engine.mediaDeny(session: id, reason: .unavailable)
            return
        }
        registry.add(live)
        live.onGone = { [weak self] reason in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.teardown(session: id, tell: true, reason: reason) } }
        }
        current = Remote(id: id, peer: peer, name: name, control: control, offered: control, startedAt: Date(), width: size.width, height: size.height)
        ScreenPanels.shared.update()

        Task { [weak self] in
            do {
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

    /// The person pressed Stop, or the switch went off.
    func stopCurrent(reason: TandemMediaEnd = .ended) {
        if let waiting = prompt, current == nil {
            try? engine?.mediaDeny(session: waiting.id, reason: .declined)
            closePrompt()
            return
        }
        guard let running = current else { return }
        teardown(session: running.id, tell: true, reason: reason)
    }

    /// Everything that belongs to a session, gone. `tell` is for a stop that started here: the core still has the
    /// session and the viewer has to hear about it.
    func teardown(session: UInt64, tell: Bool, reason: TandemMediaEnd = .ended) {
        let live = registry.remove(session)
        if tell { try? engine?.mediaStop(session: session) }
        if current?.id == session {
            let running = current
            current = nil
            // A viewer that was lost, and not stopped, may come back from a network blip without being asked again.
            if let running, !tell, [.peerGone, .error, .timeout].contains(reason) {
                recent[running.peer] = (running.control, Date().addingTimeInterval(120))
            }
            model?.injector.endRemote()
            ScreenPanels.shared.update()
        }
        if let live { Task { await live.stop() } }
    }

    /// The core ended a session.
    func ended(session: UInt64, reason: TandemMediaEnd) {
        if prompt?.id == session {
            closePrompt()
            return
        }
        teardown(session: session, tell: false, reason: reason)
    }

    /// Turns control on or off for the session that runs, without ending it.
    func setControl(_ allowed: Bool) {
        guard let engine, var running = current else { return }
        let granted = allowed && AXIsProcessTrusted()
        try? engine.mediaUpdate(session: running.id, update: TandemMediaUpdate(width: nil, height: nil, rotation: nil, fps: nil, control: granted))
        running.control = granted
        current = running
        if !granted { model?.injector.endRemote() }
        ScreenPanels.shared.update()
    }

    // MARK: Input

    func input(session: UInt64, input: TandemMediaInput) {
        guard let running = current, running.id == session, running.control, let live = registry.get(session) else { return }
        model?.injector.handle(remote: input, in: live.geometry, from: running.peer)
    }

    // MARK: The display changes

    /// A different resolution, another display as the main one, a wake from sleep: the capture is started again with the
    /// size that fits now, and the viewer is told the new shape before the first picture of it.
    func displayChanged() {
        guard let running = current, let live = registry.get(running.id), !recovering else { return }
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
                    if var now = self.current, now.id == running.id {
                        now.width = shape.width
                        now.height = shape.height
                        self.current = now
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

    init(id: UInt64, peer: String, engine: TandemEngine, encoder: ScreenEncoder, display: ScreenCapturer.DisplayInfo,
         limits: (Int, Int), baseFps: Int, startBitrate: Int) {
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
            lock.unlock()
        }
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
    func restart(announce: (Shape) throws -> Void) async throws {
        let (oldCapture, oldEncoder, rate, rateBitrate) = lock.withLock { (capturer, encoder, fps, bitrate) }

        let fresh = ScreenCapturer.mainDisplay()
        let size = ScreenGeometry.fit(source: fresh.pixels, maxWidth: limits.width, maxHeight: limits.height)
        let before = oldEncoder.config
        let changed = size.width != before.width || size.height != before.height

        await oldCapture.stop()
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

import AppKit
import Observation
import SwiftUI
import TandemCore

/// The core's calls for the sessions this Mac asked for, on to the right picture. The core calls these from threads of
/// its own, so this only looks the session up and moves on: frames go to the decoder, news goes to the main actor.
final class LiveRouter: TandemMediaViewer, @unchecked Sendable {
    private let lock = NSLock()
    private var pipelines: [UInt64: LivePipeline] = [:]

    func register(_ pipeline: LivePipeline, for session: UInt64) {
        lock.lock()
        pipelines[session] = pipeline
        lock.unlock()
    }

    func remove(_ session: UInt64) {
        lock.lock()
        let pipeline = pipelines.removeValue(forKey: session)
        lock.unlock()
        pipeline?.decoder.onImage = nil
        pipeline?.decoder.onNeedsKeyframe = nil
    }

    private func pipeline(_ session: UInt64) -> LivePipeline? {
        lock.lock()
        defer { lock.unlock() }
        return pipelines[session]
    }

    func onAccepted(session: UInt64, accept: TandemMediaAccept) {
        pipeline(session)?.accepted(accept)
    }

    func onUpdate(session: UInt64, update: TandemMediaUpdate) {
        pipeline(session)?.updated(update)
    }

    func onFrame(session: UInt64, ptsUs: UInt64, keyframe: Bool, discontinuity: Bool, data: Data) {
        pipeline(session)?.decoder.decode(data, ptsUs: ptsUs, keyframe: keyframe, discontinuity: discontinuity)
    }

    func onEnded(session: UInt64, reason: TandemMediaEnd) {
        pipeline(session)?.ended(reason)
    }
}

/// The decoder of one session and the way news about it reaches the main actor.
final class LivePipeline: @unchecked Sendable {
    let decoder = H264Decoder()
    var accepted: @Sendable (TandemMediaAccept) -> Void = { _ in }
    var updated: @Sendable (TandemMediaUpdate) -> Void = { _ in }
    var ended: @Sendable (TandemMediaEnd) -> Void = { _ in }
}

/// Every window of a phone's screen or camera. Starting one is a request to the phone; the phone's person decides.
@MainActor
@Observable
final class LiveManager {
    static let shared = LiveManager()

    private(set) var sessions: [LiveSession] = []
    private var windows: [ObjectIdentifier: LiveWindowController] = [:]
    private let router = LiveRouter()
    private var engine: TandemEngine?
    private var statsTimer: Timer?
    private var previousCounters: [UInt64: (frames: UInt64, bytes: UInt64, at: Date)] = [:]

    /// Called once the engine runs: from now on the pictures of the sessions asked for come here.
    func attach(engine: TandemEngine) {
        self.engine = engine
        engine.setMediaViewer(viewer: router)
    }

    func session(of kind: TandemMediaKind, on peer: String) -> LiveSession? {
        sessions.first { $0.kind == kind && $0.peer == peer && $0.isRunning }
    }

    // MARK: Starting

    /// Opens a window and asks the phone. A window of this kind for this phone that is already there comes to the front.
    @discardableResult
    func start(
        device: TandemDevice, kind: TandemMediaKind, facing: LiveCameraFacing = .back, quality: LiveQuality = .standard,
        reusing: LiveSession? = nil
    ) -> LiveSession? {
        if let existing = session(of: kind, on: device.id), existing.cameraFacing == facing || kind == .screen {
            windows[ObjectIdentifier(existing)]?.bringToFront()
            return existing
        }
        guard let engine else { return nil }
        // A second request of the same kind replaces the first, so the old window is told to let go quietly.
        // Try again after an end is the same window asking again: an ended session is not "running", so it has to be named.
        let replaced = reusing ?? session(of: kind, on: device.id)

        let want = TandemMediaWant(
            kind: kind, codecs: [.h264], maxWidth: quality.box(for: kind).long, maxHeight: quality.box(for: kind).short,
            maxFps: kind == .screen ? 60 : quality.maxFps, maxBitrate: 0, control: kind == .screen,
            facing: kind == .camera ? facing.facing : .any
        )
        let id: UInt64
        do {
            id = try engine.mediaRequest(peer: device.id, want: want)
        } catch {
            EngineModel.shared.showToast(String(localized: "\(device.name) cannot be reached right now"))
            return nil
        }
        let session = replaced ?? LiveSession(
            id: id, peer: device.id, phoneName: device.name, kind: kind, cameraFacing: facing, quality: quality
        )
        if let replaced {
            reset(replaced, id: id, facing: facing, quality: quality)
        }
        bind(session, to: id)
        if let reused = replaced, let controller = windows[ObjectIdentifier(reused)] {
            controller.bringToFront()
        }
        if replaced == nil {
            sessions.append(session)
            let controller = LiveWindowController(session: session, manager: self)
            windows[ObjectIdentifier(session)] = controller
            controller.show()
        }
        startStatsTimer()
        return session
    }

    /// The same window asks again: after an end, or with another camera or quality.
    func restart(_ session: LiveSession) {
        guard let device = EngineModel.shared.device(session.peer) else { return }
        start(device: device, kind: session.kind, facing: session.cameraFacing, quality: session.quality, reusing: session)
    }

    private func reset(_ session: LiveSession, id: UInt64, facing: LiveCameraFacing, quality: LiveQuality) {
        let old = session.id
        if old != id {
            router.remove(old)
            previousCounters[old] = nil
        }
        session.id = id
        session.phase = .requesting
        session.hasPicture = false
        session.stoppedByMe = false
        session.cameraFacing = facing
        session.quality = quality
        session.mirrored = session.kind == .camera && facing == .front
        session.phoneRotation = 0
        session.extraRotation = 0
        session.stats = nil
        session.surface.clear()
    }

    private func bind(_ session: LiveSession, to id: UInt64) {
        let pipeline = LivePipeline()
        let engine = engine
        pipeline.decoder.onNeedsKeyframe = { try? engine?.mediaRequestKeyframe(session: id) }
        pipeline.decoder.onImage = { [weak session] image, _ in
            guard let session else { return }
            // Straight to the layer from the decoder's thread; the main actor only hears that the first one is there.
            session.surface.show(image)
            if !session.firstPicture.exchange(true) {
                Task { @MainActor in session.hasPicture = true }
            }
        }
        pipeline.accepted = { [weak self, weak session] accept in
            Task { @MainActor in
                guard let session, session.id == id else { return }
                session.width = Int(accept.width)
                session.height = Int(accept.height)
                session.controlGranted = accept.control
                session.phase = .active
                self?.windows[ObjectIdentifier(session)]?.contentSizeChanged()
            }
        }
        pipeline.updated = { [weak self, weak session] update in
            Task { @MainActor in
                guard let session, session.id == id else { return }
                if let width = update.width, let height = update.height {
                    session.width = Int(width)
                    session.height = Int(height)
                }
                if let rotation = update.rotation { session.phoneRotation = Int(rotation) % 360 }
                if let control = update.control { session.controlGranted = control }
                self?.windows[ObjectIdentifier(session)]?.contentSizeChanged()
            }
        }
        pipeline.ended = { [weak self, weak session] reason in
            Task { @MainActor in
                guard let session, session.id == id else { return }
                self?.finish(session, reason: reason)
            }
        }
        session.firstPicture.reset()
        session.surface.isControlActive = { [weak session] in
            guard let session else { return false }
            return session.controlGranted && session.controlOn && !session.phase.isEnded
        }
        // What is on the other side decides what the keys and the right button mean there.
        let platform = EngineModel.shared.device(session.peer)?.platform
        session.surface.remote = platform == .android ? .phone : platform == .macOs ? .mac : .pc
        session.surface.onInput = { [weak self, weak session] input in
            guard let session else { return }
            self?.sendInput(session, input)
        }
        router.register(pipeline, for: id)
    }

    /// A click, a scroll or a key for the phone, when it allowed this.
    func sendInput(_ session: LiveSession, _ input: TandemMediaInput) {
        guard session.controlGranted, let engine else { return }
        try? engine.mediaSendInput(session: session.id, input: input)
    }

    private func finish(_ session: LiveSession, reason: TandemMediaEnd) {
        session.phase = .ended(LiveEnd(reason, byMe: session.stoppedByMe))
        router.remove(session.id)
        previousCounters[session.id] = nil
        windows[ObjectIdentifier(session)]?.contentSizeChanged()
    }

    // MARK: Stopping

    /// Ends the session. Stopped from the window, the window goes with it: there is nothing to say about an end that was asked for.
    /// An end that was not asked for (the other side stopped, the link went) stays in the window with what happened.
    func stop(_ session: LiveSession, closingWindow: Bool = true) {
        guard session.isRunning else { return }
        session.stoppedByMe = true
        try? engine?.mediaStop(session: session.id)
        finish(session, reason: .ended)
        if closingWindow { windows[ObjectIdentifier(session)]?.close() }
    }

    /// The window was closed: the session goes with it.
    func windowClosed(_ session: LiveSession) {
        if session.isRunning { stop(session, closingWindow: false) }
        router.remove(session.id)
        windows[ObjectIdentifier(session)] = nil
        sessions.removeAll { $0 === session }
        if sessions.isEmpty {
            statsTimer?.invalidate()
            statsTimer = nil
        }
    }

    func stopAll() {
        for session in sessions where session.isRunning { stop(session, closingWindow: false) }
    }

    // MARK: A phone that offers

    /// The person started the sharing on the phone itself: open the window and ask. The phone is waiting for exactly this.
    func handleOffer(from peer: String, kind: TandemMediaKind, facing: TandemMediaFacing) {
        guard let device = EngineModel.shared.device(peer), device.online else { return }
        let camera: LiveCameraFacing = facing == .front ? .front : .back
        start(device: device, kind: kind, facing: camera, quality: .standard)
        NSApp.activate(ignoringOtherApps: true)
    }

    // MARK: Stats

    private func startStatsTimer() {
        guard statsTimer == nil else { return }
        statsTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.refreshStats() }
        }
    }

    private func refreshStats() {
        guard let engine else { return }
        for session in sessions where session.isRunning && session.phase == .active {
            guard session.showStats, let stats = engine.mediaStats(session: session.id) else { continue }
            let now = Date()
            var fps = 0.0
            var megabits = 0.0
            if let before = previousCounters[session.id] {
                let seconds = max(0.2, now.timeIntervalSince(before.at))
                fps = Double(stats.framesOut &- before.frames) / seconds
                megabits = Double(stats.bytes &- before.bytes) * 8 / seconds / 1_000_000
            }
            previousCounters[session.id] = (stats.framesOut, stats.bytes, now)
            let decoder = router.decoderCounters(session.id)
            session.stats = LiveStats(
                fps: fps, megabitsPerSecond: megabits, rttMs: stats.rttMs.map(Int.init),
                lost: Int(stats.lost), droppedLocally: Int(stats.appDropped) + (decoder?.framesDropped ?? 0),
                keyframesRequested: Int(stats.keyframesRequested), targetMegabits: Double(stats.targetBitrate) / 1_000_000,
                frameAgeMs: stats.lastFrameAgeMs.map(Int.init), decodeMs: decoder?.averageDecodeMilliseconds ?? 0
            )
        }
    }

    // MARK: Snapshots for the debug harness

    func prepareSnapshots() {
        for session in sessions { session.surface.prepareSnapshot() }
    }

    func finishSnapshots() {
        for session in sessions { session.surface.finishSnapshot() }
    }

    // MARK: Debug feed

    /// Opens a window on a session that no phone is behind and feeds it from a recorded stream. For the debug harness.
    func debugOpen(
        kind: TandemMediaKind, name: String, width: Int, height: Int, rotation: Int, mirrored: Bool, phase: LivePhase
    ) -> LiveSession {
        let session = LiveSession(
            id: UInt64.random(in: 1...UInt64.max), peer: "debug", phoneName: name, kind: kind,
            cameraFacing: mirrored ? .front : .back, quality: .standard
        )
        session.width = width
        session.height = height
        session.phoneRotation = rotation
        session.mirrored = mirrored
        session.phase = phase
        sessions.append(session)
        let controller = LiveWindowController(session: session, manager: self)
        windows[ObjectIdentifier(session)] = controller
        controller.show()
        return session
    }

    func debugController(_ session: LiveSession) -> LiveWindowController? { windows[ObjectIdentifier(session)] }
}

/// A flag that flips once, from any thread, so the first picture is announced to the main actor only once.
final class OnceFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var value = false

    /// Sets the flag and says what it was.
    func exchange(_ new: Bool) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        let old = value
        value = new
        return old
    }

    func reset() {
        lock.lock()
        value = false
        lock.unlock()
    }
}

extension LiveRouter {
    func decoderCounters(_ session: UInt64) -> H264Decoder.Counters? {
        lock.lock()
        defer { lock.unlock() }
        return pipelines[session]?.decoder.counters
    }
}

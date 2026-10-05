import AppKit
import Observation
import SwiftUI
import TandemCore

/// Where a window of the phone's screen or camera is in its life.
extension LivePhase {
    var isEnded: Bool {
        if case .ended = self { return true }
        return false
    }
}

enum LivePhase: Equatable {
    /// The request is out and the phone has not answered. The person on the phone may still be asked.
    case requesting
    /// Accepted. The first picture may take a moment.
    case active
    /// Over. Why decides what the window says.
    case ended(LiveEnd)
}

enum LiveEnd: Equatable {
    /// The person closed or stopped it here.
    case stopped
    /// The person on the phone stopped it.
    case phoneStopped
    case declined
    /// The phone is set to never show this to this Mac.
    case refused
    case busy
    case unsupported
    /// The phone would, but cannot: no permission to capture, no camera.
    case unavailable
    case noAnswer
    /// The connection was gone for longer than the core waits.
    case lost
    case failed

    init(_ reason: TandemMediaEnd, byMe: Bool) {
        if byMe { self = .stopped; return }
        switch reason {
        case .ended, .replaced: self = .phoneStopped
        case .declined: self = .declined
        case .policy: self = .refused
        case .busy: self = .busy
        case .unsupported: self = .unsupported
        case .unavailable: self = .unavailable
        case .timeout: self = .noAnswer
        case .peerGone: self = .lost
        case .unknownSession, .error: self = .failed
        }
    }
}

/// What the window says, in words. One place, so the copy is the same on the screen and in the tests.
struct LiveCopy: Equatable {
    var title: String
    var detail: String
    var symbol: String
    var canRetry: Bool

    static func requesting(name: String, kind: TandemMediaKind) -> LiveCopy {
        LiveCopy(
            title: String(localized: "Waiting for \(name)"),
            detail: kind == .camera
                ? String(localized: "Your phone asks first. Open the notification on it and allow the camera.")
                : String(localized: "Your phone asks first. Open the notification on it, allow, and confirm the screen sharing."),
            symbol: kind == .camera ? "camera" : "iphone.gen3", canRetry: false
        )
    }

    static func firstPicture(kind: TandemMediaKind) -> LiveCopy {
        LiveCopy(
            title: kind == .camera ? String(localized: "Starting the camera") : String(localized: "Starting the screen"),
            detail: "", symbol: kind == .camera ? "camera" : "iphone.gen3", canRetry: false
        )
    }

    static func ended(_ end: LiveEnd, name: String, kind: TandemMediaKind) -> LiveCopy {
        let what = kind == .camera ? String(localized: "its camera") : String(localized: "its screen")
        switch end {
        case .stopped:
            return LiveCopy(title: String(localized: "Stopped"), detail: "", symbol: "stop.circle", canRetry: true)
        case .phoneStopped:
            return LiveCopy(title: String(localized: "\(name) stopped sharing"), detail: "", symbol: "stop.circle", canRetry: true)
        case .declined:
            return LiveCopy(
                title: String(localized: "\(name) said no"),
                detail: String(localized: "Nothing is shown until it is allowed on the phone."), symbol: "hand.raised", canRetry: true
            )
        case .refused:
            return LiveCopy(
                title: String(localized: "\(name) is set to never show \(what) here"),
                detail: String(localized: "Change it in Tandem on the phone, on the page of this Mac."), symbol: "hand.raised", canRetry: false
            )
        case .busy:
            return LiveCopy(
                title: String(localized: "\(name) is busy"),
                detail: String(localized: "It is already sharing. Try again in a moment."), symbol: "clock", canRetry: true
            )
        case .unsupported:
            return LiveCopy(
                title: String(localized: "\(name) cannot show \(what)"),
                detail: String(localized: "Update Tandem on the phone and try again."), symbol: "exclamationmark.triangle", canRetry: false
            )
        case .unavailable:
            return LiveCopy(
                title: String(localized: "\(name) could not start"),
                detail: kind == .camera
                    ? String(localized: "The camera is in use by another app, or Tandem is not allowed to use it.")
                    : String(localized: "Screen sharing was not confirmed on the phone."),
                symbol: "exclamationmark.triangle", canRetry: true
            )
        case .noAnswer:
            return LiveCopy(
                title: String(localized: "\(name) did not answer"),
                detail: String(localized: "The phone may be locked or asleep. Unlock it and try again."), symbol: "moon.zzz", canRetry: true
            )
        case .lost:
            return LiveCopy(
                title: String(localized: "Lost the connection to \(name)"),
                detail: String(localized: "Both devices need to be on the same network, or reach each other another way."), symbol: "wifi.slash", canRetry: true
            )
        case .failed:
            return LiveCopy(
                title: String(localized: "Something went wrong"),
                detail: String(localized: "Try again. If it keeps happening, restart Tandem on the phone."), symbol: "exclamationmark.triangle", canRetry: true
            )
        }
    }
}

/// Where the camera of the phone points, and how sharp. What the person picks in the window.
enum LiveCameraFacing: String, CaseIterable, Identifiable {
    case back, front
    var id: String { rawValue }

    var facing: TandemMediaFacing { self == .front ? .front : .back }
}

enum LiveQuality: String, CaseIterable, Identifiable {
    case standard, high
    var id: String { rawValue }

    /// The box the phone fits the picture in, long side by short side. Whichever way the phone holds the camera.
    func box(for kind: TandemMediaKind) -> (long: UInt32, short: UInt32) {
        switch (kind, self) {
        case (.screen, .standard): (1920, 1080)
        case (.screen, .high): (2560, 1440)
        case (.camera, .standard): (1280, 720)
        case (.camera, .high): (1920, 1080)
        }
    }

    var maxFps: UInt32 { 30 }
}

/// One window's worth of live video, from the request to the end. The decoder and the layer live next to it; this is
/// what the views read.
@MainActor
@Observable
final class LiveSession {
    /// The session id of the core. It changes when the request is made again.
    var id: UInt64
    let peer: String
    var phoneName: String
    let kind: TandemMediaKind

    var phase: LivePhase = .requesting
    var width = 0
    var height = 0
    /// Degrees clockwise the phone says the picture has to be turned.
    var phoneRotation = 0
    /// Added by the person with the rotate button.
    var extraRotation = 0
    var mirrored: Bool
    var cameraFacing: LiveCameraFacing
    var quality: LiveQuality
    var hasPicture = false
    var stats: LiveStats?
    var alwaysOnTop = false
    var frameless = false
    var showStats: Bool = UserDefaults.standard.bool(forKey: "liveShowStats") {
        didSet { UserDefaults.standard.set(showStats, forKey: "liveShowStats") }
    }
    var notice: String?
    /// True once the person asked to stop, so the end of the session is not reported as the phone's doing.
    var stoppedByMe = false
    /// The phone let this Mac click and type (it asked, and the phone has its accessibility service on).
    var controlGranted = false
    /// The person's own switch: the mouse and keys go to the phone only while it is on.
    var controlOn = true

    let surface = VideoSurfaceView()
    /// Flips when the first picture is drawn, so the decoder's thread can tell the main actor once.
    let firstPicture = OnceFlag()

    init(id: UInt64, peer: String, phoneName: String, kind: TandemMediaKind, cameraFacing: LiveCameraFacing, quality: LiveQuality) {
        self.id = id
        self.peer = peer
        self.phoneName = phoneName
        self.kind = kind
        self.cameraFacing = cameraFacing
        self.quality = quality
        self.mirrored = kind == .camera && cameraFacing == .front
    }

    var orientation: VideoOrientation {
        VideoOrientation(phoneRotation: phoneRotation, extraRotation: extraRotation, mirrored: mirrored)
    }

    /// The width and height of the picture as it is shown, once turned. Zero before the phone has said.
    var shownSize: CGSize {
        let size = orientation.shown(width: width, height: height)
        return CGSize(width: size.width, height: size.height)
    }

    var isRunning: Bool {
        if case .ended = phase { return false }
        return true
    }

    var title: String { phoneName }
    var subtitle: String { kind == .camera ? String(localized: "Camera") : String(localized: "Screen") }
}

/// Counters for the stats line, worked out once a second from the core's and the decoder's.
struct LiveStats: Equatable {
    var fps = 0.0
    var megabitsPerSecond = 0.0
    var rttMs: Int?
    var lost = 0
    var droppedLocally = 0
    var keyframesRequested = 0
    var targetMegabits = 0.0
    var frameAgeMs: Int?
    var decodeMs = 0.0

    var line: String {
        var parts = [String(format: "%.0f fps", fps), String(format: "%.1f Mbit/s", megabitsPerSecond)]
        if let rttMs { parts.append("\(rttMs) ms") }
        parts.append(String(localized: "lost \(lost)"))
        if droppedLocally > 0 { parts.append(String(localized: "dropped \(droppedLocally)")) }
        parts.append(String(format: "decode %.1f ms", decodeMs))
        return parts.joined(separator: "  ·  ")
    }
}

import AppKit
import CoreVideo
import Foundation
import Security
import TandemCore
import VideoToolbox

/// Where a session's pictures come from: the capture of the system (`ScreenCapturer`), or Screen Sharing while this Mac is locked
/// (`ScreenSharingSource`).
protocol ScreenSource: AnyObject, Sendable {
    var onFrame: ((CVPixelBuffer) -> Void)? { get set }
    var onStopped: ((Error?) -> Void)? { get set }
    func start(display: ScreenCapturer.DisplayInfo, width: Int, height: Int, fps: Int) async throws
    func setFrameRate(_ fps: Int) async
    func stop() async
}

extension ScreenCapturer: ScreenSource {}

/// Reaching this Mac while it is locked: what it needs, and whether it is all there.
///
/// It needs Screen Sharing of macOS on, and the password of this Mac's account, which Screen Sharing asks before it shows anything. The
/// password is kept in the login Keychain, which only this app (by its Developer ID) reads, and is only used to open Screen Sharing on
/// this Mac itself: the lock screen still wants the password typed by whoever is at the other end.
enum LockedAccess {
    private static var service: String { (Bundle.main.bundleIdentifier ?? "nl.markmaaktmedia.Tandem") + ".screen-sharing" }
    private static let failureKey = "lockedAccessFailure"

    /// For a test against a server of its own: another port than Screen Sharing's, a password that is not kept.
    private static let debugPort = ProcessInfo.processInfo.environment["TANDEM_DEBUG_VNC_PORT"].flatMap(UInt16.init)
    private static let debugPassword = ProcessInfo.processInfo.environment["TANDEM_DEBUG_VNC_PASSWORD"]

    static var port: UInt16 { debugPort ?? 5900 }
    static var user: String { NSUserName() }

    /// Screen Sharing (or Remote Management, which includes it) answers on this Mac.
    static var screenSharingOn: Bool {
        let probe = socket(AF_INET, SOCK_STREAM, 0)
        guard probe >= 0 else { return false }
        defer { close(probe) }
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        return withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(probe, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) == 0 }
        }
    }

    static var hasPassword: Bool { password() != nil }

    /// Everything is there to let a device in while this Mac is locked.
    static var ready: Bool { hasPassword && screenSharingOn }

    /// What went wrong the last time, for Settings to say. Nil after a try that worked.
    static var lastFailure: String? {
        get { UserDefaults.standard.string(forKey: failureKey) }
        set { UserDefaults.standard.set(newValue, forKey: failureKey) }
    }

    /// The one place the password comes from, so it can come from elsewhere (the viewer, each time) without touching the rest.
    static func password() -> String? {
        if let debugPassword { return debugPassword }
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: user,
            kSecReturnData: true, kSecUseAuthenticationUI: kSecUseAuthenticationUIFail,
        ]
        var found: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &found) == errSecSuccess, let data = found as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    /// Checks the password with Screen Sharing first, and keeps it only when it was taken.
    static func save(password: String) async throws {
        try await Task.detached { try ScreenSharingClient.check(port: port, user: user, password: password) }.value
        forget()
        let item: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: user,
            kSecAttrLabel: "Tandem: reaching this Mac while it is locked", kSecValueData: Data(password.utf8),
        ]
        let status = SecItemAdd(item as CFDictionary, nil)
        guard status == errSecSuccess else { throw NSError(domain: NSOSStatusErrorDomain, code: Int(status)) }
        lastFailure = nil
    }

    static func forget() {
        SecItemDelete([kSecClass: kSecClassGenericPassword, kSecAttrService: service, kSecAttrAccount: user] as CFDictionary)
        lastFailure = nil
    }

    static func openSharingSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.Sharing-Settings.extension") { NSWorkspace.shared.open(url) }
    }
}

/// Screen Sharing as the pictures of a session. Its picture is letterboxed into the size of the session's encoder, and the input of the
/// viewer goes back to it, in its pixels, because nothing an app posts reaches the lock screen.
final class ScreenSharingSource: ScreenSource, @unchecked Sendable {
    var onFrame: ((CVPixelBuffer) -> Void)?
    var onStopped: ((Error?) -> Void)?

    private let client = ScreenSharingClient()
    private let lock = NSLock()
    private var interval: TimeInterval = 1.0 / 30
    private var running = false
    private var target = (width: 0, height: 0)
    private var transfer: VTPixelTransferSession?
    private var sourcePool: CVPixelBufferPool?
    private var sourceSize = (width: 0, height: 0)
    private var targetPool: CVPixelBufferPool?
    private var scrollCarry = (x: 0.0, y: 0.0)

    func start(display: ScreenCapturer.DisplayInfo, width: Int, height: Int, fps: Int) async throws {
        guard let password = LockedAccess.password() else { throw ScreenSharingClient.Failure.wrongPassword }
        target = (width, height)
        interval = 1.0 / Double(max(1, fps))
        let client = client
        do {
            try await Task.detached { try client.connect(port: LockedAccess.port, user: LockedAccess.user, password: password) }.value
        } catch {
            LockedAccess.lastFailure = (error as? ScreenSharingClient.Failure).map(\.description) ?? error.localizedDescription
            throw error
        }
        LockedAccess.lastFailure = nil
        // The pointer starts in the middle, where a lock screen has its password field.
        client.movePointer(x: client.width / 2, y: client.height / 2)
        try makeTransfer()
        lock.withLock { running = true }
        let thread = Thread { [weak self] in self?.loop() }
        thread.name = "Tandem screen sharing"
        thread.qualityOfService = .userInteractive
        thread.start()
    }

    func setFrameRate(_ fps: Int) async {
        lock.withLock { interval = 1.0 / Double(max(1, fps)) }
    }

    func stop() async {
        let was = lock.withLock { () -> Bool in
            defer { running = false }
            return running
        }
        onFrame = nil
        onStopped = nil
        if was { client.releaseButtons() }
        client.close()
    }

    private func loop() {
        var full = true
        while lock.withLock({ running }) {
            let began = Date()
            do {
                if try client.update(full: full) { produce() }
                full = false
            } catch {
                let report = lock.withLock { () -> ((Error?) -> Void)? in
                    guard running else { return nil }
                    running = false
                    return onStopped
                }
                report?(error)
                return
            }
            let wait = lock.withLock { interval } - Date().timeIntervalSince(began)
            if wait > 0 { Thread.sleep(forTimeInterval: wait) }
        }
    }

    private func makeTransfer() throws {
        var session: VTPixelTransferSession?
        guard VTPixelTransferSessionCreate(allocator: nil, pixelTransferSessionOut: &session) == noErr, let session else {
            throw ScreenSharingClient.Failure.protocolError("no pixel transfer")
        }
        VTSessionSetProperty(session, key: kVTPixelTransferPropertyKey_ScalingMode, value: kVTScalingMode_Letterbox)
        VTSessionSetProperty(session, key: kVTPixelTransferPropertyKey_DestinationYCbCrMatrix, value: kCVImageBufferYCbCrMatrix_ITU_R_709_2)
        VTSessionSetProperty(session, key: kVTPixelTransferPropertyKey_DestinationColorPrimaries, value: kCVImageBufferColorPrimaries_ITU_R_709_2)
        VTSessionSetProperty(session, key: kVTPixelTransferPropertyKey_DestinationTransferFunction, value: kCVImageBufferTransferFunction_ITU_R_709_2)
        transfer = session
        targetPool = Self.pool(width: target.width, height: target.height, format: kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange)
    }

    private static func pool(width: Int, height: Int, format: OSType) -> CVPixelBufferPool? {
        let attributes: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: format, kCVPixelBufferWidthKey: width, kCVPixelBufferHeightKey: height,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
        ]
        var pool: CVPixelBufferPool?
        CVPixelBufferPoolCreate(nil, nil, attributes as CFDictionary, &pool)
        return pool
    }

    /// The picture as it is now, into a buffer of the encoder's size and format.
    private func produce() {
        guard let transfer, let targetPool, let deliver = onFrame else { return }
        _ = client.withPicture { picture in
            if sourcePool == nil || sourceSize != (picture.width, picture.height) {
                sourcePool = Self.pool(width: picture.width, height: picture.height, format: kCVPixelFormatType_32BGRA)
                sourceSize = (picture.width, picture.height)
            }
            var source: CVPixelBuffer?
            var output: CVPixelBuffer?
            guard let sourcePool, CVPixelBufferPoolCreatePixelBuffer(nil, sourcePool, &source) == kCVReturnSuccess, let source,
                  CVPixelBufferPoolCreatePixelBuffer(nil, targetPool, &output) == kCVReturnSuccess, let output
            else { return }
            CVPixelBufferLockBaseAddress(source, [])
            if let base = CVPixelBufferGetBaseAddress(source) {
                let rowBytes = CVPixelBufferGetBytesPerRow(source)
                let width = picture.width * 4
                for row in 0 ..< picture.height {
                    memcpy(base + row * rowBytes, picture.bytes + row * width, width)
                }
            }
            CVPixelBufferUnlockBaseAddress(source, [])
            guard VTPixelTransferSessionTransferImage(transfer, from: source, to: output) == noErr else { return }
            deliver(output)
        }
    }

    // MARK: Input

    /// Where the picture of Screen Sharing sits in the picture that is sent: letterboxed, so the same shape with bars where it does not
    /// fill it.
    private var content: CGRect {
        let source = CGSize(width: max(1, client.width), height: max(1, client.height))
        let scale = min(Double(target.width) / source.width, Double(target.height) / source.height)
        let size = CGSize(width: source.width * scale, height: source.height * scale)
        return CGRect(x: (Double(target.width) - size.width) / 2, y: (Double(target.height) - size.height) / 2, width: size.width, height: size.height)
    }

    /// Pixels of Screen Sharing for one pixel of the sent picture.
    private var scale: Double { Double(client.width) / max(1, content.width) }

    func handle(_ input: TandemMediaInput) {
        switch input {
        case let .pointerAbs(x, y):
            let rect = content
            let px = (Double(x) * Double(target.width) - rect.minX) / rect.width * Double(client.width)
            let py = (Double(y) * Double(target.height) - rect.minY) / rect.height * Double(client.height)
            client.movePointer(x: Int(px), y: Int(py))
        case let .pointerRel(dx, dy):
            client.movePointer(dx: Double(dx) * scale, dy: Double(dy) * scale)
        case let .button(button, down, _):
            client.button(button, down: down)
        case let .scroll(dx, dy):
            // One step of a wheel for about every 40 pixels of a swipe, kept over between swipes so slow ones still scroll.
            let step = 40.0
            scrollCarry.x += Double(dx)
            scrollCarry.y += Double(dy)
            while abs(scrollCarry.y) >= step {
                client.scrollStep(up: scrollCarry.y < 0)
                scrollCarry.y -= scrollCarry.y < 0 ? -step : step
            }
            while abs(scrollCarry.x) >= step {
                client.scrollStep(left: scrollCarry.x < 0)
                scrollCarry.x -= scrollCarry.x < 0 ? -step : step
            }
        case let .key(code, down, mods, text):
            guard !HidKeys.isModifier(code) else { return }
            guard let keysym = ScreenSharingClient.keysym(forUsage: code) else {
                if down { type(text) }
                return
            }
            // Modifiers travel as flags on the keys, so they are pressed around the key that has them.
            let held = ScreenSharingClient.modifierKeysyms.enumerated().filter { mods & (1 << UInt16($0.offset)) != 0 }.map(\.element)
            if down { for modifier in held { client.key(modifier, down: true) } }
            client.key(keysym, down: down)
            if !down { for modifier in held.reversed() { client.key(modifier, down: false) } }
        case let .text(text):
            type(text)
        }
    }

    private func type(_ text: String) {
        for scalar in text.unicodeScalars {
            let keysym = ScreenSharingClient.keysym(for: scalar)
            client.key(keysym, down: true)
            client.key(keysym, down: false)
        }
    }

    func release() {
        client.releaseButtons()
    }
}

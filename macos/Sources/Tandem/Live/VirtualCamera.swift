import AppKit
import CoreImage
import CoreMedia
import CoreMediaIO
import SwiftUI
import SystemExtensions
import TandemCore

/// The phone as a webcam that Zoom, FaceTime, Teams and the rest can pick: Tandem Camera (macos/CameraExtension). The extension is
/// the camera; this side installs it, opens the phone's camera without a window when an app starts using it, and puts the pictures
/// in. A camera window that is open already feeds it as well.
final class VirtualCamera: NSObject, @unchecked Sendable {
    static let shared = VirtualCamera()
    static let enabledKey = "webcamEnabled"
    private static let phoneKey = "webcamPhone"

    /// The size the extension says it has (macos/CameraExtension/main.swift): every picture is fitted into it.
    private static let width = 1920
    private static let height = 1080
    /// Fixed in the extension, so it can be found among the cameras.
    private static let deviceUID = "6C2B9F3E-7A41-4D8E-9B65-1F0C7E4A2D93"

    private static var extensionID: String { (Bundle.main.bundleIdentifier ?? "nl.markmaaktmedia.Tandem") + ".Camera" }

    /// Only a build signed with the Developer ID and its profile carries the extension; another build has nothing to install.
    static var isAvailable: Bool {
        let url = Bundle.main.bundleURL.appendingPathComponent("Contents/Library/SystemExtensions/\(extensionID).systemextension")
        return FileManager.default.fileExists(atPath: url.path)
    }

    @MainActor static var enabled: Bool { UserDefaults.standard.bool(forKey: enabledKey) }

    private let queue = DispatchQueue(label: "webcam", qos: .userInteractive)
    private let lock = NSLock()
    private var degrees = 0
    private var busy = false
    private var sink: (device: CMIODeviceID, stream: CMIOStreamID, queue: CMSimpleQueue)?
    private var lastLook = Date.distantPast
    private var pool: CVPixelBufferPool?
    private let context = CIContext(options: [.cacheIntermediates: false])
    /// The camera of a phone opened for an app here, without a window, to be closed again when the app is done with it.
    @MainActor private var opened: LiveSession?

    // MARK: Installing

    /// At launch and when the switch goes on: asks macOS for the extension. Asked again at every launch, so an update of the app brings
    /// the new extension along; macOS only asks the person the first time.
    @MainActor
    func start() {
        guard Self.isAvailable else { return }
        listen()
        if Self.enabled { install() }
    }

    @MainActor
    func install() {
        let request = OSSystemExtensionRequest.activationRequest(forExtensionWithIdentifier: Self.extensionID, queue: .main)
        request.delegate = self
        OSSystemExtensionManager.shared.submitRequest(request)
    }

    @MainActor
    func uninstall() {
        let request = OSSystemExtensionRequest.deactivationRequest(forExtensionWithIdentifier: Self.extensionID, queue: .main)
        request.delegate = self
        OSSystemExtensionManager.shared.submitRequest(request)
    }

    // MARK: An app starts or stops using the camera

    @MainActor
    private func listen() {
        let center = CFNotificationCenterGetDarwinNotifyCenter()
        let base = Bundle.main.bundleIdentifier ?? "nl.markmaaktmedia.Tandem"
        let observer = Unmanaged.passUnretained(self).toOpaque()
        CFNotificationCenterAddObserver(center, observer, { _, _, _, _, _ in
            Task { @MainActor in VirtualCamera.shared.wanted() }
        }, "\(base).camera.on" as CFString, nil, .deliverImmediately)
        CFNotificationCenterAddObserver(center, observer, { _, _, _, _, _ in
            Task { @MainActor in VirtualCamera.shared.unwanted() }
        }, "\(base).camera.off" as CFString, nil, .deliverImmediately)
    }

    /// The camera of the phone that was used last, or else the first one there is, opened without a window.
    @MainActor
    private func wanted() {
        guard Self.enabled else { return }
        let manager = LiveManager.shared
        if manager.sessions.contains(where: { $0.kind == .camera && $0.isRunning }) { return }
        let model = EngineModel.shared
        let last = UserDefaults.standard.string(forKey: Self.phoneKey)
        let phones = model.devices.filter { $0.canShowCamera && $0.online }
        guard let phone = phones.first(where: { $0.id == last }) ?? phones.first else {
            model.showToast(String(localized: "Open Tandem on your phone to use it as a webcam"))
            return
        }
        opened = manager.start(device: phone, kind: .camera, showWindow: false)
    }

    @MainActor
    private func unwanted() {
        guard let session = opened else { return }
        opened = nil
        LiveManager.shared.stop(session, closingWindow: false)
        LiveManager.shared.windowClosed(session)
    }

    /// A camera window was opened for this phone: that is the phone to use next time.
    @MainActor
    func remember(phone: String) {
        UserDefaults.standard.set(phone, forKey: Self.phoneKey)
    }

    // MARK: Pictures

    /// How the phone says the picture has to be turned to stand upright.
    func setRotation(_ degrees: Int) {
        lock.withLock { self.degrees = ((degrees % 360) + 360) % 360 }
    }

    /// From the decoder's thread. A picture that comes while the last one is still on its way is skipped: late is worse than fewer.
    func push(_ image: CVPixelBuffer) {
        let skip: Bool = lock.withLock {
            if busy { return true }
            busy = true
            return false
        }
        guard !skip else { return }
        queue.async { [self] in
            defer { lock.withLock { busy = false } }
            guard let sink = connectedSink(), let frame = fitted(image) else { return }
            var description: CMFormatDescription?
            CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: frame, formatDescriptionOut: &description)
            guard let description else { return }
            var timing = CMSampleTimingInfo(duration: .invalid, presentationTimeStamp: CMClockGetTime(CMClockGetHostTimeClock()), decodeTimeStamp: .invalid)
            var sample: CMSampleBuffer?
            CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: frame, formatDescription: description, sampleTiming: &timing, sampleBufferOut: &sample)
            guard let sample, CMSimpleQueueGetCount(sink.queue) < CMSimpleQueueGetCapacity(sink.queue) else { return }
            CMSimpleQueueEnqueue(sink.queue, element: UnsafeRawPointer(Unmanaged.passRetained(sample).toOpaque()))
        }
    }

    /// The picture turned upright and fitted into the camera's frame, with dark bars where it does not fill it (a phone held upright).
    private func fitted(_ image: CVPixelBuffer) -> CVPixelBuffer? {
        if pool == nil {
            let attributes: [CFString: Any] = [
                kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA,
                kCVPixelBufferWidthKey: Self.width,
                kCVPixelBufferHeightKey: Self.height,
                kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
            ]
            CVPixelBufferPoolCreate(kCFAllocatorDefault, nil, attributes as CFDictionary, &pool)
        }
        guard let pool else { return nil }
        var out: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(kCFAllocatorDefault, pool, &out)
        guard let out else { return nil }

        var picture = CIImage(cvPixelBuffer: image)
        let degrees = lock.withLock { self.degrees }
        switch degrees {
        case 90: picture = picture.oriented(.right)
        case 180: picture = picture.oriented(.down)
        case 270: picture = picture.oriented(.left)
        default: break
        }
        let extent = picture.extent
        let scale = min(CGFloat(Self.width) / extent.width, CGFloat(Self.height) / extent.height)
        let x = (CGFloat(Self.width) - extent.width * scale) / 2
        let y = (CGFloat(Self.height) - extent.height * scale) / 2
        picture = picture
            .transformed(by: CGAffineTransform(translationX: -extent.minX, y: -extent.minY).scaledBy(x: scale, y: scale))
            .transformed(by: CGAffineTransform(translationX: x, y: y))
        let canvas = CGRect(x: 0, y: 0, width: Self.width, height: Self.height)
        let background = CIImage(color: CIColor(red: 0.11, green: 0.11, blue: 0.11)).cropped(to: canvas)
        context.render(picture.composited(over: background), to: out, bounds: canvas, colorSpace: CGColorSpaceCreateDeviceRGB())
        return out
    }

    /// The sink of Tandem Camera, found and started the first time a picture comes, and looked for again at most every two seconds
    /// while it is not there (the extension not installed yet, or just replaced).
    private func connectedSink() -> (device: CMIODeviceID, stream: CMIOStreamID, queue: CMSimpleQueue)? {
        if let sink { return sink }
        guard Date().timeIntervalSince(lastLook) > 2 else { return nil }
        lastLook = Date()
        guard let device = Self.findDevice(), let stream = Self.streams(of: device).dropFirst().first else { return nil }
        var unmanaged: Unmanaged<CMSimpleQueue>?
        let copied = CMIOStreamCopyBufferQueue(stream, { _, _, _ in }, nil, &unmanaged)
        guard copied == 0, let queue = unmanaged?.takeRetainedValue() else { return nil }
        guard CMIODeviceStartStream(device, stream) == 0 else { return nil }
        sink = (device, stream, queue)
        return sink
    }

    private static func address(_ selector: Int) -> CMIOObjectPropertyAddress {
        CMIOObjectPropertyAddress(
            mSelector: CMIOObjectPropertySelector(selector),
            mScope: CMIOObjectPropertyScope(kCMIOObjectPropertyScopeGlobal),
            mElement: CMIOObjectPropertyElement(kCMIOObjectPropertyElementMain)
        )
    }

    private static func ids(of object: CMIOObjectID, _ selector: Int) -> [CMIOObjectID] {
        var address = address(selector)
        var size: UInt32 = 0
        guard CMIOObjectGetPropertyDataSize(object, &address, 0, nil, &size) == 0, size > 0 else { return [] }
        var ids = [CMIOObjectID](repeating: 0, count: Int(size) / MemoryLayout<CMIOObjectID>.size)
        var used: UInt32 = 0
        guard CMIOObjectGetPropertyData(object, &address, 0, nil, size, &used, &ids) == 0 else { return [] }
        return ids
    }

    private static func findDevice() -> CMIODeviceID? {
        ids(of: CMIOObjectID(kCMIOObjectSystemObject), kCMIOHardwarePropertyDevices).first { device in
            var address = address(kCMIODevicePropertyDeviceUID)
            var uid: Unmanaged<CFString>?
            var used: UInt32 = 0
            let size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
            guard CMIOObjectGetPropertyData(device, &address, 0, nil, size, &used, &uid) == 0 else { return false }
            return uid?.takeRetainedValue() as String? == deviceUID
        }
    }

    /// The source first, then the sink: the order the extension adds them in.
    private static func streams(of device: CMIODeviceID) -> [CMIOStreamID] {
        ids(of: device, kCMIODevicePropertyStreams)
    }
}

extension VirtualCamera: OSSystemExtensionRequestDelegate {
    func request(
        _ request: OSSystemExtensionRequest, actionForReplacingExtension existing: OSSystemExtensionProperties,
        withExtension ext: OSSystemExtensionProperties
    ) -> OSSystemExtensionRequest.ReplacementAction {
        .replace
    }

    func requestNeedsUserApproval(_ request: OSSystemExtensionRequest) {
        Task { @MainActor in
            FloatingToast.show(String(localized: "Allow Tandem Camera in System Settings, under Login Items & Extensions"), symbol: "video")
            if let url = URL(string: "x-apple.systempreferences:com.apple.LoginItems-Settings.extension") { NSWorkspace.shared.open(url) }
        }
    }

    func request(_ request: OSSystemExtensionRequest, didFinishWithResult result: OSSystemExtensionRequest.Result) {
        // A new extension has a new sink: find it again with the next picture.
        queue.async { [self] in
            sink = nil
            lastLook = .distantPast
        }
    }

    func request(_ request: OSSystemExtensionRequest, didFailWithError error: Error) {
        Task { @MainActor in
            EngineModel.shared.showToast(String(localized: "Tandem Camera could not be installed: \(error.localizedDescription)"))
        }
    }
}

/// In Settings, Remote control, above the OBS row: the switch, with an (i) that says what it does. Grey in a build without the extension.
struct WebcamSection: View {
    @AppStorage(VirtualCamera.enabledKey) private var enabled = false
    @LocalState private var explaining = false

    var body: some View {
        Section {
            Toggle(isOn: $enabled) {
                HStack(spacing: 6) {
                    Text("Phone as webcam")
                    Button { explaining.toggle() } label: { Image(systemName: "info.circle").foregroundStyle(.secondary) }
                        .buttonStyle(.borderless)
                        .popover(isPresented: $explaining, arrowEdge: .bottom) {
                            Text("Pick Tandem Camera in Zoom, FaceTime or any other app, and the camera of your phone turns on. The first time, macOS asks you to allow it.")
                                .font(.callout)
                                .frame(width: 280, alignment: .leading)
                                .fixedSize(horizontal: false, vertical: true)
                                .padding(14)
                        }
                }
            }
            .disabled(!VirtualCamera.isAvailable)
            .help(VirtualCamera.isAvailable ? "" : String(localized: "This build of Tandem has no camera extension"))
            .onChange(of: enabled) { _, on in
                if on { VirtualCamera.shared.install() } else { VirtualCamera.shared.uninstall() }
            }
        }
    }
}

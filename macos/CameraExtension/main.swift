// Tandem Camera: the phone as a webcam that every app on the Mac can pick, like the one built in.
//
// A camera extension (CoreMediaIO) with two streams. The source stream is the camera other apps see. The sink stream is where the
// Tandem app puts the pictures of the phone camera in. When an app starts the source, the extension tells Tandem with a Darwin
// notification, and Tandem opens the camera of the phone without a window. Without pictures from the sink the camera shows a dark frame.
import CoreMedia
import CoreMediaIO
import CoreVideo
import Foundation
import IOKit.audio

let width: Int32 = 1920
let height: Int32 = 1080
let fps: Int32 = 30
/// A picture from the sink older than this is not shown any more: the phone went away, the dark frame comes back.
let staleAfter: UInt64 = 1_000_000_000
/// Fixed, so apps that remember the camera they used last find it again after an update.
let deviceUUID = UUID(uuidString: "6C2B9F3E-7A41-4D8E-9B65-1F0C7E4A2D93")!
let sourceUUID = UUID(uuidString: "0E5D8A21-3C6F-4B97-A2E4-8D19F6B3C750")!
let sinkUUID = UUID(uuidString: "B47F1C92-5E8A-4D36-91C3-2A6E0F8D4B17")!

/// The app this extension belongs to: its own id without ".Camera". Its notifications are named after it, so the test build and the
/// real one do not hear each other.
let hostBundle = (Bundle.main.bundleIdentifier ?? "nl.markmaaktmedia.Tandem.Camera").replacingOccurrences(of: ".Camera", with: "")

func notifyHost(_ name: String) {
    let center = CFNotificationCenterGetDarwinNotifyCenter()
    CFNotificationCenterPostNotification(center, CFNotificationName("\(hostBundle).camera.\(name)" as CFString), nil, nil, true)
}

func hostNanoseconds() -> UInt64 {
    CMClockGetTime(CMClockGetHostTimeClock()).convertScale(1_000_000_000, method: .default).value.magnitude
}

final class Camera: NSObject {
    let queue = DispatchQueue(label: "camera")
    var provider: CMIOExtensionProvider!
    var device: CMIOExtensionDevice!
    var source: CMIOExtensionStream!
    var sink: CMIOExtensionStream!
    let format: CMIOExtensionStreamFormat
    let formatDescription: CMVideoFormatDescription
    let dark: CVPixelBuffer

    var sourceRunning = false
    var sinkClient: CMIOExtensionClient?
    var timer: DispatchSourceTimer?
    var latest: (buffer: CVPixelBuffer, at: UInt64)?
    var sequence: UInt64 = 0

    override init() {
        var description: CMFormatDescription?
        CMVideoFormatDescriptionCreate(allocator: kCFAllocatorDefault, codecType: kCVPixelFormatType_32BGRA, width: width, height: height, extensions: nil, formatDescriptionOut: &description)
        formatDescription = description!
        let frame = CMTime(value: 1, timescale: fps)
        format = CMIOExtensionStreamFormat(formatDescription: formatDescription, maxFrameDuration: frame, minFrameDuration: frame, validFrameDurations: nil)

        var buffer: CVPixelBuffer?
        let attributes = [kCVPixelBufferIOSurfacePropertiesKey: [:]] as CFDictionary
        CVPixelBufferCreate(kCFAllocatorDefault, Int(width), Int(height), kCVPixelFormatType_32BGRA, attributes, &buffer)
        dark = buffer!
        CVPixelBufferLockBaseAddress(dark, [])
        // The near black of Tandem's own windows, so the camera in a call looks like it is waiting rather than broken.
        memset(CVPixelBufferGetBaseAddress(dark), 0x1C, CVPixelBufferGetBytesPerRow(dark) * Int(height))
        CVPixelBufferUnlockBaseAddress(dark, [])
        super.init()

        provider = CMIOExtensionProvider(source: self, clientQueue: nil)
        device = CMIOExtensionDevice(localizedName: "Tandem Camera", deviceID: deviceUUID, legacyDeviceID: deviceUUID.uuidString, source: self)
        source = CMIOExtensionStream(localizedName: "Tandem Camera", streamID: sourceUUID, direction: .source, clockType: .hostTime, source: StreamSource(self, sink: false))
        sink = CMIOExtensionStream(localizedName: "Tandem Camera Input", streamID: sinkUUID, direction: .sink, clockType: .hostTime, source: StreamSource(self, sink: true))
        try! device.addStream(source)
        try! device.addStream(sink)
        try! provider.addDevice(device)
    }

    // MARK: The camera other apps see

    func startSource() {
        queue.async { [self] in
            guard !sourceRunning else { return }
            sourceRunning = true
            let timer = DispatchSource.makeTimerSource(queue: queue)
            timer.schedule(deadline: .now(), repeating: .nanoseconds(Int(1_000_000_000 / fps)), leeway: .milliseconds(2))
            timer.setEventHandler { [weak self] in self?.sendFrame() }
            timer.resume()
            self.timer = timer
            notifyHost("on")
        }
    }

    func stopSource() {
        queue.async { [self] in
            guard sourceRunning else { return }
            sourceRunning = false
            timer?.cancel()
            timer = nil
            notifyHost("off")
        }
    }

    private func sendFrame() {
        let now = hostNanoseconds()
        let picture = latest.flatMap { now &- $0.at < staleAfter ? $0.buffer : nil } ?? dark
        var timing = CMSampleTimingInfo(
            duration: CMTime(value: 1, timescale: fps),
            presentationTimeStamp: CMTime(value: CMTimeValue(now), timescale: 1_000_000_000),
            decodeTimeStamp: .invalid
        )
        var description: CMFormatDescription?
        CMVideoFormatDescriptionCreateForImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: picture, formatDescriptionOut: &description)
        guard let description else { return }
        var sample: CMSampleBuffer?
        CMSampleBufferCreateReadyWithImageBuffer(allocator: kCFAllocatorDefault, imageBuffer: picture, formatDescription: description, sampleTiming: &timing, sampleBufferOut: &sample)
        guard let sample else { return }
        source.send(sample, discontinuity: [], hostTimeInNanoseconds: now)
    }

    // MARK: Where Tandem puts the pictures in

    func startSink(for client: CMIOExtensionClient) {
        queue.async { [self] in
            sinkClient = client
            consume()
        }
    }

    func stopSink() {
        queue.async { [self] in
            sinkClient = nil
            latest = nil
        }
    }

    private func consume() {
        guard let client = sinkClient else { return }
        sink.consumeSampleBuffer(from: client) { [weak self] sample, sequence, _, more, _ in
            guard let self else { return }
            self.queue.async {
                if let sample, let image = CMSampleBufferGetImageBuffer(sample) {
                    self.latest = (image, hostNanoseconds())
                    self.sink.notifyScheduledOutputChanged(CMIOExtensionScheduledOutput(sequenceNumber: sequence, hostTimeInNanoseconds: hostNanoseconds()))
                }
                guard self.sinkClient != nil else { return }
                // Straight on while there is more; otherwise a moment later, so an empty queue is not asked in a tight loop.
                if more { self.consume() } else { self.queue.asyncAfter(deadline: .now() + .milliseconds(4)) { self.consume() } }
            }
        }
    }
}

extension Camera: CMIOExtensionProviderSource {
    func connect(to client: CMIOExtensionClient) throws {}
    func disconnect(from client: CMIOExtensionClient) {}
    var availableProperties: Set<CMIOExtensionProperty> { [.providerManufacturer] }

    func providerProperties(forProperties properties: Set<CMIOExtensionProperty>) throws -> CMIOExtensionProviderProperties {
        let result = CMIOExtensionProviderProperties(dictionary: [:])
        if properties.contains(.providerManufacturer) { result.manufacturer = "Tandem" }
        return result
    }

    func setProviderProperties(_ providerProperties: CMIOExtensionProviderProperties) throws {}
}

extension Camera: CMIOExtensionDeviceSource {
    func deviceProperties(forProperties properties: Set<CMIOExtensionProperty>) throws -> CMIOExtensionDeviceProperties {
        let result = CMIOExtensionDeviceProperties(dictionary: [:])
        if properties.contains(.deviceTransportType) { result.transportType = Int(kIOAudioDeviceTransportTypeVirtual) }
        if properties.contains(.deviceModel) { result.model = "Tandem Camera" }
        return result
    }

    func setDeviceProperties(_ deviceProperties: CMIOExtensionDeviceProperties) throws {}
}

/// One of the two streams. The device answers for both; this only says which one it is.
final class StreamSource: NSObject, CMIOExtensionStreamSource {
    private unowned let camera: Camera
    private let isSink: Bool

    init(_ camera: Camera, sink: Bool) {
        self.camera = camera
        self.isSink = sink
    }

    var formats: [CMIOExtensionStreamFormat] { [camera.format] }

    var availableProperties: Set<CMIOExtensionProperty> {
        var properties: Set<CMIOExtensionProperty> = [.streamActiveFormatIndex, .streamFrameDuration]
        if isSink { properties.formUnion([.streamSinkBufferQueueSize, .streamSinkBuffersRequiredForStartup, .streamSinkBufferUnderrunCount, .streamSinkEndOfData]) }
        return properties
    }

    func streamProperties(forProperties properties: Set<CMIOExtensionProperty>) throws -> CMIOExtensionStreamProperties {
        let result = CMIOExtensionStreamProperties(dictionary: [:])
        if properties.contains(.streamActiveFormatIndex) { result.activeFormatIndex = 0 }
        if properties.contains(.streamFrameDuration) { result.frameDuration = CMTime(value: 1, timescale: fps) }
        if properties.contains(.streamSinkBufferQueueSize) { result.sinkBufferQueueSize = 1 }
        if properties.contains(.streamSinkBuffersRequiredForStartup) { result.sinkBuffersRequiredForStartup = 1 }
        return result
    }

    func setStreamProperties(_ streamProperties: CMIOExtensionStreamProperties) throws {}

    private var sinkClient: CMIOExtensionClient?

    func authorizedToStartStream(for client: CMIOExtensionClient) -> Bool {
        if isSink { sinkClient = client }
        return true
    }

    func startStream() throws {
        if isSink {
            if let sinkClient { camera.startSink(for: sinkClient) }
        } else {
            camera.startSource()
        }
    }

    func stopStream() throws {
        if isSink { camera.stopSink() } else { camera.stopSource() }
    }
}

let camera = Camera()
CMIOExtensionProvider.startService(provider: camera.provider)
CFRunLoopRun()

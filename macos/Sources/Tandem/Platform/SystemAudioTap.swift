import AudioToolbox
import CoreAudio
import Foundation

/// Captures everything this Mac plays, as 16 bit signed samples, so it can be played somewhere else.
///
/// It uses a Core Audio process tap (macOS 14.2 and later): a tap on all processes, wrapped in a
/// private aggregate device that only this app can see. With `muteLocal` the tap also silences the
/// speakers while it runs, so the phone is the speaker instead of an echo of it. The tap is gone when
/// this app is, and then the sound comes back by itself.
///
/// The first start asks for "System Audio Recording" permission. Without it the tap still runs but
/// delivers silence, and macOS gives no way to ask whether the permission is there.
final class SystemAudioTap: @unchecked Sendable {
    struct Format: Equatable {
        var sampleRate: UInt32
        var channels: UInt8
    }

    enum Failure: Error, LocalizedError {
        case notSupported
        case step(String, OSStatus)

        var errorDescription: String? {
            switch self {
            case .notSupported: String(localized: "This needs macOS 14.2 or later")
            case let .step(what, status): String(localized: "Could not capture the sound (\(what), error \(status))")
            }
        }
    }

    private var tapID = AudioObjectID(kAudioObjectUnknown)
    private var aggregateID = AudioObjectID(kAudioObjectUnknown)
    private var procID: AudioDeviceIOProcID?
    private let queue = DispatchQueue(label: "tandem.audio.capture", qos: .userInteractive)
    private var source = AudioStreamBasicDescription()

    /// The last time the sound callback ran, for noticing that the output device went away.
    private let lastBeat = NSLock()
    private var beatAt = Date.distantPast
    var lastDelivery: Date { lastBeat.lock(); defer { lastBeat.unlock() }; return beatAt }

    /// Opens the tap and says what it will deliver. Nothing flows until [start].
    func prepare(muteLocal: Bool) throws -> Format {
        let description = CATapDescription(stereoGlobalTapButExcludeProcesses: [])
        description.name = "Tandem"
        description.isPrivate = true
        description.muteBehavior = muteLocal ? .muted : .unmuted

        var status = AudioHardwareCreateProcessTap(description, &tapID)
        guard status == noErr else { throw Failure.step("tap", status) }

        let outputUID = try Self.defaultOutputUID()
        let aggregate: [String: Any] = [
            kAudioAggregateDeviceNameKey: "Tandem speaker tap",
            kAudioAggregateDeviceUIDKey: UUID().uuidString,
            kAudioAggregateDeviceMainSubDeviceKey: outputUID,
            kAudioAggregateDeviceIsPrivateKey: true,
            kAudioAggregateDeviceIsStackedKey: false,
            kAudioAggregateDeviceTapAutoStartKey: true,
            kAudioAggregateDeviceSubDeviceListKey: [[kAudioSubDeviceUIDKey: outputUID]],
            kAudioAggregateDeviceTapListKey: [[
                kAudioSubTapDriftCompensationKey: true,
                kAudioSubTapUIDKey: description.uuid.uuidString,
            ]],
        ]
        status = AudioHardwareCreateAggregateDevice(aggregate as CFDictionary, &aggregateID)
        guard status == noErr else {
            stop()
            throw Failure.step("device", status)
        }

        var address = AudioObjectPropertyAddress(
            mSelector: kAudioTapPropertyFormat, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain
        )
        var size = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
        status = AudioObjectGetPropertyData(tapID, &address, 0, nil, &size, &source)
        guard status == noErr, source.mSampleRate > 0, source.mChannelsPerFrame > 0 else {
            stop()
            throw Failure.step("format", status)
        }
        return Format(sampleRate: UInt32(source.mSampleRate), channels: UInt8(min(2, source.mChannelsPerFrame)))
    }

    /// Starts delivering. [handler] gets 16 bit interleaved samples, a whole number of frames, on the
    /// capture queue: it must return quickly. A stretch of silence longer than a second is not delivered,
    /// so a Mac that plays nothing sends nothing.
    func start(handler: @escaping @Sendable (Data) -> Void) throws {
        let channels = Int(min(2, source.mChannelsPerFrame))
        let planar = source.mFormatFlags & kAudioFormatFlagIsNonInterleaved != 0
        let isFloat = source.mFormatFlags & kAudioFormatFlagIsFloat != 0
        let bits = Int(source.mBitsPerChannel)
        var silentFor = 0
        let holdFrames = Int(source.mSampleRate)

        let status = AudioDeviceCreateIOProcIDWithBlock(&procID, aggregateID, queue) { [weak self] _, input, _, _, _ in
            guard let self else { return }
            self.lastBeat.lock(); self.beatAt = Date(); self.lastBeat.unlock()
            let pcm = SystemAudioTap.convert(input, channels: channels, planar: planar, isFloat: isFloat, bits: bits)
            guard !pcm.isEmpty else { return }
            let frames = pcm.count / (channels * 2)
            if SystemAudioTap.isSilent(pcm) {
                silentFor += frames
                if silentFor > holdFrames { return }
            } else {
                silentFor = 0
            }
            handler(pcm)
        }
        guard status == noErr, procID != nil else { throw Failure.step("callback", status) }
        let started = AudioDeviceStart(aggregateID, procID)
        guard started == noErr else { throw Failure.step("start", started) }
        lastBeat.lock(); beatAt = Date(); lastBeat.unlock()
    }

    func stop() {
        if aggregateID != kAudioObjectUnknown, let procID {
            AudioDeviceStop(aggregateID, procID)
            AudioDeviceDestroyIOProcID(aggregateID, procID)
        }
        procID = nil
        if aggregateID != kAudioObjectUnknown { AudioHardwareDestroyAggregateDevice(aggregateID) }
        aggregateID = AudioObjectID(kAudioObjectUnknown)
        if tapID != kAudioObjectUnknown { AudioHardwareDestroyProcessTap(tapID) }
        tapID = AudioObjectID(kAudioObjectUnknown)
    }

    deinit { stop() }

    // MARK: Pieces

    private static func defaultOutputUID() throws -> String {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultSystemOutputDevice,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var device = AudioObjectID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        var status = AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &device)
        guard status == noErr, device != kAudioObjectUnknown else { throw Failure.step("output", status) }

        address.mSelector = kAudioDevicePropertyDeviceUID
        var uid: Unmanaged<CFString>?
        size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        status = AudioObjectGetPropertyData(device, &address, 0, nil, &size, &uid)
        guard status == noErr, let value = uid?.takeRetainedValue() else { throw Failure.step("output name", status) }
        return value as String
    }

    /// Turns what the tap hands over (float or integer, interleaved or one buffer per channel) into
    /// 16 bit interleaved samples. Kept apart from the capture so it can be tried without a Mac that plays.
    static func convert(
        _ list: UnsafePointer<AudioBufferList>, channels: Int, planar: Bool, isFloat: Bool, bits: Int
    ) -> Data {
        let buffers = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: list))
        guard buffers.count > 0, channels > 0 else { return Data() }
        let sourceChannels = planar ? 1 : max(1, Int(buffers[0].mNumberChannels))
        let bytesPerSample = max(1, bits / 8)
        guard let first = buffers[0].mData else { return Data() }
        _ = first
        let frames = Int(buffers[0].mDataByteSize) / (bytesPerSample * sourceChannels)
        guard frames > 0 else { return Data() }

        var out = Data(count: frames * channels * 2)
        out.withUnsafeMutableBytes { raw in
            let dst = raw.bindMemory(to: Int16.self)
            for frame in 0..<frames {
                for channel in 0..<channels {
                    let buffer = planar ? (channel < buffers.count ? buffers[channel] : buffers[0]) : buffers[0]
                    let index = planar ? frame : frame * sourceChannels + min(channel, sourceChannels - 1)
                    guard let data = buffer.mData else { continue }
                    dst[frame * channels + channel] = sample(data, index: index, isFloat: isFloat, bytes: bytesPerSample)
                }
            }
        }
        return out
    }

    private static func sample(_ data: UnsafeMutableRawPointer, index: Int, isFloat: Bool, bytes: Int) -> Int16 {
        if isFloat && bytes == 4 {
            let value = data.assumingMemoryBound(to: Float.self)[index]
            return Int16(max(-1, min(1, value)) * 32767)
        }
        switch bytes {
        case 2: return data.assumingMemoryBound(to: Int16.self)[index]
        case 4: return Int16(truncatingIfNeeded: data.assumingMemoryBound(to: Int32.self)[index] >> 16)
        default: return 0
        }
    }

    static func isSilent(_ pcm: Data) -> Bool {
        pcm.withUnsafeBytes { raw in
            let samples = raw.bindMemory(to: Int16.self)
            // A few counts of noise is still silence: some outputs never quite reach zero.
            for value in samples where value > 8 || value < -8 { return false }
            return true
        }
    }
}

import AppKit
import CoreGraphics
import Foundation
import TandemVirtualDisplay

/// A display that exists only while this object does: another device is using it as a second screen. The system treats it as a monitor
/// (windows can be moved to it, the pointer can go there, it has its own menu bar), and the capture of it is what the other device sees.
final class VirtualDisplay {
    let displayID: CGDirectDisplayID
    /// The size in pixels, which is what the picture that goes over is.
    let pixels: CGSize
    private let hiDPI: Bool
    private var handle: UnsafeMutableRawPointer?

    static var isAvailable: Bool { TandemVirtualDisplayAvailable() != 0 }

    /// Adds the display. `nil` when the system would not make one. Call `prepare()` before using it.
    init?(name: String, width: Int, height: Int, hiDPI: Bool, refresh: Int) {
        var id: CGDirectDisplayID = 0
        // The same serial for the same name, so the system recognises the display and puts it where it was.
        let serial = UInt32(truncatingIfNeeded: abs(name.hashValue) % 1_000_000) + 1
        guard let made = TandemVirtualDisplayCreate(name, Int32(width), Int32(height), hiDPI ? 1 : 0, Int32(refresh), serial, &id), id != 0 else { return nil }
        handle = made
        displayID = id
        pixels = CGSize(width: width, height: height)
        self.hiDPI = hiDPI
    }

    deinit { destroy() }

    func destroy() {
        guard let handle else { return }
        self.handle = nil
        TandemVirtualDisplayDestroy(handle)
    }

    /// Where it is now, in the coordinates events are posted in.
    var bounds: CGRect { CGDisplayBounds(displayID) }

    /// The system needs a moment before the display is one of its monitors, and picks a mode of its own, which may be smaller than the one
    /// asked for. This waits until the display is there, chooses the mode of the right size by hand and puts the display to the right of the
    /// main one. False when it did not come up as it was asked.
    @discardableResult
    func prepare() async -> Bool {
        let width = Int(pixels.width), height = Int(pixels.height)
        for _ in 0..<20 {
            if isActive, selectMode(width: width, height: height) {
                placeRightOfMain()
                return true
            }
            try? await Task.sleep(for: .milliseconds(150))
        }
        return false
    }

    private var isActive: Bool {
        var ids = [CGDirectDisplayID](repeating: 0, count: 16)
        var count: UInt32 = 0
        CGGetActiveDisplayList(16, &ids, &count)
        return ids.prefix(Int(count)).contains(displayID)
    }

    /// True when the display shows the size now.
    private func selectMode(width: Int, height: Int) -> Bool {
        if let current = CGDisplayCopyDisplayMode(displayID), current.pixelWidth == width, current.pixelHeight == height { return true }
        let options = [kCGDisplayShowDuplicateLowResolutionModes: true] as CFDictionary
        guard let modes = CGDisplayCopyAllDisplayModes(displayID, options) as? [CGDisplayMode] else { return false }
        let points = hiDPI ? (width / 2, height / 2) : (width, height)
        guard let mode = modes.first(where: { $0.pixelWidth == width && $0.pixelHeight == height && $0.width == points.0 && $0.height == points.1 })
            ?? modes.first(where: { $0.pixelWidth == width && $0.pixelHeight == height }) else { return false }
        var config: CGDisplayConfigRef?
        guard CGBeginDisplayConfiguration(&config) == .success, let config else { return false }
        CGConfigureDisplayWithDisplayMode(config, displayID, mode, nil)
        CGCompleteDisplayConfiguration(config, .forSession)
        if let now = CGDisplayCopyDisplayMode(displayID) { return now.pixelWidth == width && now.pixelHeight == height }
        return false
    }

    /// Where the other screens are is where the person put them; a new one goes to the right of the main one.
    private func placeRightOfMain() {
        let main = CGDisplayBounds(CGMainDisplayID())
        var config: CGDisplayConfigRef?
        guard CGBeginDisplayConfiguration(&config) == .success, let config else { return }
        CGConfigureDisplayOrigin(config, displayID, Int32(main.maxX), 0)
        CGCompleteDisplayConfiguration(config, .forSession)
    }

    /// Every mode the system lists for it, for the report of the debug check.
    var modeList: String {
        let options = [kCGDisplayShowDuplicateLowResolutionModes: true] as CFDictionary
        let modes = (CGDisplayCopyAllDisplayModes(displayID, options) as? [CGDisplayMode]) ?? []
        return modes.map { "\($0.pixelWidth)x\($0.pixelHeight) (\($0.width)x\($0.height) pt)" }.joined(separator: ", ")
    }
}

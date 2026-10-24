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

    /// Adds the display. `nil` when the system would not make one. Call `prepare()` before using it. `key` is what tells the displays of
    /// two devices apart (the device id), `name` is what the system shows.
    init?(name: String, key: String? = nil, width: Int, height: Int, hiDPI: Bool, refresh: Int) {
        var id: CGDirectDisplayID = 0
        // The same serial for the same device, so the system recognises the display, and two devices that are extra screens at once are two
        // monitors to it even when they have the same name. Not `hashValue`, which is different every launch.
        let serial = Self.serial(for: key ?? name)
        guard let made = TandemVirtualDisplayCreate(name, Int32(width), Int32(height), hiDPI ? 1 : 0, Int32(refresh), serial, &id), id != 0 else { return nil }
        handle = made
        displayID = id
        pixels = CGSize(width: width, height: height)
        self.hiDPI = hiDPI
    }

    deinit { destroy() }

    private static func serial(for key: String) -> UInt32 {
        var hash: UInt32 = 2_166_136_261
        for byte in key.utf8 { hash = (hash ^ UInt32(byte)) &* 16_777_619 }
        return hash % 1_000_000 + 1
    }

    func destroy() {
        guard let handle else { return }
        self.handle = nil
        TandemVirtualDisplayDestroy(handle)
    }

    /// Where it is now, in the coordinates events are posted in.
    var bounds: CGRect { CGDisplayBounds(displayID) }

    /// The system needs a moment before the display is one of its monitors, and picks a mode of its own, which may be smaller than the one
    /// asked for. This waits until the display is there and chooses the mode of the right size by hand; `arrange` puts it in its place.
    /// False when it did not come up as it was asked.
    @discardableResult
    func prepare() async -> Bool {
        let width = Int(pixels.width), height = Int(pixels.height)
        for _ in 0..<20 {
            if isActive, selectMode(width: width, height: height) { return true }
            try? await Task.sleep(for: .milliseconds(150))
        }
        return false
    }

    private static func activeDisplays() -> [CGDirectDisplayID] {
        var ids = [CGDirectDisplayID](repeating: 0, count: 16)
        var count: UInt32 = 0
        CGGetActiveDisplayList(16, &ids, &count)
        return Array(ids.prefix(Int(count)))
    }

    private var isActive: Bool { Self.activeDisplays().contains(displayID) }

    /// Made by Tandem, by the vendor number `TandemVirtualDisplay.m` gives every display it makes, so one that is still going away is not
    /// taken for a monitor that is really there.
    private static func isTandem(_ id: CGDirectDisplayID) -> Bool { CGDisplayVendorNumber(id) == 0x7A4E }

    /// True when the display shows the size now: the pixels and the points, since a hiDPI display that the system put in the mode of the
    /// same pixels at one point each draws everything at half the size the viewer's screen does.
    private func selectMode(width: Int, height: Int) -> Bool {
        let points = hiDPI ? (width / 2, height / 2) : (width, height)
        func right(_ mode: CGDisplayMode) -> Bool {
            mode.pixelWidth == width && mode.pixelHeight == height && mode.width == points.0 && mode.height == points.1
        }
        if let current = CGDisplayCopyDisplayMode(displayID), right(current) { return true }
        let options = [kCGDisplayShowDuplicateLowResolutionModes: true] as CFDictionary
        guard let modes = CGDisplayCopyAllDisplayModes(displayID, options) as? [CGDisplayMode] else { return false }
        let wanted = modes.first(where: right)
        guard let mode = wanted ?? modes.first(where: { $0.pixelWidth == width && $0.pixelHeight == height }) else { return false }
        var config: CGDisplayConfigRef?
        guard CGBeginDisplayConfiguration(&config) == .success, let config else { return false }
        CGConfigureDisplayWithDisplayMode(config, displayID, mode, nil)
        CGCompleteDisplayConfiguration(config, .forSession)
        guard let now = CGDisplayCopyDisplayMode(displayID) else { return false }
        return wanted != nil ? right(now) : now.pixelWidth == width && now.pixelHeight == height
    }

    /// On which side it goes: the side the device stands on, when that is known (see `DeviceSides`), else the right. Applied by `arrange`.
    var onLeft = false

    /// Puts these displays beside the monitors that are really there, top edges in line with the main one: each on its own side, the first
    /// one of a side closest and the next ones further out. Two on the same side would otherwise be put on top of each other, and the
    /// system moves one of them wherever it likes. Where the real monitors are is where the person put them.
    static func arrange(_ displays: [VirtualDisplay]) {
        let active = activeDisplays()
        let placed = displays.filter { active.contains($0.displayID) }
        guard !placed.isEmpty else { return }
        let main = CGDisplayBounds(CGMainDisplayID())
        // Only the monitors in the row of the main one: past one that stands above it, the extra screen would touch nothing.
        let real = active.filter { !isTandem($0) }.map { CGDisplayBounds($0) }.filter { $0.maxY > main.minY && $0.minY < main.maxY }
        var right = real.map(\.maxX).max() ?? main.maxX
        var left = real.map(\.minX).min() ?? main.minX
        var config: CGDisplayConfigRef?
        guard CGBeginDisplayConfiguration(&config) == .success, let config else { return }
        for display in placed {
            let width = CGDisplayBounds(display.displayID).width
            let x: CGFloat
            if display.onLeft {
                left -= width
                x = left
            } else {
                x = right
                right += width
            }
            CGConfigureDisplayOrigin(config, display.displayID, Int32(x), Int32(main.minY))
        }
        CGCompleteDisplayConfiguration(config, .forSession)
    }

    /// Every mode the system lists for it, for the report of the debug check.
    var modeList: String {
        let options = [kCGDisplayShowDuplicateLowResolutionModes: true] as CFDictionary
        let modes = (CGDisplayCopyAllDisplayModes(displayID, options) as? [CGDisplayMode]) ?? []
        return modes.map { "\($0.pixelWidth)x\($0.pixelHeight) (\($0.width)x\($0.height) pt)" }.joined(separator: ", ")
    }
}

import Foundation

/// USB HID usages on the Keyboard page (what the wire carries) to the virtual key codes of macOS (what a key event
/// needs). The phone sends positions of keys, not letters, so the Mac's own layout decides what they type.
enum HidKeys {
    /// Nil for a usage this Mac has no key for. Modifier keys are nil on purpose: they travel as flags on the keys.
    static func macKeyCode(forUsage usage: UInt32) -> UInt16? {
        table[usage]
    }

    static func isModifier(_ usage: UInt32) -> Bool { (0xE0 ... 0xE7).contains(usage) }

    private static let table: [UInt32: UInt16] = {
        var map: [UInt32: UInt16] = [:]
        let letters: [UInt16] = [
            0, 11, 8, 2, 14, 3, 5, 4, 34, 38, 40, 37, 46, 45, 31, 35, 12, 15, 1, 17, 32, 9, 13, 7, 16, 6,
        ]
        for (index, code) in letters.enumerated() { map[0x04 + UInt32(index)] = code }
        let digits: [UInt16] = [18, 19, 20, 21, 23, 22, 26, 28, 25, 29]
        for (index, code) in digits.enumerated() { map[0x1E + UInt32(index)] = code }
        let rest: [UInt32: UInt16] = [
            0x28: 36, 0x29: 53, 0x2A: 51, 0x2B: 48, 0x2C: 49, 0x2D: 27, 0x2E: 24, 0x2F: 33, 0x30: 30, 0x31: 42,
            0x32: 42, 0x33: 41, 0x34: 39, 0x35: 50, 0x36: 43, 0x37: 47, 0x38: 44, 0x39: 57,
            0x3A: 122, 0x3B: 120, 0x3C: 99, 0x3D: 118, 0x3E: 96, 0x3F: 97, 0x40: 98, 0x41: 100, 0x42: 101,
            0x43: 109, 0x44: 103, 0x45: 111,
            0x49: 114, 0x4A: 115, 0x4B: 116, 0x4C: 117, 0x4D: 119, 0x4E: 121, 0x4F: 124, 0x50: 123, 0x51: 125, 0x52: 126,
            0x53: 71, 0x54: 75, 0x55: 67, 0x56: 78, 0x57: 69, 0x58: 76,
            0x59: 83, 0x5A: 84, 0x5B: 85, 0x5C: 86, 0x5D: 87, 0x5E: 88, 0x5F: 89, 0x60: 91, 0x61: 92, 0x62: 82,
            0x63: 65, 0x64: 10, 0x65: 110, 0x67: 81,
            0x68: 105, 0x69: 107, 0x6A: 113, 0x6B: 106, 0x6C: 64, 0x6D: 79, 0x6E: 80, 0x6F: 90,
        ]
        map.merge(rest) { $1 }
        return map
    }()
}

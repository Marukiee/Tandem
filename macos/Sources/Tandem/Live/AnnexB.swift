import Foundation

/// H.264 in Annex B form (start codes), which is what the core hands over, taken apart far enough for Video Toolbox:
/// it wants the parameter sets on their own and the pictures with a length in front of each NAL unit instead of a start
/// code. Pure functions with no framework in them, so the rules can be tested on bytes.
enum AnnexB {
    enum NalType {
        static let slice = 1
        static let idr = 5
        static let sei = 6
        static let sps = 7
        static let pps = 8
        static let aud = 9
    }

    /// The NAL units of an access unit, each without its start code. Both the three and the four byte start code count.
    /// A unit ends where the next start code begins, and trailing zero bytes belong to the next start code.
    static func units(in data: Data) -> [Data] {
        let bytes = [UInt8](data)
        var starts: [(code: Int, payload: Int)] = []
        var i = 0
        while i + 2 < bytes.count {
            if bytes[i + 2] > 1 {
                i += 3
            } else if bytes[i] == 0, bytes[i + 1] == 0, bytes[i + 2] == 1 {
                starts.append((i, i + 3))
                i += 3
            } else {
                i += 1
            }
        }
        var result: [Data] = []
        for (index, start) in starts.enumerated() {
            var end = index + 1 < starts.count ? starts[index + 1].code : bytes.count
            while end > start.payload, bytes[end - 1] == 0 { end -= 1 }
            if end > start.payload { result.append(Data(bytes[start.payload..<end])) }
        }
        return result
    }

    static func type(of unit: Data) -> Int {
        guard let first = unit.first else { return 0 }
        return Int(first & 0x1F)
    }

    /// What one access unit holds, as far as decoding is concerned.
    struct Access: Equatable {
        var sps: Data?
        var pps: Data?
        /// Slices of the picture (type 1 and 5), the only NAL units that go to the decoder.
        var pictures: [Data] = []
        var isKeyframe: Bool { pictures.contains { AnnexB.type(of: $0) == NalType.idr } }
    }

    static func parse(_ data: Data) -> Access {
        var access = Access()
        for unit in units(in: data) {
            switch type(of: unit) {
            case NalType.sps: access.sps = unit
            case NalType.pps: access.pps = unit
            case NalType.slice, NalType.idr: access.pictures.append(unit)
            default: break
            }
        }
        return access
    }

    /// The pictures with a four byte big-endian length in front of each, the form `CMSampleBuffer` wants.
    static func lengthPrefixed(_ units: [Data]) -> Data {
        var out = Data(capacity: units.reduce(0) { $0 + $1.count + 4 })
        for unit in units {
            var length = UInt32(unit.count).bigEndian
            withUnsafeBytes(of: &length) { out.append(contentsOf: $0) }
            out.append(unit)
        }
        return out
    }

    /// Width and height from a sequence parameter set, after the emulation prevention bytes are taken out. Used to
    /// check what a stream really holds against what the phone said it would send.
    static func dimensions(ofSPS sps: Data) -> (width: Int, height: Int)? {
        var reader = BitReader(unescaped(sps))
        reader.skip(8)  // NAL header
        let profile = reader.bits(8)
        reader.skip(16)  // constraint flags and level
        _ = reader.ue()  // sps id
        var chroma = 1
        if [100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135].contains(profile) {
            chroma = reader.ue()
            if chroma == 3 { reader.skip(1) }
            _ = reader.ue()
            _ = reader.ue()
            reader.skip(1)
            if reader.bits(1) == 1 {
                for index in 0..<(chroma != 3 ? 8 : 12) where reader.bits(1) == 1 {
                    skipScalingList(&reader, size: index < 6 ? 16 : 64)
                }
            }
        }
        _ = reader.ue()  // log2_max_frame_num_minus4
        let pocType = reader.ue()
        if pocType == 0 {
            _ = reader.ue()
        } else if pocType == 1 {
            reader.skip(1)
            _ = reader.se()
            _ = reader.se()
            let count = reader.ue()
            for _ in 0..<min(count, 255) { _ = reader.se() }
        }
        _ = reader.ue()  // max_num_ref_frames
        reader.skip(1)
        let widthMbs = reader.ue() + 1
        let heightMapUnits = reader.ue() + 1
        let frameMbsOnly = reader.bits(1)
        if frameMbsOnly == 0 { reader.skip(1) }
        reader.skip(1)  // direct_8x8_inference
        var cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0
        if reader.bits(1) == 1 {
            cropLeft = reader.ue()
            cropRight = reader.ue()
            cropTop = reader.ue()
            cropBottom = reader.ue()
        }
        guard !reader.overran else { return nil }
        let cropUnitX = chroma == 0 ? 1 : 2
        let cropUnitY = (chroma == 0 ? 1 : 2) * (2 - frameMbsOnly)
        let width = widthMbs * 16 - (cropLeft + cropRight) * cropUnitX
        let height = (2 - frameMbsOnly) * heightMapUnits * 16 - (cropTop + cropBottom) * cropUnitY
        return width > 0 && height > 0 ? (width, height) : nil
    }

    private static func skipScalingList(_ reader: inout BitReader, size: Int) {
        var last = 8, next = 8
        for _ in 0..<size {
            if next != 0 {
                next = (last + reader.se() + 256) % 256
            }
            last = next == 0 ? last : next
        }
    }

    /// Removes the 0x03 that follows two zero bytes inside a NAL unit (emulation prevention).
    static func unescaped(_ unit: Data) -> [UInt8] {
        var out: [UInt8] = []
        out.reserveCapacity(unit.count)
        var zeros = 0
        for byte in unit {
            if zeros >= 2, byte == 3 {
                zeros = 0
                continue
            }
            out.append(byte)
            zeros = byte == 0 ? zeros + 1 : 0
        }
        return out
    }
}

/// Reads bits and Exp-Golomb numbers. Running past the end does not crash, it sets `overran` and reads zeros.
struct BitReader {
    private let bytes: [UInt8]
    private var position = 0
    private(set) var overran = false

    init(_ bytes: [UInt8]) { self.bytes = bytes }

    mutating func skip(_ count: Int) { position += count }

    mutating func bits(_ count: Int) -> Int {
        var value = 0
        for _ in 0..<count {
            let byte = position / 8
            guard byte < bytes.count else {
                overran = true
                position += 1
                value <<= 1
                continue
            }
            value = (value << 1) | Int((bytes[byte] >> UInt8(7 - position % 8)) & 1)
            position += 1
        }
        return value
    }

    mutating func ue() -> Int {
        var zeros = 0
        while bits(1) == 0 {
            zeros += 1
            if zeros > 31 || overran { overran = true; return 0 }
        }
        return (1 << zeros) - 1 + bits(zeros)
    }

    mutating func se() -> Int {
        let k = ue()
        return k % 2 == 1 ? (k + 1) / 2 : -(k / 2)
    }
}

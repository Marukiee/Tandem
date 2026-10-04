import Foundation

/// The H.264 plumbing between VideoToolbox, which speaks length prefixed NAL units, and the wire, which is Annex B
/// (start codes). Everything here is plain bytes, so the debug harness can check it without an encoder.
enum H264 {
    static let startCode: [UInt8] = [0, 0, 0, 1]

    enum NAL {
        static let slice = 1
        static let idr = 5
        static let sei = 6
        static let sps = 7
        static let pps = 8
        static let aud = 9
    }

    static func type(of nal: ArraySlice<UInt8>) -> Int {
        guard let first = nal.first else { return -1 }
        return Int(first & 0x1F)
    }

    /// One access unit as VideoToolbox hands it over (every NAL unit behind a big-endian length of `lengthSize` bytes)
    /// turned into Annex B. `leading` are parameter sets to put in front; a keyframe needs them to be decodable alone.
    /// Nil when the lengths do not add up, which means the buffer is damaged and must not be sent.
    static func annexB(lengthPrefixed bytes: UnsafeRawBufferPointer, lengthSize: Int, leading: [[UInt8]] = []) -> [UInt8]? {
        guard (1 ... 4).contains(lengthSize) else { return nil }
        var out = [UInt8]()
        out.reserveCapacity(bytes.count + 32 + leading.reduce(0) { $0 + $1.count + 4 })
        for set in leading {
            out += startCode
            out += set
        }
        var offset = 0
        while offset < bytes.count {
            guard offset + lengthSize <= bytes.count else { return nil }
            var length = 0
            for index in 0 ..< lengthSize { length = (length << 8) | Int(bytes[offset + index]) }
            offset += lengthSize
            guard length > 0, offset + length <= bytes.count else { return nil }
            out += startCode
            out += bytes[offset ..< offset + length]
            offset += length
        }
        return out
    }

    /// Where each NAL unit of an Annex B buffer is, start codes left out.
    static func nalRanges(_ bytes: [UInt8]) -> [Range<Int>] {
        var starts: [(code: Int, payload: Int)] = []
        var index = 0
        while index + 3 <= bytes.count {
            if bytes[index] == 0, bytes[index + 1] == 0 {
                if bytes[index + 2] == 1 {
                    starts.append((index, index + 3))
                    index += 3
                    continue
                }
                if index + 4 <= bytes.count, bytes[index + 2] == 0, bytes[index + 3] == 1 {
                    starts.append((index, index + 4))
                    index += 4
                    continue
                }
            }
            index += 1
        }
        return starts.enumerated().compactMap { position, start in
            let end = position + 1 < starts.count ? starts[position + 1].code : bytes.count
            return start.payload < end ? start.payload ..< end : nil
        }
    }

    static func isKeyframe(_ accessUnit: [UInt8]) -> Bool {
        nalRanges(accessUnit).contains { type(of: accessUnit[$0]) == NAL.idr }
    }

    /// The first SPS and PPS in an Annex B buffer, without start codes.
    static func parameterSets(in accessUnit: [UInt8]) -> (sps: [UInt8], pps: [UInt8])? {
        var sps: [UInt8]?
        var pps: [UInt8]?
        for range in nalRanges(accessUnit) {
            switch type(of: accessUnit[range]) {
            case NAL.sps where sps == nil: sps = Array(accessUnit[range])
            case NAL.pps where pps == nil: pps = Array(accessUnit[range])
            default: break
            }
        }
        guard let sps, let pps else { return nil }
        return (sps, pps)
    }

    /// An Annex B stream cut into access units. A picture starts at a slice that begins at the first macroblock, and
    /// parameter sets and SEI that follow a picture belong to the next one. The encoder here writes one slice per
    /// picture, which is the case this has to be right for.
    static func accessUnits(inStream bytes: [UInt8]) -> [[UInt8]] {
        var units: [[UInt8]] = []
        var current: [UInt8] = []
        var hasPicture = false
        func flush() {
            if !current.isEmpty { units.append(current) }
            current = []
            hasPicture = false
        }
        for range in nalRanges(bytes) {
            let nal = bytes[range]
            let kind = type(of: nal)
            let isSlice = kind == NAL.slice || kind == NAL.idr
            let startsPicture = isSlice && nal.count > 1 && (nal[nal.startIndex + 1] & 0x80) != 0
            if kind == NAL.aud || (hasPicture && (startsPicture || kind == NAL.sps || kind == NAL.pps || kind == NAL.sei)) {
                flush()
            }
            current += startCode
            current += nal
            if isSlice { hasPicture = true }
        }
        flush()
        return units
    }

    /// Annex B back to length prefixed (four byte lengths), which is what a decoder session wants.
    static func lengthPrefixed(fromAnnexB bytes: [UInt8], dropping dropped: Set<Int> = [NAL.sps, NAL.pps, NAL.aud]) -> [UInt8] {
        var out = [UInt8]()
        out.reserveCapacity(bytes.count)
        for range in nalRanges(bytes) where !dropped.contains(type(of: bytes[range])) {
            let length = range.count
            out += [UInt8(truncatingIfNeeded: length >> 24), UInt8(truncatingIfNeeded: length >> 16),
                    UInt8(truncatingIfNeeded: length >> 8), UInt8(truncatingIfNeeded: length)]
            out += bytes[range]
        }
        return out
    }

    /// Width and height of the picture an SPS describes, after cropping. For the harness: proof that what went on the
    /// wire says the size that was promised in the accept.
    static func dimensions(ofSPS sps: [UInt8]) -> (width: Int, height: Int)? {
        var reader = BitReader(unescaped(Array(sps.dropFirst())))
        guard let profile = reader.bits(8) else { return nil }
        _ = reader.bits(16)
        _ = reader.ue()
        var chromaFormat = 1
        if [100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135].contains(profile) {
            guard let chroma = reader.ue() else { return nil }
            chromaFormat = chroma
            if chroma == 3 { _ = reader.bits(1) }
            _ = reader.ue()
            _ = reader.ue()
            _ = reader.bits(1)
            if reader.bits(1) == 1 {
                for index in 0 ..< (chroma != 3 ? 8 : 12) where reader.bits(1) == 1 {
                    var last = 8, next = 8
                    for _ in 0 ..< (index < 6 ? 16 : 64) where next != 0 {
                        guard let delta = reader.se() else { return nil }
                        next = (last + delta + 256) % 256
                        last = next == 0 ? last : next
                    }
                }
            }
        }
        _ = reader.ue()
        guard let pocType = reader.ue() else { return nil }
        if pocType == 0 {
            _ = reader.ue()
        } else if pocType == 1 {
            _ = reader.bits(1)
            _ = reader.se()
            _ = reader.se()
            guard let cycle = reader.ue() else { return nil }
            for _ in 0 ..< cycle { _ = reader.se() }
        }
        _ = reader.ue()
        _ = reader.bits(1)
        guard let widthMbs = reader.ue(), let heightMapUnits = reader.ue(), let frameMbsOnly = reader.bits(1) else { return nil }
        if frameMbsOnly == 0 { _ = reader.bits(1) }
        _ = reader.bits(1)
        var crop = (left: 0, right: 0, top: 0, bottom: 0)
        if reader.bits(1) == 1 {
            guard let left = reader.ue(), let right = reader.ue(), let top = reader.ue(), let bottom = reader.ue() else { return nil }
            crop = (left, right, top, bottom)
        }
        let unitX = chromaFormat == 0 || chromaFormat == 3 ? 1 : 2
        let unitY = (chromaFormat == 1 ? 2 : 1) * (2 - frameMbsOnly)
        let width = (widthMbs + 1) * 16 - unitX * (crop.left + crop.right)
        let height = (2 - frameMbsOnly) * (heightMapUnits + 1) * 16 - unitY * (crop.top + crop.bottom)
        return (width, height)
    }

    /// Emulation prevention bytes (00 00 03) taken out, so the bits can be read as they were written.
    private static func unescaped(_ bytes: [UInt8]) -> [UInt8] {
        var out = [UInt8]()
        var zeros = 0
        for byte in bytes {
            if zeros >= 2, byte == 3 {
                zeros = 0
                continue
            }
            out.append(byte)
            zeros = byte == 0 ? zeros + 1 : 0
        }
        return out
    }

    private struct BitReader {
        let bytes: [UInt8]
        var position = 0

        init(_ bytes: [UInt8]) { self.bytes = bytes }

        mutating func bits(_ count: Int) -> Int? {
            var value = 0
            for _ in 0 ..< count {
                guard position / 8 < bytes.count else { return nil }
                value = (value << 1) | Int((bytes[position / 8] >> (7 - UInt8(position % 8))) & 1)
                position += 1
            }
            return value
        }

        mutating func ue() -> Int? {
            var zeros = 0
            while true {
                guard let bit = bits(1) else { return nil }
                if bit == 1 { break }
                zeros += 1
                if zeros > 31 { return nil }
            }
            guard zeros > 0 else { return 0 }
            guard let rest = bits(zeros) else { return nil }
            return (1 << zeros) - 1 + rest
        }

        mutating func se() -> Int? {
            guard let value = ue() else { return nil }
            return value % 2 == 1 ? (value + 1) / 2 : -(value / 2)
        }
    }
}

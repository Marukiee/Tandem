import CommonCrypto
import CryptoKit
import Darwin
import Foundation

/// A client of the Screen Sharing of macOS itself, on this Mac (127.0.0.1). Only the system can show and use the lock screen, and Screen
/// Sharing is the part of the system that lets someone in from elsewhere, so a locked Mac is reached through it: this client reads its
/// picture and sends it the pointer and the keys, and the rest of the session (encoder, core, viewer) stays what it always is. Nothing of
/// this leaves the Mac unencrypted: the stream is on the loopback, what goes out is the H.264 of the session over QUIC.
///
/// RFB 3.8 (RFC 6143) with the Apple login (security type 30), Raw and CopyRect pictures and the pointer as a separate image.
final class ScreenSharingClient: @unchecked Sendable {
    enum Failure: Error, CustomStringConvertible, Equatable {
        case unreachable
        case protocolError(String)
        case noAppleLogin
        case wrongPassword
        case closed

        var description: String {
            switch self {
            case .unreachable: "Screen Sharing does not answer on this Mac"
            case let .protocolError(what): "Screen Sharing said something unexpected: \(what)"
            case .noAppleLogin: "Screen Sharing does not offer a login with the account of this Mac"
            case .wrongPassword: "Screen Sharing did not take the password"
            case .closed: "Screen Sharing closed the connection"
            }
        }
    }

    /// The picture as it is now: 4 bytes a pixel, blue green red and one unused, rows without padding.
    struct Picture {
        let width: Int
        let height: Int
        let bytes: UnsafeRawPointer
    }

    private(set) var width = 0
    private(set) var height = 0
    private(set) var name = ""

    private var socket: Int32 = -1
    private let sendLock = NSLock()
    private var framebuffer: UnsafeMutableRawPointer?
    private var cursor: (width: Int, height: Int, hotX: Int, hotY: Int, pixels: [UInt8], mask: [UInt8])?
    private let pointerLock = NSLock()
    private var pointer = (x: 0, y: 0, buttons: UInt8(0))

    deinit {
        close()
        framebuffer?.deallocate()
    }

    // MARK: Connecting

    /// Connects and logs in as `user`. Throws `wrongPassword` when Screen Sharing turned the login down.
    func connect(port: UInt16, user: String, password: String) throws {
        socket = Darwin.socket(AF_INET, SOCK_STREAM, 0)
        guard socket >= 0 else { throw Failure.unreachable }
        var noDelay: Int32 = 1
        setsockopt(socket, IPPROTO_TCP, TCP_NODELAY, &noDelay, socklen_t(MemoryLayout<Int32>.size))
        var timeout = timeval(tv_sec: 10, tv_usec: 0)
        setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        var address = sockaddr_in()
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = port.bigEndian
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let connected = withUnsafePointer(to: &address) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { Darwin.connect(socket, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) }
        }
        guard connected == 0 else {
            close()
            throw Failure.unreachable
        }

        let version = try read(12)
        guard version.starts(with: Array("RFB ".utf8)) else { throw Failure.protocolError("no RFB greeting") }
        // macOS says 003.889, its own number; 3.8 is what it speaks to every other client.
        try send(Array("RFB 003.008\n".utf8))
        let count = Int(try read(1)[0])
        if count == 0 { throw Failure.protocolError(try reason()) }
        let types = try read(count)
        guard types.contains(30) else { throw Failure.noAppleLogin }
        try send([30])
        try appleLogin(user: user, password: password)
        let result = try readU32()
        if result != 0 {
            // The reason is there in 3.8, and says nothing a person can act on beyond "wrong".
            _ = try? reason()
            throw Failure.wrongPassword
        }

        // Shared: whoever else uses Screen Sharing keeps their connection.
        try send([1])
        width = Int(try readU16())
        height = Int(try readU16())
        _ = try read(16)
        name = String(decoding: try read(Int(try readU32())), as: UTF8.self)
        resize(width: width, height: height)

        // 32 bits a pixel, little endian, red at 16, green at 8, blue at 0: blue green red unused in memory, what a pixel buffer of
        // 32BGRA is.
        try send([0, 0, 0, 0, 32, 24, 0, 1, 0, 255, 0, 255, 0, 255, 16, 8, 0, 0, 0, 0])
        // Raw, CopyRect, the pointer as an image (-239), where the pointer is (-232), a new size (-223).
        let encodings: [Int32] = [1, 0, -239, -232, -223]
        var message: [UInt8] = [2, 0]
        message += be16(UInt16(encodings.count))
        for encoding in encodings { message += be32(UInt32(bitPattern: encoding)) }
        try send(message)
        // Updates come when the screen changes; a still lock screen sends nothing for a long time.
        var none = timeval(tv_sec: 0, tv_usec: 0)
        setsockopt(socket, SOL_SOCKET, SO_RCVTIMEO, &none, socklen_t(MemoryLayout<timeval>.size))
    }

    /// Only checks that the password is taken, and leaves again before any picture.
    static func check(port: UInt16, user: String, password: String) throws {
        let client = ScreenSharingClient()
        defer { client.close() }
        try client.connect(port: port, user: user, password: password)
    }

    func close() {
        sendLock.lock()
        let open = socket
        socket = -1
        sendLock.unlock()
        if open >= 0 {
            shutdown(open, SHUT_RDWR)
            Darwin.close(open)
        }
    }

    // MARK: The Apple login

    /// Diffie-Hellman with the numbers Screen Sharing sends, MD5 of the shared secret as the key, and the account name and password
    /// encrypted with it (AES-128, ECB), 64 bytes each, ended by a zero and filled with random bytes.
    private func appleLogin(user: String, password: String) throws {
        let generator = try read(2)
        let length = Int(try readU16())
        guard (16 ... 1024).contains(length) else { throw Failure.protocolError("a key of \(length) bytes") }
        let prime = try read(length)
        let theirs = try read(length)

        var secret = [UInt8](repeating: 0, count: length)
        guard SecRandomCopyBytes(kSecRandomDefault, length, &secret) == errSecSuccess else { throw Failure.protocolError("no random bytes") }
        guard let modulus = Montgomery(modulus: prime) else { throw Failure.protocolError("an even prime") }
        let ours = modulus.power(generator, secret)
        let shared = modulus.power(theirs, secret)
        let key = Array(Insecure.MD5.hash(data: Data(shared)))

        var credentials = [UInt8](repeating: 0, count: 128)
        _ = SecRandomCopyBytes(kSecRandomDefault, 128, &credentials)
        for (offset, text) in [(0, user), (64, password)] {
            let bytes = Array(text.utf8.prefix(63))
            for (index, byte) in bytes.enumerated() { credentials[offset + index] = byte }
            credentials[offset + bytes.count] = 0
        }
        try send(Self.encrypt(credentials, key: key) + ours)
    }

    static func encrypt(_ plain: [UInt8], key: [UInt8]) throws -> [UInt8] {
        var out = [UInt8](repeating: 0, count: plain.count)
        var moved = 0
        let status = CCCrypt(
            CCOperation(kCCEncrypt), CCAlgorithm(kCCAlgorithmAES128), CCOptions(kCCOptionECBMode),
            key, key.count, nil, plain, plain.count, &out, out.count, &moved
        )
        guard status == kCCSuccess, moved == plain.count else { throw Failure.protocolError("AES failed") }
        return out
    }

    // MARK: Pictures

    /// Asks for what changed (or everything) and reads until that update is in. Messages that are not pictures are read past.
    /// Returns false when the update held nothing new.
    func update(full: Bool) throws -> Bool {
        try send([3, full ? 0 : 1] + be16(0) + be16(0) + be16(UInt16(width)) + be16(UInt16(height)))
        while true {
            let type = try read(1)[0]
            switch type {
            case 0:
                return try readUpdate()
            case 1:
                _ = try read(3)
                let colours = Int(try readU16())
                _ = try read(colours * 6)
            case 2:
                break
            case 3:
                _ = try read(3)
                _ = try read(Int(try readU32()))
            default:
                throw Failure.protocolError("message \(type)")
            }
        }
    }

    private func readUpdate() throws -> Bool {
        _ = try read(1)
        let count = Int(try readU16())
        var changed = false
        for _ in 0 ..< count {
            let x = Int(try readU16()), y = Int(try readU16())
            let w = Int(try readU16()), h = Int(try readU16())
            let encoding = Int32(bitPattern: try readU32())
            switch encoding {
            case 0:
                try readRaw(x: x, y: y, w: w, h: h)
                changed = true
            case 1:
                let sx = Int(try readU16()), sy = Int(try readU16())
                copy(from: (sx, sy), to: (x, y), w: w, h: h)
                changed = true
            case -239:
                let pixels = try read(w * h * 4)
                let mask = try read((w + 7) / 8 * h)
                cursor = w > 0 && h > 0 ? (w, h, x, y, pixels, mask) : nil
                changed = true
            case -232:
                pointerLock.withLock {
                    pointer.x = x
                    pointer.y = y
                }
                changed = true
            case -223:
                resize(width: w, height: h)
                changed = true
            default:
                throw Failure.protocolError("encoding \(encoding)")
            }
        }
        return changed
    }

    private func readRaw(x: Int, y: Int, w: Int, h: Int) throws {
        guard let framebuffer, x + w <= width, y + h <= height else {
            _ = try read(w * h * 4)
            return
        }
        for row in 0 ..< h {
            try read(into: framebuffer + ((y + row) * width + x) * 4, count: w * 4)
        }
    }

    private func copy(from source: (x: Int, y: Int), to target: (x: Int, y: Int), w: Int, h: Int) {
        guard let framebuffer, source.x + w <= width, source.y + h <= height, target.x + w <= width, target.y + h <= height else { return }
        // Rows in the order that does not overwrite what is still to be copied when the two overlap.
        let rows = target.y > source.y ? Array((0 ..< h).reversed()) : Array(0 ..< h)
        for row in rows {
            memmove(framebuffer + ((target.y + row) * width + target.x) * 4, framebuffer + ((source.y + row) * width + source.x) * 4, w * 4)
        }
    }

    private func resize(width: Int, height: Int) {
        framebuffer?.deallocate()
        self.width = width
        self.height = height
        framebuffer = UnsafeMutableRawPointer.allocate(byteCount: max(1, width * height * 4), alignment: 16)
        memset(framebuffer!, 0, max(1, width * height * 4))
    }

    /// Hands the picture with the pointer drawn in, to copy from. Only good inside `body`, on the thread that reads updates.
    func withPicture<T>(_ body: (Picture) -> T) -> T? {
        guard let framebuffer, width > 0, height > 0 else { return nil }
        let at = pointerLock.withLock { (pointer.x, pointer.y) }
        // The pointer is drawn into the picture and taken out again after, so the picture underneath stays what the server sent.
        var saved: [(Int, UInt32)] = []
        if let cursor {
            let pixels = framebuffer.assumingMemoryBound(to: UInt32.self)
            let rowBytes = (cursor.width + 7) / 8
            for cy in 0 ..< cursor.height {
                let py = at.1 - cursor.hotY + cy
                guard py >= 0, py < height else { continue }
                for cx in 0 ..< cursor.width {
                    let px = at.0 - cursor.hotX + cx
                    guard px >= 0, px < width, cursor.mask[cy * rowBytes + cx / 8] & (0x80 >> UInt8(cx % 8)) != 0 else { continue }
                    let index = py * width + px
                    let source = (cy * cursor.width + cx) * 4
                    saved.append((index, pixels[index]))
                    pixels[index] = UInt32(cursor.pixels[source]) | UInt32(cursor.pixels[source + 1]) << 8
                        | UInt32(cursor.pixels[source + 2]) << 16 | 0xFF00_0000
                }
            }
        }
        let result = body(Picture(width: width, height: height, bytes: UnsafeRawPointer(framebuffer)))
        let pixels = framebuffer.assumingMemoryBound(to: UInt32.self)
        for (index, value) in saved { pixels[index] = value }
        return result
    }

    // MARK: Input

    /// The pointer to `x`, `y` in pixels of the picture.
    func movePointer(x: Int, y: Int) {
        let state = pointerLock.withLock { () -> (Int, Int, UInt8) in
            pointer.x = min(max(0, x), max(0, width - 1))
            pointer.y = min(max(0, y), max(0, height - 1))
            return (pointer.x, pointer.y, pointer.buttons)
        }
        sendPointer(state)
    }

    func movePointer(dx: Double, dy: Double) {
        let now = pointerLock.withLock { pointer }
        movePointer(x: now.x + Int(dx.rounded()), y: now.y + Int(dy.rounded()))
    }

    /// The buttons of the wire (0 left, 1 right, 2 middle) as the bits of RFB (1 left, 2 middle, 4 right).
    func button(_ button: UInt8, down: Bool) {
        let bit: UInt8 = switch button {
        case 1: 4
        case 2: 2
        default: 1
        }
        let state = pointerLock.withLock { () -> (Int, Int, UInt8) in
            if down { pointer.buttons |= bit } else { pointer.buttons &= ~bit }
            return (pointer.x, pointer.y, pointer.buttons)
        }
        sendPointer(state)
    }

    /// One step of a wheel: buttons 4 and 5 are up and down, 6 and 7 left and right, each a press and a release.
    func scrollStep(up: Bool? = nil, left: Bool? = nil) {
        let bit: UInt8
        if let up { bit = up ? 8 : 16 } else if let left { bit = left ? 32 : 64 } else { return }
        let state = pointerLock.withLock { pointer }
        sendPointer((state.x, state.y, state.buttons | bit))
        sendPointer((state.x, state.y, state.buttons))
    }

    func releaseButtons() {
        let state = pointerLock.withLock { () -> (Int, Int, UInt8) in
            pointer.buttons = 0
            return (pointer.x, pointer.y, 0)
        }
        sendPointer(state)
    }

    var pointerPosition: (x: Int, y: Int) { pointerLock.withLock { (pointer.x, pointer.y) } }

    func key(_ keysym: UInt32, down: Bool) {
        try? send([4, down ? 1 : 0, 0, 0] + be32(keysym))
    }

    private func sendPointer(_ state: (Int, Int, UInt8)) {
        try? send([5, state.2] + be16(UInt16(clamping: state.0)) + be16(UInt16(clamping: state.1)))
    }

    // MARK: Bytes

    private func send(_ bytes: [UInt8]) throws {
        sendLock.lock()
        defer { sendLock.unlock() }
        guard socket >= 0 else { throw Failure.closed }
        var offset = 0
        while offset < bytes.count {
            let wrote = bytes.withUnsafeBytes { Darwin.send(socket, $0.baseAddress! + offset, bytes.count - offset, 0) }
            if wrote <= 0 {
                if wrote < 0, errno == EINTR { continue }
                throw Failure.closed
            }
            offset += wrote
        }
    }

    private func read(into target: UnsafeMutableRawPointer, count: Int) throws {
        var offset = 0
        while offset < count {
            let open = socket
            guard open >= 0 else { throw Failure.closed }
            let got = recv(open, target + offset, count - offset, 0)
            if got <= 0 {
                if got < 0, errno == EINTR { continue }
                throw Failure.closed
            }
            offset += got
        }
    }

    private func read(_ count: Int) throws -> [UInt8] {
        guard count > 0 else { return [] }
        var bytes = [UInt8](repeating: 0, count: count)
        try bytes.withUnsafeMutableBytes { try read(into: $0.baseAddress!, count: count) }
        return bytes
    }

    private func readU16() throws -> UInt16 {
        let b = try read(2)
        return UInt16(b[0]) << 8 | UInt16(b[1])
    }

    private func readU32() throws -> UInt32 {
        let b = try read(4)
        return UInt32(b[0]) << 24 | UInt32(b[1]) << 16 | UInt32(b[2]) << 8 | UInt32(b[3])
    }

    private func reason() throws -> String {
        String(decoding: try read(Int(min(try readU32(), 4096))), as: UTF8.self)
    }

    private func be16(_ value: UInt16) -> [UInt8] { [UInt8(value >> 8), UInt8(value & 0xFF)] }
    private func be32(_ value: UInt32) -> [UInt8] { [UInt8(value >> 24), UInt8(value >> 16 & 0xFF), UInt8(value >> 8 & 0xFF), UInt8(value & 0xFF)] }
}

// MARK: - Keys

extension ScreenSharingClient {
    /// The X11 keysym of the key at a USB HID position, as on a US keyboard: Screen Sharing turns symbols into the keys of the Mac's
    /// own layout. Nil for a usage it has no symbol for.
    static func keysym(forUsage usage: UInt32) -> UInt32? {
        switch usage {
        case 0x04 ... 0x1D: return 0x61 + usage - 0x04
        case 0x1E ... 0x26: return 0x31 + usage - 0x1E
        case 0x27: return 0x30
        case 0x3A ... 0x45: return 0xFFBE + usage - 0x3A
        case 0x59 ... 0x61: return 0xFFB1 + usage - 0x59
        case 0x68 ... 0x73: return 0xFFCA + usage - 0x68
        default: return special[usage]
        }
    }

    private static let special: [UInt32: UInt32] = [
        0x28: 0xFF0D, 0x29: 0xFF1B, 0x2A: 0xFF08, 0x2B: 0xFF09, 0x2C: 0x20, 0x2D: 0x2D, 0x2E: 0x3D, 0x2F: 0x5B, 0x30: 0x5D,
        0x31: 0x5C, 0x32: 0x5C, 0x33: 0x3B, 0x34: 0x27, 0x35: 0x60, 0x36: 0x2C, 0x37: 0x2E, 0x38: 0x2F, 0x39: 0xFFE5,
        0x46: 0xFF61, 0x47: 0xFF14, 0x48: 0xFF13, 0x49: 0xFF63, 0x4A: 0xFF50, 0x4B: 0xFF55, 0x4C: 0xFFFF, 0x4D: 0xFF57,
        0x4E: 0xFF56, 0x4F: 0xFF53, 0x50: 0xFF51, 0x51: 0xFF54, 0x52: 0xFF52, 0x53: 0xFF7F, 0x54: 0xFFAF, 0x55: 0xFFAA,
        0x56: 0xFFAD, 0x57: 0xFFAB, 0x58: 0xFF8D, 0x62: 0xFFB0, 0x63: 0xFFAE, 0x64: 0x3C, 0x67: 0xFFBD,
    ]

    /// Shift, Control, Option and Command, in the order of the bits of the wire.
    static let modifierKeysyms: [UInt32] = [0xFFE1, 0xFFE3, 0xFFE9, 0xFFEB]

    /// A character as a keysym: Latin-1 is its own number, the rest of Unicode is 0x01000000 plus the code point.
    static func keysym(for scalar: Unicode.Scalar) -> UInt32 {
        switch scalar.value {
        case 0x0A, 0x0D: 0xFF0D
        case 0x09: 0xFF09
        case 0x08: 0xFF08
        case 0x20 ... 0x7E, 0xA0 ... 0xFF: scalar.value
        default: 0x0100_0000 + scalar.value
        }
    }
}

// MARK: - Big numbers

/// Powers modulo an odd number of a few hundred to a few thousand bits, which is all the Apple login needs: macOS has no public
/// Diffie-Hellman with numbers of its own choosing. Montgomery multiplication on 32 bit limbs, least significant first.
struct Montgomery {
    private let m: [UInt32]
    private let n: Int
    private let inverse: UInt32
    /// R² mod m, with R = 2^(32n): turns a number into the Montgomery form.
    private let r2: [UInt32]
    private let byteCount: Int

    init?(modulus bytes: [UInt8]) {
        let limbs = Self.limbs(bytes, count: (bytes.count + 3) / 4)
        guard let low = limbs.first, low & 1 == 1 else { return nil }
        m = limbs
        n = limbs.count
        byteCount = bytes.count
        // -m⁻¹ mod 2^32 by Newton's iteration: each step doubles the bits that are right.
        var x: UInt32 = 1
        for _ in 0 ..< 5 { x = x &* (2 &- low &* x) }
        inverse = 0 &- x
        // 1 doubled 2·32n times, each time brought back under m.
        var value = [UInt32](repeating: 0, count: n)
        value[0] = 1
        for _ in 0 ..< 64 * n {
            var carry: UInt32 = 0
            for index in 0 ..< n {
                let next = value[index] >> 31
                value[index] = value[index] << 1 | carry
                carry = next
            }
            if carry == 1 || !Self.less(value, limbs) { Self.subtract(&value, limbs) }
        }
        r2 = value
    }

    /// `base` to the power `exponent`, modulo m, as big endian bytes as long as the modulus.
    func power(_ base: [UInt8], _ exponent: [UInt8]) -> [UInt8] {
        var b = Self.limbs(base, count: n)
        // A base that is not below m is first brought below it; one times R² and back is base mod m.
        if !Self.less(b, m) { b = multiply(multiply(b, r2), [1] + [UInt32](repeating: 0, count: n - 1)) }
        let one = [1] + [UInt32](repeating: 0, count: n - 1)
        let baseM = multiply(b, r2)
        var result = multiply(one, r2)
        for byte in exponent {
            for bit in (0 ..< 8).reversed() {
                result = multiply(result, result)
                if byte >> UInt8(bit) & 1 == 1 { result = multiply(result, baseM) }
            }
        }
        result = multiply(result, one)
        return Self.bytes(result, count: byteCount)
    }

    private func multiply(_ a: [UInt32], _ b: [UInt32]) -> [UInt32] {
        var t = [UInt32](repeating: 0, count: n + 2)
        for i in 0 ..< n {
            var carry: UInt64 = 0
            let bi = UInt64(b[i])
            for j in 0 ..< n {
                let sum = UInt64(t[j]) + UInt64(a[j]) * bi + carry
                t[j] = UInt32(truncatingIfNeeded: sum)
                carry = sum >> 32
            }
            var sum = UInt64(t[n]) + carry
            t[n] = UInt32(truncatingIfNeeded: sum)
            t[n + 1] = UInt32(sum >> 32)

            let q = UInt64(t[0] &* inverse)
            sum = UInt64(t[0]) + q * UInt64(m[0])
            carry = sum >> 32
            for j in 1 ..< n {
                sum = UInt64(t[j]) + q * UInt64(m[j]) + carry
                t[j - 1] = UInt32(truncatingIfNeeded: sum)
                carry = sum >> 32
            }
            sum = UInt64(t[n]) + carry
            t[n - 1] = UInt32(truncatingIfNeeded: sum)
            t[n] = t[n + 1] &+ UInt32(sum >> 32)
        }
        var result = Array(t[0 ..< n])
        if t[n] != 0 || !Self.less(result, m) { Self.subtract(&result, m) }
        return result
    }

    private static func less(_ a: [UInt32], _ b: [UInt32]) -> Bool {
        for index in (0 ..< a.count).reversed() where a[index] != b[index] { return a[index] < b[index] }
        return false
    }

    private static func subtract(_ a: inout [UInt32], _ b: [UInt32]) {
        var borrow: Int64 = 0
        for index in 0 ..< a.count {
            let difference = Int64(a[index]) - Int64(b[index]) - borrow
            a[index] = UInt32(truncatingIfNeeded: difference)
            borrow = difference < 0 ? 1 : 0
        }
    }

    private static func limbs(_ bytes: [UInt8], count: Int) -> [UInt32] {
        var limbs = [UInt32](repeating: 0, count: count)
        for (index, byte) in bytes.reversed().enumerated() where index / 4 < count {
            limbs[index / 4] |= UInt32(byte) << UInt32(8 * (index % 4))
        }
        return limbs
    }

    private static func bytes(_ limbs: [UInt32], count: Int) -> [UInt8] {
        var out = [UInt8](repeating: 0, count: count)
        for index in 0 ..< count {
            out[count - 1 - index] = UInt8(truncatingIfNeeded: limbs[index / 4] >> UInt32(8 * (index % 4)))
        }
        return out
    }
}

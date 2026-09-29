import Foundation
import TandemCore

/// Where this Mac keeps its secrets: files in Application Support that only this user
/// can read, like an SSH key.
///
/// The Keychain would be the usual home, but macOS ties Keychain items to the exact
/// app that made them, and for an app without an Apple Developer ID that means a
/// permission prompt after every update. A private file survives updates untouched.
enum SecretFile {
    static func url(_ name: String) -> URL {
        EngineModel.supportDirectory().appendingPathComponent(name)
    }

    static func read(_ name: String) throws -> Data? {
        do {
            return try Data(contentsOf: url(name))
        } catch let error as CocoaError where error.code == .fileReadNoSuchFile || error.code == .fileNoSuchFile {
            return nil
        }
    }

    static func write(_ data: Data, to name: String) throws {
        let target = url(name)
        let temp = target.deletingLastPathComponent().appendingPathComponent(".\(name).tmp")
        FileManager.default.createFile(atPath: temp.path, contents: data, attributes: [.posixPermissions: 0o600])
        _ = try FileManager.default.replaceItemAt(target, withItemAt: temp)
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: target.path)
    }

    static func remove(_ name: String) {
        try? FileManager.default.removeItem(at: url(name))
    }
}

/// The device identity key.
final class FileVault: TandemVault, @unchecked Sendable {
    private let name = "identity.pkcs8"

    func load() throws -> Data? {
        do {
            return try SecretFile.read(name)
        } catch {
            throw TandemError.Failed(reason: error.localizedDescription)
        }
    }

    func save(secret: Data) throws {
        do {
            try SecretFile.write(secret, to: name)
        } catch {
            throw TandemError.Failed(reason: error.localizedDescription)
        }
    }
}

/// The password of the phone's hotspot, entered once in Settings.
enum HotspotCredentials {
    private static let name = "hotspot.secret"

    static func password() -> String? {
        (try? SecretFile.read(name)).flatMap { String(data: $0, encoding: .utf8) }
    }

    static func save(password: String) {
        if password.isEmpty {
            SecretFile.remove(name)
        } else {
            try? SecretFile.write(Data(password.utf8), to: name)
        }
    }
}

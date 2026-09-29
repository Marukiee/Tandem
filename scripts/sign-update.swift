// Signs a release file with the Ed25519 update key and prints the base64 signature.
// The Mac app checks it with CryptoKit over the raw bytes of the zip.
//
// The key comes from TANDEM_UPDATE_ED25519_SECRET (the secret itself, in CI) or from
// the file TANDEM_UPDATE_KEY_FILE (default ~/keystores/tandem-update-ed25519.secret).
//
// Usage: swift scripts/sign-update.swift <file> > <file>.sig
import CryptoKit
import Foundation

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(1)
}

guard CommandLine.arguments.count == 2 else { fail("usage: sign-update.swift <file>") }
let target = CommandLine.arguments[1]
let environment = ProcessInfo.processInfo.environment

var secret = environment["TANDEM_UPDATE_ED25519_SECRET"] ?? ""
if secret.isEmpty {
    let keyFile = environment["TANDEM_UPDATE_KEY_FILE"] ?? NSHomeDirectory() + "/keystores/tandem-update-ed25519.secret"
    guard let data = FileManager.default.contents(atPath: keyFile) else { fail("cannot read \(keyFile)") }
    secret = String(decoding: data, as: UTF8.self)
}
secret = secret.trimmingCharacters(in: .whitespacesAndNewlines)

guard let raw = Data(base64Encoded: secret), let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: raw) else {
    fail("the update key is not a base64 Ed25519 private key")
}
guard let bytes = FileManager.default.contents(atPath: target) else { fail("cannot read \(target)") }

// Refuse to sign with a key the shipped app would not accept. Catching this here is
// cheaper than a release that no installed Mac can update to.
let publicKeyFile = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("../macos/update-public-key.txt").standardized.path
if let shipped = FileManager.default.contents(atPath: publicKeyFile) {
    let expected = String(decoding: shipped, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
    guard key.publicKey.rawRepresentation.base64EncodedString() == expected else {
        fail("this key does not match macos/update-public-key.txt, installed apps would reject the update")
    }
}

let signature = try key.signature(for: bytes)
print(signature.base64EncodedString())

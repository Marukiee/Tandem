// Creates the Ed25519 key that signs Mac updates, once. Prints only the public key.
//
// The public key is compiled into the app (macos/update-public-key.txt), so every
// installed app trusts exactly one signer. Lose the private key and no installed Mac
// app accepts an update again. Running this a second time keeps the existing key and
// prints its public half.
//
// Usage: swift scripts/gen-update-key.swift
import CryptoKit
import Foundation

let environment = ProcessInfo.processInfo.environment
let folder = environment["TANDEM_KEYSTORES"] ?? NSHomeDirectory() + "/keystores"
let path = folder + "/tandem-update-ed25519.secret"
let manager = FileManager.default

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    exit(1)
}

try? manager.createDirectory(atPath: folder, withIntermediateDirectories: true, attributes: [.posixPermissions: 0o700])
try? manager.setAttributes([.posixPermissions: 0o700], ofItemAtPath: folder)

let privateKey: Curve25519.Signing.PrivateKey
if let existing = manager.contents(atPath: path) {
    let text = String(decoding: existing, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
    guard let raw = Data(base64Encoded: text), let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: raw) else {
        fail("\(path) exists but is not a base64 Ed25519 key")
    }
    privateKey = key
} else {
    privateKey = Curve25519.Signing.PrivateKey()
    let secret = Data(privateKey.rawRepresentation.base64EncodedString().utf8)
    // Created with the final mode so the secret is never readable by others, not even briefly.
    guard manager.createFile(atPath: path, contents: secret, attributes: [.posixPermissions: 0o600]) else {
        fail("could not write \(path)")
    }
}

print(privateKey.publicKey.rawRepresentation.base64EncodedString())

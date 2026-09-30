import AppKit
import CryptoKit
import Foundation
import Observation
import Security
import SwiftUI

/// Checks GitHub Releases for a newer version and installs it over the running app.
///
/// The same approach as the Android apps: read the releases feed at most once a day,
/// look for the asset under its fixed name, and only act when the person asks. The
/// download is checked twice before anything is replaced: against the SHA-256 in the
/// release, and against an Ed25519 signature whose public key is compiled into this
/// app. The new app must also be signed by the same certificate as this one, which is
/// what keeps Bluetooth, local network and accessibility permissions across updates.
@MainActor
@Observable
final class Updater {
    static let shared = Updater()

    struct Release: Equatable {
        let version: String
        let notes: String
        let zipURL: URL
        let shaURL: URL?
        let sigURL: URL?
        let size: Int64
        let page: URL
    }

    enum State: Equatable {
        case idle
        case checking
        case upToDate
        case available(Release)
        case downloading(Release, Double)
        case installing
        case failed(String)
    }

    var state: State = .idle
    var lastChecked: Date? = UserDefaults.standard.object(forKey: "updateLastChecked") as? Date

    @ObservationIgnored @AppStorage("autoCheckUpdates") var autoCheck = true

    private let owner = "Marukiee"
    private let repo = "Tandem"
    private let assetName = "Tandem-macOS.zip"

    var currentVersion: String { Bundle.main.appVersion }

    /// On launch: check at most once a day.
    func checkIfDue() {
        // A development build must never replace itself with a release.
        guard autoCheck, !AppIdentity.isDevelopmentBuild else { return }
        if let last = lastChecked, Date().timeIntervalSince(last) < 24 * 3600 { return }
        Task { await check(manual: false) }
    }

    /// `manual` is true for the button. An automatic check that fails says nothing.
    func check(manual: Bool = true) async {
        // The dev build must never replace itself with a release.
        guard !EngineModel.isDevBuild else {
            state = .upToDate
            return
        }
        state = .checking
        do {
            var request = URLRequest(url: URL(string: "https://api.github.com/repos/\(owner)/\(repo)/releases/latest")!)
            request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
            request.setValue("Tandem/\(currentVersion)", forHTTPHeaderField: "User-Agent")
            request.timeoutInterval = 20
            let (data, response) = try await URLSession.shared.data(for: request)
            // 404 means nothing has been published yet, which is not a failure.
            if (response as? HTTPURLResponse)?.statusCode == 404 {
                lastChecked = Date()
                UserDefaults.standard.set(lastChecked, forKey: "updateLastChecked")
                state = .upToDate
                return
            }
            guard (response as? HTTPURLResponse)?.statusCode == 200,
                  let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let tag = json["tag_name"] as? String
            else { throw UpdateError.feed }

            lastChecked = Date()
            UserDefaults.standard.set(lastChecked, forKey: "updateLastChecked")

            let version = VersionComparator.normalise(tag)
            guard VersionComparator.isNewer(version, than: currentVersion) else {
                state = .upToDate
                return
            }
            let assets = (json["assets"] as? [[String: Any]]) ?? []
            func asset(_ name: String) -> (URL, Int64)? {
                guard let entry = assets.first(where: { $0["name"] as? String == name }),
                      let link = (entry["browser_download_url"] as? String).flatMap(URL.init(string:))
                else { return nil }
                return (link, (entry["size"] as? Int64) ?? 0)
            }
            guard let (zip, size) = asset(assetName) else { throw UpdateError.noAsset }
            state = .available(Release(
                version: version,
                notes: (json["body"] as? String) ?? "",
                zipURL: zip,
                shaURL: asset(assetName + ".sha256")?.0,
                sigURL: asset(assetName + ".sig")?.0,
                size: size,
                page: (json["html_url"] as? String).flatMap(URL.init(string:)) ?? URL(string: "https://github.com/\(owner)/\(repo)/releases")!
            ))
        } catch {
            // A check nobody asked for stays quiet when it fails.
            state = manual ? .failed(String(localized: "Could not reach GitHub")) : .idle
        }
    }

    func install() async {
        guard case let .available(release) = state, !AppIdentity.isDevelopmentBuild else { return }
        do {
            state = .downloading(release, 0)
            let zip = try await download(release)
            state = .installing
            let staged = try await stage(zip: zip, release: release)
            try launchInstaller(newApp: staged)
        } catch let error as UpdateError {
            state = .failed(error.message)
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    // MARK: Steps

    private func download(_ release: Release) async throws -> URL {
        let target = FileManager.default.temporaryDirectory.appendingPathComponent("Tandem-\(release.version).zip")
        try? FileManager.default.removeItem(at: target)
        let (bytes, response) = try await URLSession.shared.bytes(from: release.zipURL)
        let total = response.expectedContentLength > 0 ? response.expectedContentLength : release.size
        FileManager.default.createFile(atPath: target.path, contents: nil)
        let handle = try FileHandle(forWritingTo: target)
        defer { try? handle.close() }
        var buffer = Data()
        var received: Int64 = 0
        var lastReport = Date()
        for try await byte in bytes {
            buffer.append(byte)
            if buffer.count >= 256 * 1024 {
                try handle.write(contentsOf: buffer)
                received += Int64(buffer.count)
                buffer.removeAll(keepingCapacity: true)
                if Date().timeIntervalSince(lastReport) > 0.1, total > 0 {
                    lastReport = Date()
                    state = .downloading(release, min(1, Double(received) / Double(total)))
                }
            }
        }
        if !buffer.isEmpty { try handle.write(contentsOf: buffer) }
        return target
    }

    /// Verifies and unpacks the download. Returns the new app, ready to swap in.
    private func stage(zip: URL, release: Release) async throws -> URL {
        let data = try Data(contentsOf: zip, options: .mappedIfSafe)

        if let shaURL = release.shaURL {
            let expected = try await text(of: shaURL).split(separator: " ").first.map(String.init)?.lowercased()
            let actual = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            guard expected == actual else { throw UpdateError.checksum }
        }

        if let key = Bundle.main.object(forInfoDictionaryKey: "TandemUpdatePublicKey") as? String,
           let raw = Data(base64Encoded: key), !key.isEmpty {
            guard let sigURL = release.sigURL else { throw UpdateError.signature }
            let signature = Data(base64Encoded: try await text(of: sigURL).trimmingCharacters(in: .whitespacesAndNewlines))
            guard let signature,
                  let publicKey = try? Curve25519.Signing.PublicKey(rawRepresentation: raw),
                  publicKey.isValidSignature(signature, for: data)
            else { throw UpdateError.signature }
        }

        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("tandem-update-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try run("/usr/bin/ditto", ["-x", "-k", zip.path, folder.path])
        guard let app = try FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil)
            .first(where: { $0.pathExtension == "app" })
        else { throw UpdateError.unpack }

        try run("/usr/bin/codesign", ["--verify", "--deep", "--strict", app.path])
        if let mine = Self.leafCertificateHash(of: Bundle.main.bundleURL),
           Self.leafCertificateHash(of: app) != mine {
            throw UpdateError.identity
        }
        return app
    }

    private func text(of url: URL) async throws -> String {
        let (data, _) = try await URLSession.shared.data(from: url)
        return String(decoding: data, as: UTF8.self)
    }

    @discardableResult
    private func run(_ tool: String, _ arguments: [String]) throws -> Int32 {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: tool)
        process.arguments = arguments
        let pipe = Pipe()
        process.standardError = pipe
        process.standardOutput = pipe
        try process.run()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw UpdateError.unpack }
        return process.terminationStatus
    }

    /// The hash of the certificate an app was signed with. Equal hashes mean the same
    /// developer identity, which is what macOS keys permissions on.
    static func leafCertificateHash(of url: URL) -> String? {
        var staticCode: SecStaticCode?
        guard SecStaticCodeCreateWithPath(url as CFURL, [], &staticCode) == errSecSuccess, let staticCode else { return nil }
        var info: CFDictionary?
        guard SecCodeCopySigningInformation(staticCode, SecCSFlags(rawValue: kSecCSSigningInformation), &info) == errSecSuccess,
              let dictionary = info as? [String: Any],
              let certificates = dictionary[kSecCodeInfoCertificates as String] as? [SecCertificate],
              let leaf = certificates.first
        else { return nil }
        let der = SecCertificateCopyData(leaf) as Data
        return SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined()
    }

    /// Swaps the app after this process has quit, then opens the new one.
    private func launchInstaller(newApp: URL) throws {
        let current = Bundle.main.bundleURL
        let script = """
        #!/bin/sh
        PID="$1"; OLD="$2"; NEW="$3"
        while kill -0 "$PID" 2>/dev/null; do sleep 0.2; done
        BACKUP="$OLD.previous"
        rm -rf "$BACKUP"
        if mv "$OLD" "$BACKUP" && mv "$NEW" "$OLD"; then
          xattr -dr com.apple.quarantine "$OLD" 2>/dev/null
          rm -rf "$BACKUP"
        else
          mv "$BACKUP" "$OLD" 2>/dev/null
        fi
        open "$OLD"
        """
        let scriptURL = FileManager.default.temporaryDirectory.appendingPathComponent("tandem-update.sh")
        try script.write(to: scriptURL, atomically: true, encoding: .utf8)
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/sh")
        process.arguments = [scriptURL.path, String(ProcessInfo.processInfo.processIdentifier), current.path, newApp.path]
        try process.run()
        NSApp.terminate(nil)
    }
}

enum UpdateError: Error {
    case feed, noAsset, checksum, signature, unpack, identity

    var message: String {
        switch self {
        case .feed: String(localized: "Could not read the releases from GitHub")
        case .noAsset: String(localized: "This release has no Mac download")
        case .checksum: String(localized: "The download is damaged (checksum does not match)")
        case .signature: String(localized: "The download is not signed by Tandem, so it was not installed")
        case .unpack: String(localized: "The download could not be unpacked")
        case .identity: String(localized: "The new version is signed by someone else, so it was not installed")
        }
    }
}

/// Compares versions the way a person reads them, so "1.10.0" beats "1.9.0". A suffix
/// after the numbers ("-rc1") counts as older than the plain release.
enum VersionComparator {
    static func normalise(_ raw: String) -> String {
        var text = raw.trimmingCharacters(in: .whitespaces)
        if text.hasPrefix("v") || text.hasPrefix("V") { text.removeFirst() }
        return text
    }

    static func isNewer(_ candidate: String, than current: String) -> Bool {
        compare(candidate, current) > 0
    }

    static func compare(_ left: String, _ right: String) -> Int {
        let a = numbers(left), b = numbers(right)
        for index in 0 ..< max(a.count, b.count) {
            let x = index < a.count ? a[index] : 0
            let y = index < b.count ? b[index] : 0
            if x != y { return x < y ? -1 : 1 }
        }
        let s1 = suffix(left), s2 = suffix(right)
        if s1 == s2 { return 0 }
        if s1.isEmpty { return 1 }
        if s2.isEmpty { return -1 }
        return s1 < s2 ? -1 : 1
    }

    private static func numbers(_ raw: String) -> [Int] {
        normalise(raw).split(separator: "-", maxSplits: 1).first.map(String.init)?
            .split(separator: ".").map { Int($0.prefix(while: \.isNumber)) ?? 0 } ?? []
    }

    private static func suffix(_ raw: String) -> String {
        let text = normalise(raw)
        guard let dash = text.firstIndex(of: "-") else { return "" }
        return String(text[text.index(after: dash)...]).lowercased()
    }
}

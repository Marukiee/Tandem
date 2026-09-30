import AppKit
import CryptoKit
import Foundation
import Observation
import Security
import SwiftUI

/// Checks GitHub Releases for a newer version and installs it over the running app.
///
/// The same approach as the Android apps: read the releases feed every few hours,
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

    /// `TANDEM_UPDATE_TEST_FROM` lets a development build pretend to be an older release,
    /// so the whole update path can be exercised without touching the installed app.
    private static let testFrom = ProcessInfo.processInfo.environment["TANDEM_UPDATE_TEST_FROM"]
    private var mayUpdateThisBuild: Bool { !AppIdentity.isDevelopmentBuild || Self.testFrom != nil }

    var currentVersion: String { Self.testFrom ?? Bundle.main.appVersion }

    /// With `TANDEM_DEBUG_DIR` set, every step is written to `update.log`.
    private func trace(_ message: String) {
        guard let dir = ProcessInfo.processInfo.environment["TANDEM_DEBUG_DIR"] else { return }
        let line = "\(Date().formatted(.iso8601)) \(message)\n"
        let url = URL(fileURLWithPath: dir).appendingPathComponent("update.log")
        if let handle = try? FileHandle(forWritingTo: url) {
            handle.seekToEndOfFile(); handle.write(Data(line.utf8)); try? handle.close()
        } else {
            try? line.write(to: url, atomically: true, encoding: .utf8)
        }
    }

    /// Test hook: check, then install the newest release, logging each step.
    func runTestUpdate() async {
        trace("test update from \(currentVersion)")
        await check(manual: true)
        trace("check finished: \(state)")
        await install()
    }

    /// Every time the app starts or comes to the front, at most once a minute. A menu bar app
    /// can stay open for weeks, so a check at launch alone would miss releases.
    func checkIfDue() {
        // A development build must never replace itself with a release.
        guard autoCheck, mayUpdateThisBuild else { return }
        if let last = lastChecked, Date().timeIntervalSince(last) < 60 { return }
        Task { await check(manual: false) }
    }

    @ObservationIgnored private var timer: Timer?

    func startPeriodicChecks() {
        guard timer == nil else { return }
        timer = Timer.scheduledTimer(withTimeInterval: 3600, repeats: true) { _ in
            Task { @MainActor in Updater.shared.checkIfDue() }
        }
    }

    /// `manual` is true for the button. An automatic check that fails says nothing.
    func check(manual: Bool = true) async {
        // The dev build must never replace itself with a release.
        guard mayUpdateThisBuild else {
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
            let found = Release(
                version: version,
                notes: (json["body"] as? String) ?? "",
                zipURL: zip,
                shaURL: asset(assetName + ".sha256")?.0,
                sigURL: asset(assetName + ".sig")?.0,
                size: size,
                page: (json["html_url"] as? String).flatMap(URL.init(string:)) ?? URL(string: "https://github.com/\(owner)/\(repo)/releases")!
            )
            state = .available(found)
            // A menu bar app is often not in view, so say it once per version.
            if !manual, UserDefaults.standard.string(forKey: "updateNotifiedVersion") != found.version {
                UserDefaults.standard.set(found.version, forKey: "updateNotifiedVersion")
                Notifier.shared.post(
                    id: "update.\(found.version)",
                    title: String(localized: "Tandem \(found.version) is available"),
                    body: String(localized: "Open Tandem to update. It only takes a moment.")
                )
            }
        } catch {
            // A check nobody asked for stays quiet when it fails.
            state = manual ? .failed(String(localized: "Could not reach GitHub")) : .idle
        }
    }

    func install() async {
        guard case let .available(release) = state, mayUpdateThisBuild else { return }
        do {
            state = .downloading(release, 0)
            trace("download start")
            let zip = try await download(release)
            trace("download done")
            state = .installing
            let staged = try await stage(zip: zip, release: release)
            trace("staged \(staged.path)")
            try launchInstaller(newApp: staged)
            trace("installer launched, quitting")
            // Not NSApp.terminate: it waits for the quit handler, which needs the main actor,
            // and this code is running on it. That deadlock left the spinner up for ever.
            // Close the engine politely, but never wait for it longer than a few seconds.
            Task.detached { try? await Task.sleep(for: .seconds(4)); exit(0) }
            await EngineModel.shared.stop()
            exit(0)
        } catch let error as UpdateError {
            trace("failed: \(error.message)")
            state = .failed(error.message)
        } catch {
            trace("failed: \(error.localizedDescription)")
            state = .failed(error.localizedDescription)
        }
    }

    // MARK: Steps

    private func download(_ release: Release) async throws -> URL {
        let target = FileManager.default.temporaryDirectory.appendingPathComponent("Tandem-\(release.version).zip")
        try? FileManager.default.removeItem(at: target)
        // A download task moves data in big chunks. Walking the response byte by byte, as an
        // earlier version did, took minutes for a few megabytes.
        let loader = FileDownloader(expectedSize: release.size) { [weak self] fraction in
            Task { @MainActor in
                if case .downloading = self?.state { self?.state = .downloading(release, fraction) }
            }
        }
        try await loader.download(release.zipURL, to: target)
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
        PID="$1"; OLD="$2"; NEW="$3"; OPEN="$4"
        while kill -0 "$PID" 2>/dev/null; do sleep 0.2; done
        BACKUP="$OLD.previous"
        rm -rf "$BACKUP"
        if mv "$OLD" "$BACKUP" && mv "$NEW" "$OLD"; then
          xattr -dr com.apple.quarantine "$OLD" 2>/dev/null
          rm -rf "$BACKUP"
        else
          mv "$BACKUP" "$OLD" 2>/dev/null
        fi
        [ "$OPEN" = noopen ] || open "$OLD"
        """
        let scriptURL = FileManager.default.temporaryDirectory.appendingPathComponent("tandem-update.sh")
        try script.write(to: scriptURL, atomically: true, encoding: .utf8)
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/bin/sh")
        // The test hook swaps the app but does not start the new one, which would be the
        // real Tandem running on the real data.
        let open = Self.testFrom != nil ? "noopen" : "open"
        process.arguments = [scriptURL.path, String(ProcessInfo.processInfo.processIdentifier), current.path, newApp.path, open]
        try process.run()
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


/// Downloads one file with progress. `URLSession.bytes` is convenient but far too slow for
/// a multi-megabyte archive, and this reports how far along it is.
private final class FileDownloader: NSObject, URLSessionDownloadDelegate, @unchecked Sendable {
    private let expectedSize: Int64
    private let progress: @Sendable (Double) -> Void
    private var continuation: CheckedContinuation<Void, Error>?
    private var target: URL?
    private var moveError: Error?
    private var lastReport = Date.distantPast

    init(expectedSize: Int64, progress: @escaping @Sendable (Double) -> Void) {
        self.expectedSize = expectedSize
        self.progress = progress
    }

    func download(_ url: URL, to target: URL) async throws {
        self.target = target
        let session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)
        defer { session.finishTasksAndInvalidate() }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            self.continuation = continuation
            session.downloadTask(with: url).resume()
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        let total = totalBytesExpectedToWrite > 0 ? totalBytesExpectedToWrite : expectedSize
        guard total > 0, Date().timeIntervalSince(lastReport) > 0.1 else { return }
        lastReport = Date()
        progress(min(1, Double(totalBytesWritten) / Double(total)))
    }

    // The temporary file is deleted as soon as this returns, so it is moved here.
    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let target else { return }
        do { try FileManager.default.moveItem(at: location, to: target) } catch { moveError = error }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        let result = error ?? moveError
        if let http = task.response as? HTTPURLResponse, http.statusCode != 200, result == nil {
            continuation?.resume(throwing: UpdateError.feed)
        } else if let result {
            continuation?.resume(throwing: result)
        } else {
            continuation?.resume()
        }
        continuation = nil
    }
}

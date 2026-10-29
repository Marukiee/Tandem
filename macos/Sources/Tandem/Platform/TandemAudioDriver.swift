import AppKit
import CoreAudio
import Foundation
import Observation

/// Tandem's own sound devices (macos/AudioDriver): Tandem Speaker in the list of outputs and Tandem Microphone in the list of inputs,
/// like AirPods. The driver travels inside the app and only works once it is in /Library/Audio/Plug-Ins/HAL, which needs an
/// administrator. The person turns it on in Settings and types their own password into the system's prompt; Tandem never keeps it.
///
/// Without the driver everything works as before: the phone as output through the tap and the outputs made per phone.
@MainActor @Observable
final class TandemAudioDriver {
    static let shared = TandemAudioDriver()

    nonisolated static let speakerUID = "nl.markmaaktmedia.tandem.audio.speaker"
    nonisolated static let speakerReturnUID = "nl.markmaaktmedia.tandem.audio.speaker.return"
    nonisolated static let microphoneUID = "nl.markmaaktmedia.tandem.audio.microphone"
    nonisolated static let microphoneFeedUID = "nl.markmaaktmedia.tandem.audio.microphone.feed"

    private static let installedURL = URL(fileURLWithPath: "/Library/Audio/Plug-Ins/HAL/TandemAudio.driver")
    private static let offeredKey = "tandemAudioUpdateOffered"

    /// The driver is in place, whether or not coreaudiod has loaded it yet.
    private(set) var installed = false
    /// Its devices exist: the sound menu shows them and Tandem can use them.
    private(set) var active = false
    /// The driver in this app is newer than the one in place.
    private(set) var outdated = false
    /// Installing or removing it right now.
    private(set) var busy = false

    @ObservationIgnored private var listener: AudioObjectPropertyListenerBlock?

    private init() {
        refresh(announce: false)
        // Not from inside init: what it tells may reach back here before `shared` exists.
        DispatchQueue.main.async { MainActor.assumeIsolated { self.activeChanged() } }
        // coreaudiod restarts after an install and the devices appear a moment later; the list of devices says when.
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDevices, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain
        )
        let block: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.refresh() } }
        }
        listener = block
        AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &address, DispatchQueue.main, block)
    }

    static var bundledURL: URL? { Bundle.main.url(forResource: "TandemAudio", withExtension: "driver") }

    private static func version(at url: URL?) -> Int? {
        guard let url, let info = NSDictionary(contentsOf: url.appendingPathComponent("Contents/Info.plist")) else { return nil }
        return (info["CFBundleVersion"] as? String).flatMap(Int.init)
    }

    /// Hidden devices are not in the list of devices, so they are looked up by UID, which is what a hidden device answers to.
    nonisolated static func device(_ uid: String) -> AudioObjectID? { AudioDevices.device(forUID: uid) }

    func refresh(announce: Bool = true) {
        let wasActive = active
        installed = FileManager.default.fileExists(atPath: Self.installedURL.path)
        active = Self.device(Self.speakerUID) != nil && Self.device(Self.speakerReturnUID) != nil
        let bundled = Self.version(at: Self.bundledURL)
        let inPlace = Self.version(at: installed ? Self.installedURL : nil)
        outdated = installed && bundled != nil && (inPlace ?? 0) < (bundled ?? 0)
        if announce, wasActive != active { activeChanged() }
    }

    private func activeChanged() {
        EngineModel.shared.speakerDevices.resync()
        PhoneMicrophone.shared.watch()
    }

    // MARK: Installing

    /// Puts the driver in place and restarts coreaudiod, after the system's administrator prompt. Nil when it went well or the
    /// person cancelled the prompt, else what went wrong.
    func install() async -> String? {
        guard let source = Self.bundledURL else { return String(localized: "This copy of Tandem has no sound devices to install") }
        let target = Self.installedURL.path
        return await run([
            "/bin/rm -rf \(Self.quoted(target))",
            "/usr/bin/ditto \(Self.quoted(source.path)) \(Self.quoted(target))",
            "/usr/sbin/chown -R root:wheel \(Self.quoted(target))",
            Self.restartAudio,
        ])
    }

    func uninstall() async -> String? {
        // Sound that goes to Tandem Speaker now would go nowhere once it is gone: back to the Mac's own output first.
        EngineModel.shared.speakerDevices.leaveTandemSpeaker()
        PhoneMicrophone.shared.stop(tell: true)
        return await run(["/bin/rm -rf \(Self.quoted(Self.installedURL.path))", Self.restartAudio])
    }

    /// launchctl is how macOS restarts the sound server itself; killall is for when that refuses, launchd then starts it again.
    private static let restartAudio = "(/bin/launchctl kickstart -k system/com.apple.audio.coreaudiod || /usr/bin/killall coreaudiod)"

    private func run(_ commands: [String]) async -> String? {
        busy = true
        defer { busy = false }
        let shell = commands.joined(separator: " && ")
        // AppleScript is the one supported way left for an app to run something as administrator after a password prompt, without a
        // helper tool that has to be installed first (AuthorizationExecuteWithPrivileges is deprecated).
        let source = "do shell script \"\(Self.appleScriptEscaped(shell))\" with administrator privileges"
        let outcome: String? = await Task.detached {
            var error: NSDictionary?
            NSAppleScript(source: source)?.executeAndReturnError(&error)
            guard let error else { return nil }
            // -128: the person pressed Cancel. Nothing to say about that.
            if (error[NSAppleScript.errorNumber] as? Int) == -128 { return nil }
            return error[NSAppleScript.errorMessage] as? String ?? String(localized: "It did not work")
        }.value
        // The devices take a moment to come back after the restart.
        for _ in 0..<20 {
            refresh()
            if active == installed { break }
            try? await Task.sleep(for: .milliseconds(250))
        }
        return outcome
    }

    private static func quoted(_ path: String) -> String { "'" + path.replacingOccurrences(of: "'", with: "'\\''") + "'" }

    private static func appleScriptEscaped(_ text: String) -> String {
        text.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
    }

    /// When the driver in this app is newer than the one in place, asks once per version whether to put it in place, at the moment the
    /// person uses it. Never by itself at launch, and never in a debug launch, which would put up a password prompt during a test.
    func offerUpdateIfNeeded() {
        guard outdated, !busy, DebugSupport.directory == nil, let bundled = Self.version(at: Self.bundledURL) else { return }
        guard UserDefaults.standard.integer(forKey: Self.offeredKey) < bundled else { return }
        UserDefaults.standard.set(bundled, forKey: Self.offeredKey)
        let alert = NSAlert()
        alert.messageText = String(localized: "Update Tandem's sound devices?")
        alert.informativeText = String(localized: "This version of Tandem has a newer Tandem Speaker and Tandem Microphone. Updating asks for your password and stops all sound for a moment.")
        alert.addButton(withTitle: String(localized: "Update"))
        alert.addButton(withTitle: String(localized: "Later"))
        guard alert.runModal() == .alertFirstButtonReturn else { return }
        Task {
            if let problem = await install() { EngineModel.shared.showToast(problem) }
        }
    }
}

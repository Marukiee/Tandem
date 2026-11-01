import AppKit
import Foundation
import IOKit
import IOKit.ps
import IOKit.pwr_mgt
import Observation

/// Keeps this Mac awake so it can be reached from elsewhere, also with the lid closed.
///
/// Awake with the lid open is an assertion any app may hold. With the lid closed macOS sleeps whatever apps say, unless sleep is switched
/// off for the whole system (`pmset disablesleep`), which only an administrator may do. That is asked once: the answer installs a rule
/// that lets this user switch just that, and nothing else, without a password from then on.
@MainActor
@Observable
final class KeepAwake {
    static let shared = KeepAwake()

    private static let onKey = "keepAwake"
    private static let lidKey = "keepAwakeLidClosed"
    /// Set while this app switched sleep off for the system, so a crash or a quit in between is undone at the next start.
    private static let disabledKey = "keepAwakeDisabledSleep"
    nonisolated private static let rule = "/etc/sudoers.d/tandem-pmset"
    /// Below this, on the battery, awake gives way: a closed Mac in a bag would otherwise run empty and hot.
    static let batteryFloor = 15

    private(set) var on = UserDefaults.standard.bool(forKey: onKey)
    var lidClosed = UserDefaults.standard.object(forKey: lidKey) as? Bool ?? true {
        didSet {
            UserDefaults.standard.set(lidClosed, forKey: Self.lidKey)
            if on { applyLid() }
        }
    }
    /// Asking for the administrator password right now.
    private(set) var asking = false

    @ObservationIgnored private var assertion: IOPMAssertionID = 0
    @ObservationIgnored private var sessionAssertion: IOPMAssertionID = 0
    @ObservationIgnored private var guardTask: Task<Void, Never>?

    /// What was left behind by a run that did not end well, and what was on before a restart.
    func start() {
        if UserDefaults.standard.bool(forKey: Self.disabledKey), !(on && lidClosed) { Task { await setSystemSleep(disabled: false) } }
        if on { apply() }
    }

    func toggle() { set(!on) }

    func set(_ wanted: Bool) {
        on = wanted
        UserDefaults.standard.set(wanted, forKey: Self.onKey)
        apply()
    }

    /// Undone when Tandem quits: a Mac that sleeps again when the app that kept it awake is gone is what a person expects.
    func shutdown() {
        release(&assertion)
        release(&sessionAssertion)
        if UserDefaults.standard.bool(forKey: Self.disabledKey) { runQuietly(disabled: false) }
    }

    private func apply() {
        if on {
            hold(&assertion, kIOPMAssertPreventUserIdleSystemSleep as String, "Keep awake is on in Tandem")
            watchBattery()
        } else {
            release(&assertion)
            guardTask?.cancel()
            guardTask = nil
        }
        applyLid()
    }

    private func applyLid() {
        let wanted = on && lidClosed
        guard wanted != UserDefaults.standard.bool(forKey: Self.disabledKey) else { return }
        Task { await setSystemSleep(disabled: wanted) }
    }

    // MARK: A device looks at this Mac

    /// A screen that is looked at keeps the Mac awake and its display on, for as long as someone looks.
    func setSession(_ active: Bool) {
        if active {
            hold(&sessionAssertion, kIOPMAssertPreventUserIdleDisplaySleep as String, "Another device shows this Mac's screen")
            wakeDisplay()
        } else {
            release(&sessionAssertion)
        }
    }

    /// A display that went to sleep shows nothing to a capture; this is what a touch of the mouse does.
    func wakeDisplay() {
        var id: IOPMAssertionID = 0
        IOPMAssertionDeclareUserActivity("Tandem remote input" as CFString, kIOPMUserActiveLocal, &id)
    }

    // MARK: The battery

    private func watchBattery() {
        guard guardTask == nil else { return }
        guardTask = Task { [weak self] in
            while !Task.isCancelled {
                if let level = Self.batteryLevelOnBattery(), level < Self.batteryFloor {
                    self?.set(false)
                    Notifier.shared.post(
                        id: "keepawake.battery",
                        title: String(localized: "This Mac may sleep again"),
                        body: String(localized: "Keep awake turned off, the battery is at \(level)%."),
                        sound: false
                    )
                    return
                }
                try? await Task.sleep(for: .seconds(60))
            }
        }
    }

    /// The charge in percent while the Mac runs on its battery, nil on power or without a battery.
    private static func batteryLevelOnBattery() -> Int? {
        guard let info = IOPSCopyPowerSourcesInfo()?.takeRetainedValue(),
              let list = IOPSCopyPowerSourcesList(info)?.takeRetainedValue() as? [CFTypeRef]
        else { return nil }
        for source in list {
            guard let description = IOPSGetPowerSourceDescription(info, source)?.takeUnretainedValue() as? [String: Any],
                  description[kIOPSPowerSourceStateKey] as? String == kIOPSBatteryPowerValue,
                  let current = description[kIOPSCurrentCapacityKey] as? Int,
                  let max = description[kIOPSMaxCapacityKey] as? Int, max > 0
            else { continue }
            return current * 100 / max
        }
        return nil
    }

    // MARK: Sleep of the whole system

    /// Switches sleep of the system off or on again. Without the rule yet, macOS asks for an administrator once, and the rule is put in
    /// place in the same go.
    private func setSystemSleep(disabled: Bool) async {
        if await Task.detached(operation: { Self.pmset(disabled: disabled) }).value {
            UserDefaults.standard.set(disabled, forKey: Self.disabledKey)
            return
        }
        // Turning it back on needs no question when there is nothing to undo.
        guard disabled || UserDefaults.standard.bool(forKey: Self.disabledKey) else { return }
        asking = true
        let installed = await Task.detached(operation: { Self.installRule(thenDisable: disabled) }).value
        asking = false
        if installed {
            UserDefaults.standard.set(disabled, forKey: Self.disabledKey)
        } else if disabled {
            // No password given: awake with the lid open still works, the closed lid does not.
            lidClosed = false
            FloatingToast.show(String(localized: "Without your password this Mac still sleeps when you close it"), symbol: "laptopcomputer")
        }
    }

    private func runQuietly(disabled: Bool) {
        if Self.pmset(disabled: disabled) { UserDefaults.standard.set(disabled, forKey: Self.disabledKey) }
    }

    /// Through the rule, without a password; false when the rule is not there.
    nonisolated private static func pmset(disabled: Bool) -> Bool {
        run("/usr/bin/sudo", ["-n", "/usr/bin/pmset", "-a", "disablesleep", disabled ? "1" : "0"])
    }

    /// The rule, checked by visudo before it goes in, and the first switch, with one question for an administrator.
    nonisolated private static func installRule(thenDisable disabled: Bool) -> Bool {
        let user = NSUserName()
        guard user.allSatisfy({ $0.isLetter || $0.isNumber || "._-".contains($0) }) else { return false }
        let line = "\(user) ALL=(root) NOPASSWD: /usr/bin/pmset -a disablesleep 0, /usr/bin/pmset -a disablesleep 1"
        let temp = FileManager.default.temporaryDirectory.appendingPathComponent("tandem-pmset-\(UUID().uuidString)")
        guard (try? (line + "\n").write(to: temp, atomically: true, encoding: .utf8)) != nil else { return false }
        defer { try? FileManager.default.removeItem(at: temp) }
        let script = "/usr/sbin/visudo -cf '\(temp.path)' && /usr/bin/install -m 0440 -o root -g wheel '\(temp.path)' \(rule) && /usr/bin/pmset -a disablesleep \(disabled ? 1 : 0)"
        let prompt = String(localized: "Tandem wants to keep this Mac awake with the lid closed.")
        let apple = "do shell script \"\(script)\" with prompt \"\(prompt.replacingOccurrences(of: "\"", with: ""))\" with administrator privileges"
        return run("/usr/bin/osascript", ["-e", apple])
    }

    nonisolated private static func run(_ path: String, _ arguments: [String]) -> Bool {
        let task = Process()
        task.executableURL = URL(fileURLWithPath: path)
        task.arguments = arguments
        task.standardOutput = FileHandle.nullDevice
        task.standardError = FileHandle.nullDevice
        do { try task.run() } catch { return false }
        task.waitUntilExit()
        return task.terminationStatus == 0
    }

    // MARK: Assertions

    private func hold(_ id: inout IOPMAssertionID, _ type: String, _ reason: String) {
        guard id == 0 else { return }
        IOPMAssertionCreateWithName(type as CFString, IOPMAssertionLevel(kIOPMAssertionLevelOn), reason as CFString, &id)
    }

    private func release(_ id: inout IOPMAssertionID) {
        guard id != 0 else { return }
        IOPMAssertionRelease(id)
        id = 0
    }
}

/// Whether the lid of this Mac is closed, and whether a screen of its own is still on: with the lid closed and no other display there is
/// nothing to capture, and a display of software stands in.
enum Lid {
    static var isClosed: Bool {
        let root = IOServiceGetMatchingService(kIOMainPortDefault, IOServiceMatching("IOPMrootDomain"))
        guard root != 0 else { return false }
        defer { IOObjectRelease(root) }
        let value = IORegistryEntryCreateCFProperty(root, "AppleClamshellState" as CFString, kCFAllocatorDefault, 0)?.takeRetainedValue()
        return value as? Bool ?? false
    }

    /// Closed, and no display on that is not the built in one.
    static var leavesNoDisplay: Bool {
        guard isClosed else { return false }
        var count: UInt32 = 0
        CGGetActiveDisplayList(0, nil, &count)
        var displays = [CGDirectDisplayID](repeating: 0, count: Int(count))
        CGGetActiveDisplayList(count, &displays, &count)
        return !displays.prefix(Int(count)).contains { CGDisplayIsBuiltin($0) == 0 }
    }
}

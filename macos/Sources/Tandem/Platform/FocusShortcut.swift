import AppKit
import Foundation

/// macOS lets an app like Tandem neither set a Focus nor read one. The Focus status API only answers apps with the Communication
/// Notifications entitlement, which needs a provisioning profile from Apple: `donotdisturbd` drops an app without it, and an app signed
/// with it but without a profile does not start. The Do Not Disturb framework itself is refused to anything that is not Apple's.
/// Shortcuts can do both, so Tandem brings a shortcut of its own, "Tandem Focus", which the person adds with one click and Tandem runs
/// with `shortcuts run`: `on` turns Do Not Disturb on, `off` turns it off, and `get` answers with the name of the Focus that is on.
enum FocusShortcut {
    static let name = "Tandem Focus"

    enum Added {
        /// Shortcuts shows it and asks the person to add it.
        case opened
        /// macOS only imports a signed shortcut, and signing needs an iCloud account on this Mac.
        case needsICloud
        case failed
    }

    static func isInstalled() async -> Bool {
        guard let list = await run(["list"]) else { return false }
        return list.split(separator: "\n").contains { $0 == name }
    }

    static func set(_ on: Bool) async -> Bool {
        await perform(on ? "on" : "off") != nil
    }

    /// The name of the Focus that is on, empty when none is, nil when the shortcut could not be run.
    static func current() async -> String? {
        await perform("get")
    }

    /// Signs the shortcut with this Mac's iCloud account (macOS imports no unsigned shortcut) and opens it, so Shortcuts asks the person
    /// to add it.
    static func add() async -> Added {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("tandem-focus-shortcut", isDirectory: true)
        try? FileManager.default.removeItem(at: folder)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let unsigned = folder.appendingPathComponent("unsigned.shortcut")
        // Shortcuts names what it imports after the file.
        let signed = folder.appendingPathComponent("\(name).shortcut")
        guard let data = try? PropertyListSerialization.data(fromPropertyList: definition(), format: .binary, options: 0),
              (try? data.write(to: unsigned)) != nil else { return .failed }
        let signing = await execute(["sign", "--mode", "people-who-know-me", "--input", unsigned.path, "--output", signed.path])
        guard signing.ok, FileManager.default.fileExists(atPath: signed.path) else {
            return signing.text.contains("iCloud") ? .needsICloud : .failed
        }
        await MainActor.run { _ = NSWorkspace.shared.open(signed) }
        return .opened
    }

    private static func perform(_ command: String) async -> String? {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("tandem-focus-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        let input = folder.appendingPathComponent("input.txt")
        let output = folder.appendingPathComponent("output.txt")
        guard (try? Data(command.utf8).write(to: input)) != nil else { return nil }
        let arguments = ["run", name, "--input-path", input.path, "--output-path", output.path, "--output-type", "public.plain-text"]
        guard await run(arguments) != nil else { return nil }
        // No Focus is no output at all.
        return (try? String(contentsOf: output, encoding: .utf8))?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    }

    private static func run(_ arguments: [String]) async -> String? {
        let result = await execute(arguments)
        return result.ok ? result.text : nil
    }

    /// A shortcut that waits for something (a question of macOS) must not hold up the next reading for ever.
    private static func execute(_ arguments: [String]) async -> (ok: Bool, text: String) {
        await Task.detached {
            let process = Process()
            process.executableURL = URL(fileURLWithPath: "/usr/bin/shortcuts")
            process.arguments = arguments
            let pipe = Pipe()
            process.standardOutput = pipe
            process.standardError = pipe
            do { try process.run() } catch { return (false, "") }
            let stop = DispatchWorkItem { if process.isRunning { process.terminate() } }
            DispatchQueue.global().asyncAfter(deadline: .now() + 20, execute: stop)
            let data = pipe.fileHandleForReading.readDataToEndOfFile()
            process.waitUntilExit()
            stop.cancel()
            return (process.terminationStatus == 0, String(decoding: data, as: UTF8.self))
        }.value
    }

    /// The shortcut: if the input is `on`, Do Not Disturb on until turned off; if it is `off`, off; otherwise the current Focus.
    private static func definition() -> [String: Any] {
        func action(_ identifier: String, _ parameters: [String: Any] = [:]) -> [String: Any] {
            var parameters = parameters
            parameters["UUID"] = UUID().uuidString
            return ["WFWorkflowActionIdentifier": identifier, "WFWorkflowActionParameters": parameters]
        }
        func condition(_ group: String, mode: Int, equals text: String? = nil) -> [String: Any] {
            var parameters: [String: Any] = ["GroupingIdentifier": group, "WFControlFlowMode": mode]
            if let text {
                parameters["WFCondition"] = 4
                parameters["WFConditionalActionString"] = text
                parameters["WFInput"] = [
                    "Type": "Variable",
                    "Variable": ["Value": ["Type": "ExtensionInput"], "WFSerializationType": "WFTextTokenAttachment"],
                ]
            }
            return action("is.workflow.actions.conditional", parameters)
        }
        func focus(_ on: Bool) -> [String: Any] {
            action("is.workflow.actions.dnd.set", [
                "Enabled": on ? 1 : 0,
                "FocusModes": ["Identifier": "com.apple.donotdisturb.mode.default", "DisplayString": "Do Not Disturb"],
                "AssertionType": "Turned Off",
            ])
        }
        let whenOn = UUID().uuidString
        let whenOff = UUID().uuidString
        // Written by the Shortcuts of this Mac, so it reads it as its own and changes nothing on import.
        let client = Bundle(path: "/System/Applications/Shortcuts.app")?.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "9999"
        return [
            "WFWorkflowClientVersion": client,
            "WFWorkflowMinimumClientVersion": 900,
            "WFWorkflowMinimumClientVersionString": "900",
            "WFWorkflowIcon": ["WFWorkflowIconStartColor": 1_440_408_063, "WFWorkflowIconGlyphNumber": 59_511],
            "WFWorkflowImportQuestions": [Any](),
            "WFWorkflowTypes": [Any](),
            "WFQuickActionSurfaces": [Any](),
            "WFWorkflowHasShortcutInputVariables": true,
            "WFWorkflowHasOutputFallback": false,
            "WFWorkflowInputContentItemClasses": ["WFStringContentItem", "WFGenericFileContentItem"],
            "WFWorkflowOutputContentItemClasses": [Any](),
            "WFWorkflowActions": [
                condition(whenOn, mode: 0, equals: "on"),
                focus(true),
                condition(whenOn, mode: 1),
                condition(whenOff, mode: 0, equals: "off"),
                focus(false),
                condition(whenOff, mode: 1),
                action("is.workflow.actions.dnd.getfocus"),
                condition(whenOff, mode: 2),
                condition(whenOn, mode: 2),
            ],
        ]
    }
}

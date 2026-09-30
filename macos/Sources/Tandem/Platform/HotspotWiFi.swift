import CoreWLAN
import Foundation

enum HotspotWiFiError: Error, LocalizedError {
    case notFound
    case joinFailed(String)
    case noRoute

    var errorDescription: String? {
        switch self {
        case .notFound: String(localized: "The hotspot is not visible yet")
        case let .joinFailed(reason): reason.isEmpty ? String(localized: "Could not join the hotspot") : reason
        case .noRoute: String(localized: "Joined the hotspot but got no connection")
        }
    }
}

/// Joins the phone's hotspot and finds the way out of it.
enum HotspotWiFi {
    static let port = 47820

    /// Joins `ssid` and returns the gateway address once the Mac has one. The phone is
    /// that gateway, which is where the core is told to look for it.
    static func join(ssid: String, password: String) async throws -> String {
        var lastError: Error = HotspotWiFiError.notFound
        // A hotspot that was just switched on takes a few seconds to show up in a scan.
        for attempt in 0 ..< 10 {
            try Task.checkCancellation()
            do {
                try await joinOnce(ssid: ssid, password: password)
                return try await waitForGateway()
            } catch is CancellationError {
                throw CancellationError()
            } catch {
                lastError = error
                if attempt < 9 { try await Task.sleep(for: .seconds(4)) }
            }
        }
        throw lastError
    }

    private static func joinOnce(ssid: String, password: String) async throws {
        // CoreWLAN first: it needs no password on a command line. It may refuse without
        // Location permission on recent macOS, and then the command line takes over.
        if await Task.detached(priority: .userInitiated, operation: { joinWithCoreWLAN(ssid: ssid, password: password) }).value {
            return
        }
        guard let interface = await wifiInterface() else { throw HotspotWiFiError.joinFailed(String(localized: "This Mac has no Wi-Fi")) }
        let result = await run("/usr/sbin/networksetup", ["-setairportnetwork", interface, ssid, password], timeout: 30)
        // networksetup exits 0 even when it fails and prints the failure instead.
        let text = result.output.trimmingCharacters(in: .whitespacesAndNewlines)
        if result.status != 0 || text.contains("Failed") || text.contains("Could not") || text.contains("Error") {
            throw text.contains("find") ? HotspotWiFiError.notFound : HotspotWiFiError.joinFailed("")
        }
    }

    private static func joinWithCoreWLAN(ssid: String, password: String) -> Bool {
        guard let interface = CWWiFiClient.shared().interface() else { return false }
        do {
            let networks = try interface.scanForNetworks(withName: ssid)
            guard let network = networks.first else { return false }
            try interface.associate(to: network, password: password)
            return true
        } catch {
            return false
        }
    }

    /// The default route, once DHCP has given the Mac one on the Wi-Fi interface.
    private static func waitForGateway() async throws -> String {
        for _ in 0 ..< 20 {
            try Task.checkCancellation()
            if let route = await defaultRoute(), route.gateway.contains(".") {
                return route.gateway
            }
            try await Task.sleep(for: .seconds(1))
        }
        throw HotspotWiFiError.noRoute
    }

    static func defaultRoute() async -> (gateway: String, interface: String)? {
        let result = await run("/sbin/route", ["-n", "get", "default"], timeout: 5)
        var gateway: String?
        var interface: String?
        for line in result.output.split(separator: "\n") {
            let parts = line.split(separator: ":", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
            guard parts.count == 2 else { continue }
            if parts[0] == "gateway" { gateway = parts[1] }
            if parts[0] == "interface" { interface = parts[1] }
        }
        guard let gateway, let interface else { return nil }
        return (gateway, interface)
    }

    /// `en0` on most Macs, found from the hardware ports rather than assumed.
    static func wifiInterface() async -> String? {
        if let name = CWWiFiClient.shared().interface()?.interfaceName { return name }
        let result = await run("/usr/sbin/networksetup", ["-listallhardwareports"], timeout: 5)
        var isWiFi = false
        for line in result.output.split(separator: "\n") {
            if line.hasPrefix("Hardware Port:") { isWiFi = line.contains("Wi-Fi") || line.contains("AirPort") }
            if isWiFi, line.hasPrefix("Device:") {
                return line.dropFirst("Device:".count).trimmingCharacters(in: .whitespaces)
            }
        }
        return nil
    }

    // MARK: Running tools

    private static func run(_ path: String, _ arguments: [String], timeout: Double) async -> (status: Int32, output: String) {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                let process = Process()
                process.executableURL = URL(fileURLWithPath: path)
                process.arguments = arguments
                let pipe = Pipe()
                process.standardOutput = pipe
                process.standardError = pipe
                do {
                    try process.run()
                } catch {
                    continuation.resume(returning: (-1, ""))
                    return
                }
                let killer = DispatchWorkItem { if process.isRunning { process.terminate() } }
                DispatchQueue.global().asyncAfter(deadline: .now() + timeout, execute: killer)
                let data = pipe.fileHandleForReading.readDataToEndOfFile()
                process.waitUntilExit()
                killer.cancel()
                continuation.resume(returning: (process.terminationStatus, String(decoding: data, as: UTF8.self)))
            }
        }
    }
}

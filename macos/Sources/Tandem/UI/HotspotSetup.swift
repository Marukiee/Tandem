import AppKit
import CoreBluetooth
import CoreLocation
import CoreWLAN
import SwiftUI

/// Asking for Bluetooth: the system dialog appears the first time a manager is created.
enum BluetoothPrompt {
    private static var manager: CBCentralManager?

    static func ask() {
        manager = CBCentralManager(delegate: nil, queue: nil)
    }

    static func openSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_Bluetooth") {
            NSWorkspace.shared.open(url)
        }
    }
}

/// macOS hides the name of the Wi-Fi network from apps that may not see the location, so the
/// setup asks for it once. The position itself is never read.
enum LocationAccess {
    private static var manager: CLLocationManager?

    static var status: CLAuthorizationStatus { CLLocationManager().authorizationStatus }
    static var allowed: Bool { status == .authorizedAlways || status == .authorized }

    static func ask() {
        let created = CLLocationManager()
        manager = created
        created.requestWhenInUseAuthorization()
    }

    static func openSettings() {
        if let url = URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_LocationServices") {
            NSWorkspace.shared.open(url)
        }
    }
}

/// What the Mac is joined to right now, and the password it stored for it, so the phone's
/// hotspot does not have to be typed in by hand.
enum CurrentWiFi {
    static func ssid() -> String? {
        if let name = CWWiFiClient.shared().interface()?.ssid(), !name.isEmpty { return name }
        // Without Location access CoreWLAN hides the name; the command line tool still knows it.
        guard let device = wifiDevice() else { return nil }
        let text = run("/usr/sbin/networksetup", ["-getairportnetwork", device]) ?? ""
        guard let range = text.range(of: ": ") else { return nil }
        let name = String(text[range.upperBound...]).trimmingCharacters(in: .whitespacesAndNewlines)
        return name.isEmpty ? nil : name
    }

    /// The password macOS saved for a network. macOS asks for the login password first, once.
    static func password(for ssid: String) -> String? {
        let text = run("/usr/bin/security", ["find-generic-password", "-D", "AirPort network password", "-a", ssid, "-w"])
        let trimmed = text?.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed?.isEmpty == false ? trimmed : nil
    }

    private static func wifiDevice() -> String? {
        guard let text = run("/usr/sbin/networksetup", ["-listallhardwareports"]) else { return nil }
        let lines = text.components(separatedBy: "\n")
        for (index, line) in lines.enumerated() where line.contains("Hardware Port: Wi-Fi") || line.contains("Hardware Port: AirPort") {
            if index + 1 < lines.count, let range = lines[index + 1].range(of: "Device: ") {
                return String(lines[index + 1][range.upperBound...]).trimmingCharacters(in: .whitespaces)
            }
        }
        return nil
    }

    private static func run(_ tool: String, _ arguments: [String]) -> String? {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: tool)
        process.arguments = arguments
        let pipe = Pipe()
        process.standardOutput = pipe
        process.standardError = Pipe()
        guard (try? process.run()) != nil else { return nil }
        let data = pipe.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        return process.terminationStatus == 0 ? String(decoding: data, as: UTF8.self) : nil
    }
}

/// A one-time walk through everything the automatic hotspot needs: join the phone's hotspot
/// once so its name and password can be read from this Mac, and allow Bluetooth.
struct HotspotSetupSheet: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage("hotspotSSID") private var ssid = ""
    @AppStorage(HotspotCoordinator.enabledKey) private var auto = false
    @LocalState private var step = 0
    @LocalState private var current: String?
    @LocalState private var password = HotspotCredentials.password() ?? ""
    @LocalState private var reading = false
    @LocalState private var readFailed = false
    @LocalState private var bluetooth = CBManager.authorization
    @LocalState private var location = LocationAccess.status
    @LocalState private var typeIt = false

    private let steps = 4
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack {
                Text("Set up the hotspot").font(.title2.weight(.bold))
                Spacer()
                Text("Step \(step + 1) of \(steps)").font(.callout).foregroundStyle(.secondary)
            }

            Group {
                switch step {
                case 0: connectStep
                case 1: credentialsStep
                case 2: bluetoothStep
                default: doneStep
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .transition(.opacity.combined(with: .offset(y: 8)))
            .id(step)

            Spacer(minLength: 0)

            HStack {
                Button("Cancel", role: .cancel) { dismiss() }
                Spacer()
                if step > 0 && step < steps - 1 {
                    Button("Back") { withAnimation(.tandem) { step -= 1 } }
                }
                Button(step == steps - 1 ? "Done" : "Next") {
                    if step == steps - 1 { auto = true; dismiss() } else { withAnimation(.tandem) { step += 1 } }
                }
                .buttonStyle(.glassProminent)
                .tint(Palette.indigo)
                .keyboardShortcut(.defaultAction)
                .disabled(!canContinue)
            }
        }
        .padding(28)
        .frame(width: 500, height: 380)
        .onReceive(timer) { _ in
            bluetooth = CBManager.authorization
            location = LocationAccess.status
            if step == 0 { Task.detached { let name = CurrentWiFi.ssid(); await MainActor.run { current = name } } }
        }
        .onAppear { Task.detached { let name = CurrentWiFi.ssid(); await MainActor.run { current = name } } }
    }

    private var canContinue: Bool {
        switch step {
        case 0: current != nil || typeIt
        case 1: !ssid.isEmpty && !password.isEmpty
        case 2: bluetooth == .allowedAlways
        default: true
        }
    }

    // MARK: Steps

    private var connectStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Join your phone's hotspot").font(.headline)
            Text("Turn on the hotspot on your phone, then choose it from the Wi-Fi menu on this Mac. You only do this once.")
                .foregroundStyle(.secondary)
            HStack(spacing: 10) {
                Image(systemName: current == nil ? "wifi.slash" : "wifi")
                    .foregroundStyle(current == nil ? Color.secondary : Palette.indigo)
                if let current {
                    Text("Connected to \(current)").fontWeight(.medium)
                } else {
                    Text("Waiting for a Wi-Fi network").foregroundStyle(.secondary)
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 12, style: .continuous))

            if current == nil {
                // Joined but not shown: macOS only names the network to apps that may see the location.
                if !LocationAccess.allowed {
                    Text("macOS only tells Tandem the name of your network if it may see your location. Your position is never read.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                    HStack {
                        Button(location == .notDetermined ? "Allow" : "Open Settings") {
                            if location == .notDetermined { LocationAccess.ask() } else { LocationAccess.openSettings() }
                        }
                        Button("I'll type it myself") { typeIt = true; withAnimation(.tandem) { step = 1 } }
                            .buttonStyle(.plain)
                            .foregroundStyle(.secondary)
                    }
                } else {
                    Button("I'll type it myself") { typeIt = true; withAnimation(.tandem) { step = 1 } }
                        .buttonStyle(.plain)
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var credentialsStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Use this network").font(.headline)
            Text("Tandem remembers the name and password of the hotspot, privately on this Mac, so it can join again by itself.")
                .foregroundStyle(.secondary)
            Button {
                useCurrentNetwork()
            } label: {
                if reading { HStack(spacing: 8) { PillSpinner(size: 16); Text("Reading the password…") } }
                else { Label("Use \(current ?? ssid)", systemImage: "wifi") }
            }
            .buttonStyle(.glass)
            .disabled(reading || (current ?? ssid).isEmpty)
            if readFailed {
                Text("macOS did not hand over the password. Type it below.").font(.callout).foregroundStyle(.secondary)
            }
            TextField("Network name", text: $ssid)
            SecureField("Password", text: $password)
                .onChange(of: password) { _, value in HotspotCredentials.save(password: value) }
        }
    }

    private var bluetoothStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Allow Bluetooth").font(.headline)
            Text("When this Mac has no internet it cannot reach your phone over the network, so it asks over Bluetooth to turn the hotspot on.")
                .foregroundStyle(.secondary)
            HStack(spacing: 10) {
                Image(systemName: bluetooth == .allowedAlways ? "checkmark.circle.fill" : "circle.dashed")
                    .foregroundStyle(bluetooth == .allowedAlways ? Color.green : Color.secondary)
                Text(bluetooth == .allowedAlways ? "Bluetooth is allowed" : "Bluetooth is not allowed yet")
                Spacer()
                if bluetooth != .allowedAlways {
                    Button(bluetooth == .notDetermined ? "Allow" : "Open Settings") {
                        if bluetooth == .notDetermined { BluetoothPrompt.ask() } else { BluetoothPrompt.openSettings() }
                    }
                }
            }
            .padding(12)
            .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            Text("On your phone, open Tandem, Settings, Hotspot and turn on \"Let my Mac use my hotspot\".")
                .font(.callout)
                .foregroundStyle(.secondary)
        }
    }

    private var doneStep: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("All set").font(.headline)
            Text("From now on, when this Mac has no connection it asks your phone for its hotspot and joins it. You get a notification each time it does.")
                .foregroundStyle(.secondary)
        }
    }

    private func useCurrentNetwork() {
        guard let name = current ?? (ssid.isEmpty ? nil : ssid) else { return }
        ssid = name
        reading = true
        readFailed = false
        Task.detached {
            let found = CurrentWiFi.password(for: name)
            await MainActor.run {
                reading = false
                if let found {
                    password = found
                    HotspotCredentials.save(password: found)
                } else {
                    readFailed = true
                }
            }
        }
    }
}

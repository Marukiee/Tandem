import AppKit
import CoreAudio
import SwiftUI
import TandemCore

/// The sound that belongs to a screen that is looked at from this Mac. It is asked for (`requestSound`) when the person wants it here,
/// or when headphones are in and the person says yes this time; the device whose screen it is sends it as a phone sends its sound, and
/// `PhoneSound` plays it. The other way round, a device that looks at this Mac's screen can ask for its sound: see `EngineModel`.
@MainActor
enum ScreenSound {
    static let whereKey = "screenSound"
    static let askKey = "screenSoundAsk"

    /// "there" (the default) or "here".
    static var choice: String { UserDefaults.standard.string(forKey: whereKey) ?? "there" }
    static var askWithHeadphones: Bool { UserDefaults.standard.object(forKey: askKey) as? Bool ?? true }

    /// The devices whose sound this Mac asked for, because it looks at their screen.
    private(set) static var asked: Set<String> = []

    static func viewStarted(_ peer: String) {
        if choice == "here" {
            ask(peer, on: true)
        } else if askWithHeadphones, Headphones.connected, let device = EngineModel.shared.device(peer) {
            SoundPrompt.shared.ask(device)
        }
    }

    static func viewEnded(_ peer: String) {
        guard asked.contains(peer) else { return }
        ask(peer, on: false)
        PhoneSound.shared.stop(device: peer)
    }

    static func ask(_ peer: String, on: Bool) {
        if on { asked.insert(peer) } else { asked.remove(peer) }
        guard let engine = EngineModel.shared.tandem else { return }
        Task { try? await engine.requestSound(target: peer, on: on) }
    }
}

/// Whether what this Mac plays goes to headphones now: a Bluetooth device, or the headphone jack of the Mac itself.
enum Headphones {
    static var connected: Bool {
        var device = AudioDeviceID(0)
        var size = UInt32(MemoryLayout<AudioDeviceID>.size)
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDefaultOutputDevice, mScope: kAudioObjectPropertyScopeGlobal, mElement: kAudioObjectPropertyElementMain
        )
        guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &device) == noErr else { return false }
        var transport: UInt32 = 0
        size = UInt32(MemoryLayout<UInt32>.size)
        address.mSelector = kAudioDevicePropertyTransportType
        if AudioObjectGetPropertyData(device, &address, 0, nil, &size, &transport) == noErr,
           transport == kAudioDeviceTransportTypeBluetooth || transport == kAudioDeviceTransportTypeBluetoothLE {
            return true
        }
        // The built-in output says which way it plays: 'hdpn' is the jack.
        var source: UInt32 = 0
        size = UInt32(MemoryLayout<UInt32>.size)
        address = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyDataSource, mScope: kAudioDevicePropertyScopeOutput, mElement: kAudioObjectPropertyElementMain)
        return AudioObjectGetPropertyData(device, &address, 0, nil, &size, &source) == noErr && source == 0x6864_706E
    }
}

/// The question when headphones are in and a screen of another device opens here: play its sound here this time, always, or not.
@MainActor
final class SoundPrompt {
    static let shared = SoundPrompt()
    private var panel: NSPanel?

    func ask(_ device: TandemDevice) {
        close()
        let panel = KeyPanel(
            contentRect: NSRect(x: 0, y: 0, width: 420, height: 160),
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        panel.isOpaque = false
        panel.backgroundColor = .clear
        panel.hasShadow = false
        panel.level = .statusBar
        panel.isReleasedWhenClosed = false
        panel.hidesOnDeactivate = false
        panel.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        let view = NSHostingView(rootView: SoundPromptView(device: device, close: { [weak self] in self?.close() }))
        panel.contentView = view
        panel.setContentSize(view.fittingSize)
        if let screen = NSScreen.main {
            let area = screen.visibleFrame
            let top = ScreenPanels.shared.lowestEdge.map { $0 + 30 } ?? area.maxY - 10
            panel.setFrameOrigin(NSPoint(x: area.midX - panel.frame.width / 2, y: top - panel.frame.height))
        }
        panel.makeKeyAndOrderFront(nil)
        self.panel = panel
        // Nobody answering is a no: the sound stays where it is.
        Task { @MainActor [weak self, weak panel] in
            try? await Task.sleep(for: .seconds(30))
            if let panel, self?.panel === panel { self?.close() }
        }
    }

    func close() {
        panel?.orderOut(nil)
        panel = nil
    }
}

private struct SoundPromptView: View {
    let device: TandemDevice
    let close: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            PromptHeader(
                symbol: "headphones",
                title: Text("Play the sound of \(device.name) on your headphones?"),
                message: Text("Your headphones are in. The sound can play here instead of there.")
            )
            // The answer that is not a yes at the left, the main answer at the right, as everywhere on the Mac.
            HStack(spacing: 8) {
                Button("Leave it there", action: close).buttonStyle(.tandemGlass)
                Spacer(minLength: 0)
                Button("Always here") {
                    UserDefaults.standard.set("here", forKey: ScreenSound.whereKey)
                    ScreenSound.ask(device.id, on: true)
                    close()
                }
                .buttonStyle(.tandemGlass)
                Button("Play here") {
                    ScreenSound.ask(device.id, on: true)
                    close()
                }
                .buttonStyle(.tandemGlassProminent)
            }
            .controlSize(.large)
        }
        .floatingCard(width: 420)
    }
}

import CoreAudio
import Foundation
import TandemCore

/// Music and podcasts stop while a call goes on, and go on again after it: on this Mac when the phone rings or is in a call, and on
/// the phones when this Mac is in a call (another app has had the microphone for a few seconds: FaceTime, Zoom, Teams). It pauses
/// once, when the call begins: music the person starts again during the call keeps playing. After it, only what was paused here
/// and is still paused plays again, so nothing starts that the person stopped. The setting "Pause music during calls" in General,
/// on unless turned off.
@MainActor
final class CallPause {
    static let shared = CallPause()
    static let enabledKey = "pauseDuringCalls"

    private var enabled: Bool { UserDefaults.standard.object(forKey: Self.enabledKey) as? Bool ?? true }
    /// Calls going on, on phones (by device) and on this Mac.
    private var phoneCalls: Set<String> = []
    private var macCall = false
    /// Paused for the call that goes on now: a second look during the same call pauses nothing again.
    private var pausedHereForCall = false
    private var pausedPhonesForCall = false
    /// What was paused here, and on which phones, to start again when every call is over.
    private var pausedHere: [String] = []
    private var pausedThere: [(device: String, player: TandemMediaPlayer)] = []
    private var watch: Task<Void, Never>?
    /// Since when another app has the microphone; a few seconds count as a call, a moment does not (a dictation, a check of the side).
    private var micSince: Date?

    private var model: EngineModel { EngineModel.shared }

    func start() {
        guard watch == nil else { return }
        watch = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2))
                self?.lookAtMicrophone()
            }
        }
    }

    /// A call of a phone changed state.
    func call(_ state: TandemCallState, on device: String) {
        switch state {
        case .ringing, .dialing, .active: phoneCalls.insert(device)
        case .ended, .missed: phoneCalls.remove(device)
        }
        update()
    }

    private func lookAtMicrophone() {
        let inUse = AudioRoute.microphoneInUse()
        if inUse { micSince = micSince ?? Date() } else { micSince = nil }
        let call = micSince.map { Date().timeIntervalSince($0) >= 5 } ?? false
        guard call != macCall else { return }
        macCall = call
        update()
    }

    private func update() {
        let inCall = !phoneCalls.isEmpty || macCall
        guard inCall else { return resume() }
        guard enabled else { return }
        if !pausedHereForCall {
            pausedHereForCall = true
            pauseHere()
        }
        if macCall, !pausedPhonesForCall {
            pausedPhonesForCall = true
            pausePhones()
        }
    }

    private func pauseHere() {
        for player in model.macMedia.players where player.playing && !pausedHere.contains(player.id) {
            pausedHere.append(player.id)
            model.macMedia.perform(.pause, player: player.id, positionMs: nil) { _ in }
        }
    }

    /// What plays on the phones stops for a call on this Mac. A phone in a call of its own pauses its music by itself.
    private func pausePhones() {
        for (device, remote) in model.remoteMedia {
            for player in remote.players where player.playing && !pausedThere.contains(where: { $0.device == device && $0.player.id == player.id }) {
                pausedThere.append((device, player))
                model.sendMedia(.pause, player: player, to: device)
            }
        }
    }

    /// After the call: what was paused for it and is still paused plays again. What the person started or stopped meanwhile is theirs.
    private func resume() {
        for id in pausedHere where model.macMedia.players.contains(where: { $0.id == id && !$0.playing }) {
            model.macMedia.perform(.play, player: id, positionMs: nil) { _ in }
        }
        for (device, player) in pausedThere where model.remoteMedia[device]?.players.contains(where: { $0.id == player.id && !$0.playing }) == true {
            model.sendMedia(.play, player: player, to: device)
        }
        pausedHere = []
        pausedThere = []
        pausedHereForCall = false
        pausedPhonesForCall = false
    }
}

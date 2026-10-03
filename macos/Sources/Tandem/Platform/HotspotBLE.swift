import CoreBluetooth
import Foundation
import TandemCore

/// What the phone says about its hotspot over Bluetooth. Keep in step with
/// docs/HOTSPOT.md and BleState.kt.
enum HotspotBleState: UInt8 {
    case off = 0
    case starting = 1
    case on = 2
    /// The phone cannot start it alone: someone has to tap a notification.
    case manual = 3
    case failed = 4
    case refusedAuth = 5
    case refusedBattery = 6
    case refusedRoaming = 7
    /// The person switched hotspot sharing off on the phone.
    case disabled = 8
    /// What a Mac may use in a day over the hotspot has been used.
    case refusedLimit = 9
}

enum HotspotBleError: Error, LocalizedError {
    case busy
    case bluetoothOff
    case bluetoothDenied
    case bluetoothUnsupported
    case phoneNotFound
    case connectFailed
    case lost
    case protocolError

    var errorDescription: String? {
        switch self {
        case .busy: String(localized: "Already asking your phone")
        case .bluetoothOff: String(localized: "Turn on Bluetooth to ask your phone for its hotspot")
        case .bluetoothDenied: String(localized: "Tandem may not use Bluetooth. Allow it in System Settings under Privacy and Security")
        case .bluetoothUnsupported: String(localized: "This Mac cannot use Bluetooth Low Energy")
        case .phoneNotFound: String(localized: "Could not find your phone over Bluetooth. Is it nearby and is hotspot sharing on?")
        case .connectFailed: String(localized: "Could not connect to your phone over Bluetooth")
        case .lost: String(localized: "The Bluetooth connection to your phone dropped")
        case .protocolError: String(localized: "Your phone did not answer as expected")
        }
    }
}

/// Asks a paired phone to turn its hotspot on or off over Bluetooth Low Energy, for
/// when this Mac has no network to reach it on. The exchange is in docs/HOTSPOT.md:
/// find the phone by its rotating tag, read a challenge, sign it with the device key,
/// write the request, then follow the state notifications.
///
/// One request at a time. Everything runs on its own serial queue.
final class BleHotspotClient: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, @unchecked Sendable {
    static let service = CBUUID(string: "6F2D7A10-8B1C-4E6F-A3D5-1C9E5B7F2A40")
    static let challengeUUID = CBUUID(string: "6F2D7A11-8B1C-4E6F-A3D5-1C9E5B7F2A40")
    static let requestUUID = CBUUID(string: "6F2D7A12-8B1C-4E6F-A3D5-1C9E5B7F2A40")
    static let stateUUID = CBUUID(string: "6F2D7A13-8B1C-4E6F-A3D5-1C9E5B7F2A40")

    static let actionOn: UInt8 = 1
    static let actionOff: UInt8 = 2

    private enum Step { case idle, waitingForPower, scanning, connecting, discovering, subscribing, readingChallenge, writing, waitingForState }

    private let queue = DispatchQueue(label: "tandem.hotspot.ble")
    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var fallback: CBPeripheral?
    private var challengeCharacteristic: CBCharacteristic?
    private var requestCharacteristic: CBCharacteristic?
    private var stateCharacteristic: CBCharacteristic?
    private var step = Step.idle
    private var continuation: CheckedContinuation<HotspotBleState, Error>?
    private var timer: DispatchWorkItem?
    private var fallbackTimer: DispatchWorkItem?

    private var action: UInt8 = 1
    private var myId = ""
    private var engine: TandemEngine?
    private var hints: [Data] = []
    private var progress: (@Sendable (HotspotBleState) -> Void)?
    private var lastState: HotspotBleState?

    /// Runs one request and returns the last state the phone reported. Throws when the
    /// phone could not be reached at all. A phone that says no (battery, roaming, not
    /// allowed) is a returned state, not an error.
    func run(
        action: UInt8,
        phoneId: String,
        engine: TandemEngine,
        progress: @escaping @Sendable (HotspotBleState) -> Void
    ) async throws -> HotspotBleState {
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                queue.async {
                    guard self.continuation == nil else {
                        continuation.resume(throwing: HotspotBleError.busy)
                        return
                    }
                    self.continuation = continuation
                    self.action = action
                    self.engine = engine
                    self.myId = engine.id()
                    self.progress = progress
                    self.lastState = nil
                    self.step = .waitingForPower
                    // A phone's tag is per hour, so the neighbouring hours cover a clock
                    // that is a little off and the moment the hour turns.
                    let hour = UInt64(Date().timeIntervalSince1970 / 3600)
                    self.hints = [hour &- 1, hour, hour &+ 1].map { tandemBleHint(deviceId: phoneId, hour: $0) }
                    // Making the manager is what asks for Bluetooth permission the first time.
                    self.central = CBCentralManager(
                        delegate: self,
                        queue: self.queue,
                        options: [CBCentralManagerOptionShowPowerAlertKey: false]
                    )
                    self.arm(seconds: 8) { self.finish(.failure(HotspotBleError.bluetoothOff)) }
                }
            }
        } onCancel: {
            queue.async { self.finish(.failure(CancellationError())) }
        }
    }

    // MARK: Timers and finishing

    private func arm(seconds: Double, _ action: @escaping () -> Void) {
        timer?.cancel()
        let item = DispatchWorkItem(block: action)
        timer = item
        queue.asyncAfter(deadline: .now() + seconds, execute: item)
    }

    private func finish(_ result: Result<HotspotBleState, Error>) {
        guard let continuation else { return }
        self.continuation = nil
        timer?.cancel()
        fallbackTimer?.cancel()
        central?.stopScan()
        if let peripheral, let central, peripheral.state != .disconnected {
            central.cancelPeripheralConnection(peripheral)
        }
        peripheral = nil
        fallback = nil
        challengeCharacteristic = nil
        requestCharacteristic = nil
        stateCharacteristic = nil
        central?.delegate = nil
        central = nil
        step = .idle
        progress = nil
        engine = nil
        continuation.resume(with: result)
    }

    // MARK: Central

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        guard continuation != nil else { return }
        switch central.state {
        case .poweredOn:
            if step == .waitingForPower { startScan() }
        case .poweredOff:
            finish(.failure(HotspotBleError.bluetoothOff))
        case .unauthorized:
            finish(.failure(HotspotBleError.bluetoothDenied))
        case .unsupported:
            finish(.failure(HotspotBleError.bluetoothUnsupported))
        default:
            break
        }
    }

    private func startScan() {
        step = .scanning
        central?.scanForPeripherals(withServices: [Self.service], options: nil)
        arm(seconds: 15) { self.finish(.failure(HotspotBleError.phoneNotFound)) }
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        guard step == .scanning else { return }
        let tags = advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data]
        if let tag = tags?[Self.service] {
            // Someone else's phone advertises a different tag.
            if hints.contains(tag) { connect(peripheral) }
        } else if fallback == nil {
            // The tag rides in the scan response, which not every scan delivers. Wait a
            // moment for a phone that shows its tag, then settle for the one without.
            fallback = peripheral
            let item = DispatchWorkItem { [weak self] in
                guard let self, self.step == .scanning, let candidate = self.fallback else { return }
                self.connect(candidate)
            }
            fallbackTimer = item
            queue.asyncAfter(deadline: .now() + 3, execute: item)
        }
    }

    private func connect(_ target: CBPeripheral) {
        guard step == .scanning else { return }
        fallbackTimer?.cancel()
        central?.stopScan()
        peripheral = target
        target.delegate = self
        step = .connecting
        central?.connect(target, options: nil)
        arm(seconds: 12) { self.finish(.failure(HotspotBleError.connectFailed)) }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard step == .connecting else { return }
        step = .discovering
        peripheral.discoverServices([Self.service])
        arm(seconds: 10) { self.finish(.failure(HotspotBleError.protocolError)) }
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        finish(.failure(HotspotBleError.connectFailed))
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        guard continuation != nil else { return }
        // Dropping after the answer is in hand is fine.
        if let lastState, lastState != .starting, lastState != .manual {
            finish(.success(lastState))
        } else {
            finish(.failure(HotspotBleError.lost))
        }
    }

    // MARK: Peripheral

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == Self.service }) else {
            finish(.failure(HotspotBleError.protocolError))
            return
        }
        peripheral.discoverCharacteristics([Self.challengeUUID, Self.requestUUID, Self.stateUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil else {
            finish(.failure(HotspotBleError.protocolError))
            return
        }
        for characteristic in service.characteristics ?? [] {
            switch characteristic.uuid {
            case Self.challengeUUID: challengeCharacteristic = characteristic
            case Self.requestUUID: requestCharacteristic = characteristic
            case Self.stateUUID: stateCharacteristic = characteristic
            default: break
            }
        }
        guard let state = stateCharacteristic, challengeCharacteristic != nil, requestCharacteristic != nil else {
            finish(.failure(HotspotBleError.protocolError))
            return
        }
        // Listening comes first: the answer may follow the write within milliseconds.
        step = .subscribing
        peripheral.setNotifyValue(true, for: state)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard step == .subscribing else { return }
        guard error == nil, let challenge = challengeCharacteristic else {
            finish(.failure(HotspotBleError.protocolError))
            return
        }
        step = .readingChallenge
        peripheral.readValue(for: challenge)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        if characteristic.uuid == Self.challengeUUID, step == .readingChallenge {
            guard error == nil, let challenge = characteristic.value, challenge.count == 16 else {
                finish(.failure(HotspotBleError.protocolError))
                return
            }
            sendRequest(challenge: challenge, to: peripheral)
        } else if characteristic.uuid == Self.stateUUID, error == nil, let value = characteristic.value {
            handleState(value)
        }
    }

    private func sendRequest(challenge: Data, to peripheral: CBPeripheral) {
        guard let engine, let request = requestCharacteristic else {
            finish(.failure(HotspotBleError.protocolError))
            return
        }
        let timestamp = UInt64(Date().timeIntervalSince1970 * 1000)
        let message = tandemHotspotAuthMessage(challenge: challenge, deviceId: myId, action: action, timestampMs: timestamp)
        let signature = engine.signMessage(message: message)

        var payload = Data([1, action])
        for shift in stride(from: 56, through: 0, by: -8) { payload.append(UInt8((timestamp >> UInt64(shift)) & 0xFF)) }
        let id = Data(myId.utf8)
        payload.append(UInt8(id.count))
        payload.append(id)
        payload.append(signature)

        step = .writing
        peripheral.writeValue(payload, for: request, type: .withResponse)
        arm(seconds: 10) { self.finish(.failure(HotspotBleError.protocolError)) }
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard step == .writing else { return }
        if error != nil {
            // The phone answers a request it does not trust with an ATT error.
            finish(.success(.refusedAuth))
            return
        }
        step = .waitingForState
        arm(seconds: 20) { self.finish(.success(self.lastState ?? .failed)) }
    }

    private func handleState(_ data: Data) {
        // Anything before the request was written is the phone's old news.
        guard step == .writing || step == .waitingForState, let first = data.first,
              let state = HotspotBleState(rawValue: first)
        else { return }
        lastState = state
        progress?(state)
        switch state {
        case .on:
            finish(.success(.on))
        case .off:
            // For an "on" request an off is the state from before; keep waiting.
            if action == Self.actionOff { finish(.success(.off)) }
        case .starting:
            arm(seconds: 45) { self.finish(.success(self.lastState ?? .failed)) }
        case .manual:
            // Someone has to pick up the phone. Give them a few minutes.
            arm(seconds: 200) { self.finish(.success(.manual)) }
        case .failed, .refusedAuth, .refusedBattery, .refusedRoaming, .refusedLimit, .disabled:
            finish(.success(state))
        }
    }
}

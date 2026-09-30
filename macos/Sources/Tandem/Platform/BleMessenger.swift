import CoreBluetooth
import Foundation
import TandemCore

/// Clipboard and notifications over a Bluetooth link to the phone, for when this Mac has no
/// network to reach it on. The link is only kept while the phone is out of reach over the
/// network, and only to a phone this Mac already shares a Bluetooth key with, which it gets
/// the first time they connect over the network.
///
/// This only moves writes. The core seals, cuts into pieces and puts them back together, and
/// a message that arrives is handled like one from the network, so nothing above knows.
/// Everything runs on one serial queue; what arrives is handed to the core in order.
final class BleMessenger: NSObject, CBCentralManagerDelegate, CBPeripheralDelegate, @unchecked Sendable {
    static let service = CBUUID(string: "6F2D7A10-8B1C-4E6F-A3D5-1C9E5B7F2A40")
    static let inUUID = CBUUID(string: "6F2D7A14-8B1C-4E6F-A3D5-1C9E5B7F2A40")
    static let outUUID = CBUUID(string: "6F2D7A15-8B1C-4E6F-A3D5-1C9E5B7F2A40")

    private let queue = DispatchQueue(label: "tandem.ble.messages")
    private var central: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var writeCharacteristic: CBCharacteristic?
    private var engine: TandemEngine?
    private var phoneId: String?
    private var hints: [Data] = []
    private var ready = false
    private var pump: DispatchSourceTimer?
    private var reconnect: DispatchWorkItem?
    private var incoming: AsyncStream<(String, Data)>.Continuation?

    /// Keeps a link to this phone up, or drops it when `phoneId` is nil. Safe to call often.
    func want(phoneId: String?, engine: TandemEngine) {
        queue.async {
            guard self.phoneId != phoneId else { return }
            self.teardown()
            self.phoneId = phoneId
            self.engine = engine
            guard let phoneId else { return }
            // A phone's tag is per hour, so the neighbouring hours cover a clock a little off.
            let hour = UInt64(Date().timeIntervalSince1970 / 3600)
            self.hints = [hour &- 1, hour, hour &+ 1].map { tandemBleHint(deviceId: phoneId, hour: $0) }
            self.startReader(engine: engine)
            self.central = CBCentralManager(delegate: self, queue: self.queue, options: [CBCentralManagerOptionShowPowerAlertKey: false])
        }
    }

    // MARK: Lifecycle

    private func startReader(engine: TandemEngine) {
        let (stream, continuation) = AsyncStream<(String, Data)>.makeStream()
        incoming = continuation
        // One reader, so the pieces of a message reach the core in the order they arrived.
        Task.detached {
            for await (link, chunk) in stream { _ = try? await engine.bleReceive(link: link, chunk: chunk) }
        }
    }

    private func teardown() {
        reconnect?.cancel()
        pump?.cancel()
        pump = nil
        if let peripheral, let central, peripheral.state != .disconnected { central.cancelPeripheralConnection(peripheral) }
        central?.stopScan()
        central?.delegate = nil
        central = nil
        if let peripheral { engine?.bleDropLink(link: peripheral.identifier.uuidString) }
        if let phoneId { engine?.bleLinkDown(id: phoneId) }
        peripheral = nil
        writeCharacteristic = nil
        ready = false
        incoming?.finish()
        incoming = nil
        phoneId = nil
    }

    // MARK: Central

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn { scan() }
    }

    private func scan() {
        guard phoneId != nil, peripheral == nil else { return }
        central?.scanForPeripherals(withServices: [Self.service], options: nil)
    }

    func centralManager(_ central: CBCentralManager, didDiscover found: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard peripheral == nil else { return }
        // Someone else's phone advertises a different tag. A phone whose scan response did not
        // arrive shows no tag at all; it is not taken here, the person's own is worth waiting for.
        guard let tag = (advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data])?[Self.service], hints.contains(tag) else { return }
        central.stopScan()
        peripheral = found
        found.delegate = self
        central.connect(found, options: nil)
    }

    func centralManager(_ central: CBCentralManager, didConnect connected: CBPeripheral) {
        connected.discoverServices([Self.service])
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect failed: CBPeripheral, error: Error?) {
        lost()
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral gone: CBPeripheral, error: Error?) {
        lost()
    }

    /// The link is gone. Tell the core, and try again shortly if the phone is still wanted.
    private func lost() {
        pump?.cancel()
        pump = nil
        if let peripheral { engine?.bleDropLink(link: peripheral.identifier.uuidString) }
        if let phoneId { engine?.bleLinkDown(id: phoneId) }
        peripheral = nil
        writeCharacteristic = nil
        ready = false
        guard phoneId != nil else { return }
        let item = DispatchWorkItem { [weak self] in self?.scan() }
        reconnect = item
        queue.asyncAfter(deadline: .now() + 5, execute: item)
    }

    // MARK: Peripheral

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == Self.service }) else { return lost() }
        peripheral.discoverCharacteristics([Self.inUUID, Self.outUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil else { return lost() }
        var out: CBCharacteristic?
        for characteristic in service.characteristics ?? [] {
            if characteristic.uuid == Self.inUUID { writeCharacteristic = characteristic }
            if characteristic.uuid == Self.outUUID { out = characteristic }
        }
        // An older phone app has neither: nothing to do, and no reason to keep trying hard.
        guard let out, writeCharacteristic != nil else { return lost() }
        peripheral.setNotifyValue(true, for: out)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, characteristic.uuid == Self.outUUID, characteristic.isNotifying else { return }
        ready = true
        // The first frame says who this is, so the phone can tell which device the link belongs to.
        if let phoneId, let engine { write(engine.bleHello(id: phoneId, chunkSize: UInt32(chunkSize(for: peripheral)))) }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 0.4, repeating: 0.4)
        timer.setEventHandler { [weak self] in self?.flush() }
        timer.resume()
        pump = timer
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, characteristic.uuid == Self.outUUID, let value = characteristic.value else { return }
        incoming?.yield((peripheral.identifier.uuidString, value))
    }

    // MARK: Writing

    private func chunkSize(for peripheral: CBPeripheral) -> Int {
        // What one write can carry, capped so a piece also fits one notification going the other way.
        min(max(peripheral.maximumWriteValueLength(for: .withResponse), 20), 180)
    }

    /// Whatever the core has queued for the phone since the last look.
    private func flush() {
        guard ready, let peripheral, let phoneId, let engine else { return }
        write(engine.bleTakeOutbox(id: phoneId, chunkSize: UInt32(chunkSize(for: peripheral))))
    }

    private func write(_ chunks: [Data]) {
        guard let peripheral, let characteristic = writeCharacteristic else { return }
        for chunk in chunks { peripheral.writeValue(chunk, for: characteristic, type: .withResponse) }
    }
}

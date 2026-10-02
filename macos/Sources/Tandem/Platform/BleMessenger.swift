import CoreBluetooth
import Foundation
import TandemCore

/// Looks for the phone over Bluetooth while this Mac has no network to reach it on, and says when it
/// is in range, so the Mac can show it as reachable and ask it for its hotspot. When the two share a
/// Bluetooth key (they get one the first time they connect over the network) it also keeps a link,
/// for clipboard and notifications.
///
/// This only moves writes. The core seals, cuts into pieces and puts them back together, and
/// a message that arrives is handled like one from the network, so nothing above knows.
/// Everything runs on one serial queue; what arrives is handed to the core in order.
///
/// The phone's tag (which tells its beacon from someone else's) rides in the scan response, which is
/// not always in the first sight of the phone. The scan therefore reports every sighting, so the tag
/// is seen when it comes, and a phone that never shows one is tried after a while and dropped again
/// if it does not answer as the right phone.
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

    /// Said, on the messenger's queue, when the phone's beacon comes into range or goes out of it.
    var onNearby: (@Sendable (String, Bool) -> Void)?

    /// Whether a link may be opened: the two share a key and the person wants Bluetooth messages.
    private var canLink = false
    private var lastSeen = Date.distantPast
    private var nearby = false
    private var beaconWatch: DispatchSourceTimer?
    /// The phone as it showed itself with its tag, and one that showed no tag (yet).
    private var tagged: CBPeripheral?
    private var untagged: CBPeripheral?
    private var untaggedTimer: DispatchWorkItem?
    /// Set when a frame from the wanted phone opened, which is what makes a link this phone's.
    private var verified = false
    private var verifyTimer: DispatchWorkItem?
    /// Beacons that turned out not to be the phone, and until when they are left alone.
    private var strangers: [UUID: Date] = [:]

    /// Looks for this phone, or stops looking when `phoneId` is nil, and opens a link to it when `canLink`. Safe to
    /// call often.
    func want(phoneId: String?, canLink: Bool, engine: TandemEngine) {
        queue.async {
            self.canLink = canLink
            guard self.phoneId != phoneId else {
                // The key may have come since the phone was first seen.
                self.linkIfPossible()
                return
            }
            self.teardown()
            self.phoneId = phoneId
            self.engine = engine
            guard let phoneId else { return }
            // A phone's tag is per hour, so the neighbouring hours cover a clock a little off.
            let hour = UInt64(Date().timeIntervalSince1970 / 3600)
            self.hints = [hour &- 1, hour, hour &+ 1].map { tandemBleHint(deviceId: phoneId, hour: $0) }
            self.startReader(engine: engine)
            self.startBeaconWatch()
            self.central = CBCentralManager(delegate: self, queue: self.queue, options: [CBCentralManagerOptionShowPowerAlertKey: false])
        }
    }

    // MARK: Lifecycle

    private func startReader(engine: TandemEngine) {
        let (stream, continuation) = AsyncStream<(String, Data)>.makeStream()
        incoming = continuation
        let wanted = phoneId
        // One reader, so the pieces of a message reach the core in the order they arrived.
        Task.detached { [weak self] in
            for await (link, chunk) in stream {
                // A frame that opens with this phone's key is the proof that the link is the phone's.
                if let from = try? await engine.bleReceive(link: link, chunk: chunk), from == wanted {
                    self?.queue.async { self?.verified = true }
                }
            }
        }
    }

    private func startBeaconWatch() {
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 3, repeating: 3)
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            // A link, or one being opened, means the phone is there whatever the scan says.
            if self.peripheral != nil { self.beaconSeen(); return }
            if self.nearby, Date().timeIntervalSince(self.lastSeen) > 15 { self.setNearby(false) }
        }
        timer.resume()
        beaconWatch = timer
    }

    private func beaconSeen() {
        lastSeen = Date()
        setNearby(true)
    }

    private func setNearby(_ value: Bool) {
        guard nearby != value else { return }
        nearby = value
        if let phoneId { onNearby?(phoneId, value) }
    }

    private func teardown() {
        reconnect?.cancel()
        beaconWatch?.cancel()
        beaconWatch = nil
        untaggedTimer?.cancel()
        verifyTimer?.cancel()
        setNearby(false)
        tagged = nil
        untagged = nil
        verified = false
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
        guard phoneId != nil, peripheral == nil, central?.state == .poweredOn else { return }
        // Every sighting, not only the first: the tag is in the scan response, which can come after it.
        central?.scanForPeripherals(withServices: [Self.service], options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
    }

    func centralManager(_ central: CBCentralManager, didDiscover found: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard phoneId != nil, peripheral == nil else { return }
        if let until = strangers[found.identifier], until > Date() { return }
        if let tag = (advertisementData[CBAdvertisementDataServiceDataKey] as? [CBUUID: Data])?[Self.service] {
            // Someone else's phone advertises a different tag.
            guard hints.contains(tag) else { return }
            tagged = found
            untaggedTimer?.cancel()
            untagged = nil
            beaconSeen()
            linkIfPossible()
        } else if tagged == nil, untagged == nil {
            // No tag yet. Give the scan response time to arrive, then settle for this one: it is only believed
            // once it answers as the right phone.
            untagged = found
            let item = DispatchWorkItem { [weak self] in self?.settleForUntagged() }
            untaggedTimer = item
            queue.asyncAfter(deadline: .now() + 5, execute: item)
        }
    }

    private func settleForUntagged() {
        guard canLink, peripheral == nil, tagged == nil, let candidate = untagged else { return }
        connect(candidate, believed: false)
    }

    private func linkIfPossible() {
        guard canLink, peripheral == nil, let found = tagged else { return }
        connect(found, believed: true)
    }

    private func connect(_ found: CBPeripheral, believed: Bool) {
        central?.stopScan()
        peripheral = found
        verified = false
        found.delegate = self
        central?.connect(found, options: nil)
        // A link that has not been proven to be the phone's by then is let go. A beacon that never showed its tag
        // is also left alone for a while, so a stranger's phone is not tried again every few seconds.
        verifyTimer?.cancel()
        let item = DispatchWorkItem { [weak self] in
            guard let self, self.peripheral === found, !self.verified else { return }
            if !believed { self.strangers[found.identifier] = Date().addingTimeInterval(600) }
            self.central?.cancelPeripheralConnection(found)
            self.lost()
        }
        verifyTimer = item
        queue.asyncAfter(deadline: .now() + 15, execute: item)
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
        verifyTimer?.cancel()
        if let peripheral { engine?.bleDropLink(link: peripheral.identifier.uuidString) }
        if let phoneId { engine?.bleLinkDown(id: phoneId) }
        peripheral = nil
        tagged = nil
        untagged = nil
        writeCharacteristic = nil
        ready = false
        verified = false
        guard phoneId != nil else { return }
        reconnect?.cancel()
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

import CoreBluetooth
import Foundation
import TandemCore

/// Lets another computer find this Mac over Bluetooth and send it messages, for when the two have no network between them. The one of
/// two computers with the lower id looks for the other and connects (`BleMessenger`); the other one, which may be this Mac, advertises
/// the Tandem service and waits here. A phone does the same when it is the one that waits.
///
/// Like `BleMessenger` this only moves writes: what arrives on `msg_in` goes to the core, what the core has queued for a device goes
/// out as notifications on `msg_out`. The frames are sealed with a key only the two share, so a stranger who connects gets nothing.
/// CoreBluetooth cannot put service data in an advertisement, so there is no tag here: the one that connects tells the right Mac from
/// somebody else's by whether a frame opens (see `BleMessenger`).
final class BlePeripheralHub: NSObject, CBPeripheralManagerDelegate, @unchecked Sendable {
    private let queue = DispatchQueue(label: "tandem.ble.hub")
    private var manager: CBPeripheralManager?
    private var outCharacteristic: CBMutableCharacteristic?
    private var engine: TandemEngine?
    private var wanted = false
    private var serviceAdded = false
    private var links: [UUID: Link] = [:]
    private var pump: DispatchSourceTimer?
    private var incoming: AsyncStream<(UUID, Data)>.Continuation?

    /// One connected computer: the device it turned out to be, once a frame from it opened, and notifications the radio had no room for.
    private final class Link {
        let central: CBCentral
        var device: String?
        var pending: [Data] = []
        init(_ central: CBCentral) { self.central = central }
    }

    /// Starts or stops waiting for other computers. Safe to call often.
    func setEnabled(_ on: Bool, engine: TandemEngine) {
        queue.async {
            guard on != self.wanted || (on && self.manager == nil) else { return }
            self.wanted = on
            self.engine = engine
            if on {
                self.startReader(engine: engine)
                if self.manager == nil {
                    self.manager = CBPeripheralManager(delegate: self, queue: self.queue, options: [CBPeripheralManagerOptionShowPowerAlertKey: false])
                } else {
                    self.publish()
                }
            } else {
                self.teardown()
            }
        }
    }

    // MARK: Lifecycle

    private func startReader(engine: TandemEngine) {
        incoming?.finish()
        let (stream, continuation) = AsyncStream<(UUID, Data)>.makeStream()
        incoming = continuation
        // One reader, so the pieces of a message reach the core in the order they arrived.
        Task.detached { [weak self] in
            for await (link, chunk) in stream {
                guard let from = try? await engine.bleReceive(link: link.uuidString, chunk: chunk) else { continue }
                self?.queue.async { self?.bound(link: link, to: from) }
            }
        }
    }

    private func teardown() {
        pump?.cancel()
        pump = nil
        manager?.stopAdvertising()
        if serviceAdded { manager?.removeAllServices() }
        serviceAdded = false
        outCharacteristic = nil
        for (id, link) in links { drop(id, link) }
        links = [:]
        manager?.delegate = nil
        manager = nil
        incoming?.finish()
        incoming = nil
    }

    private func drop(_ id: UUID, _ link: Link) {
        engine?.bleDropLink(link: id.uuidString)
        if let device = link.device { engine?.bleLinkDown(id: device) }
    }

    // MARK: Advertising

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn: publish()
        case .poweredOff, .unauthorized, .unsupported:
            // Bluetooth went away: what was linked is gone, and it is published again when it is back.
            for (id, link) in links { drop(id, link) }
            links = [:]
            serviceAdded = false
        default: break
        }
    }

    private func publish() {
        guard wanted, let manager, manager.state == .poweredOn else { return }
        if !serviceAdded {
            let service = CBMutableService(type: BleMessenger.service, primary: true)
            let writes = CBMutableCharacteristic(type: BleMessenger.inUUID, properties: [.write], value: nil, permissions: [.writeable])
            let out = CBMutableCharacteristic(type: BleMessenger.outUUID, properties: [.notify], value: nil, permissions: [.readable])
            service.characteristics = [writes, out]
            outCharacteristic = out
            serviceAdded = true
            manager.add(service)
        } else if !manager.isAdvertising {
            advertise()
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard error == nil else {
            serviceAdded = false
            return
        }
        advertise()
    }

    private func advertise() {
        guard wanted else { return }
        // No name: it would tell everybody in range whose Mac this is.
        manager?.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [BleMessenger.service]])
    }

    // MARK: Links

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == BleMessenger.outUUID else { return }
        if links[central.identifier] == nil { links[central.identifier] = Link(central) }
        startPump()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == BleMessenger.outUUID, let link = links.removeValue(forKey: central.identifier) else { return }
        drop(central.identifier, link)
        if links.isEmpty { pump?.cancel(); pump = nil }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests where request.characteristic.uuid == BleMessenger.inUUID {
            if links[request.central.identifier] == nil { links[request.central.identifier] = Link(request.central) }
            if let value = request.value { incoming?.yield((request.central.identifier, value)) }
        }
        if let first = requests.first { peripheral.respond(to: first, withResult: .success) }
    }

    /// A frame from this link opened, so the link is that device's: it is told who this is, and what waits for it goes out.
    private func bound(link id: UUID, to device: String) {
        guard let link = links[id], let engine else { return }
        let fresh = link.device != device
        link.device = device
        startPump()
        if fresh { send(engine.bleHello(id: device, chunkSize: UInt32(chunkSize(of: link))), to: link) }
    }

    // MARK: Sending

    private func chunkSize(of link: Link) -> Int {
        // What one notification can carry, capped so a piece also fits one write coming the other way.
        min(max(link.central.maximumUpdateValueLength, 20), 180)
    }

    private func startPump() {
        guard pump == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 0.4, repeating: 0.4)
        timer.setEventHandler { [weak self] in self?.flush() }
        timer.resume()
        pump = timer
    }

    private func flush() {
        guard let engine else { return }
        for link in links.values {
            if !link.pending.isEmpty {
                send([], to: link)
            } else if let device = link.device {
                send(engine.bleTakeOutbox(id: device, chunkSize: UInt32(chunkSize(of: link))), to: link)
            }
        }
    }

    private func send(_ chunks: [Data], to link: Link) {
        guard let manager, let out = outCharacteristic else { return }
        link.pending.append(contentsOf: chunks)
        while let next = link.pending.first {
            // The radio has a limited queue: what does not fit waits for `peripheralManagerIsReady`.
            guard manager.updateValue(next, for: out, onSubscribedCentrals: [link.central]) else { return }
            link.pending.removeFirst()
        }
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        for link in links.values where !link.pending.isEmpty { send([], to: link) }
    }
}

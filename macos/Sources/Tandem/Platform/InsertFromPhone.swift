import AppKit
import ApplicationServices
import Observation
import PDFKit
import TandemCore

/// What can be asked of the phone.
enum InsertKind: String, CaseIterable, Identifiable {
    case photo, document, picture

    var id: String { rawValue }

    var core: TandemCaptureKind {
        switch self {
        case .photo: .photo
        case .document: .document
        case .picture: .picture
        }
    }

    var title: String {
        switch self {
        case .photo: String(localized: "Take photo")
        case .document: String(localized: "Scan document")
        case .picture: String(localized: "Choose picture")
        }
    }

    var symbol: String {
        switch self {
        case .photo: "camera.fill"
        case .document: "doc.viewfinder.fill"
        case .picture: "photo.on.rectangle.angled"
        }
    }

    /// What the person does on the phone while the Mac waits.
    var instruction: String {
        switch self {
        case .photo: String(localized: "Take the photo on your phone")
        case .document: String(localized: "Scan the document on your phone")
        case .picture: String(localized: "Choose a picture on your phone")
        }
    }

    var receiving: String {
        switch self {
        case .photo: String(localized: "Receiving the photo")
        case .document: String(localized: "Receiving the scan")
        case .picture: String(localized: "Receiving the picture")
        }
    }
}

/// "Insert from phone": the Mac asks a phone for a photo, a scanned document or a picture from its
/// gallery, and the result lands where the cursor was.
///
/// The request travels as a capture request; the phone answers with an ordinary file share that
/// carries the id of the request, so the file also shows up in the list of shared files. This class
/// is the state of one request, from the first tap to the paste, and drives the floating panel.
@MainActor
@Observable
final class InsertFromPhone {
    static let shared = InsertFromPhone()

    /// How long the phone gets to answer before the Mac gives up.
    static let timeout: TimeInterval = 120

    struct Session: Equatable {
        let id: UInt64
        let kind: InsertKind
        let device: String
        let deviceName: String
        let started: Date
    }

    enum Failure: Equatable {
        case noPhone
        case notConnected(String)
        case needsUpdate(String)
        case cameraRefused(String)
        case unavailable(String)
        case cancelledOnPhone(String)
        case lostConnection(String)
        case timedOut(String)
        case notReceived(String)

        var title: String {
            switch self {
            case .noPhone: String(localized: "No phone is connected")
            case let .notConnected(name): String(localized: "\(name) is not connected")
            case let .needsUpdate(name): String(localized: "\(name) cannot do this yet")
            case let .cameraRefused(name): String(localized: "The camera is off for Tandem on \(name)")
            case .unavailable: String(localized: "Your phone cannot do this")
            case let .cancelledOnPhone(name): String(localized: "Cancelled on \(name)")
            case let .lostConnection(name): String(localized: "Lost the connection to \(name)")
            case let .timedOut(name): String(localized: "\(name) did not answer")
            case .notReceived: String(localized: "The picture did not arrive")
            }
        }

        var detail: String {
            switch self {
            case .noPhone, .notConnected: String(localized: "Open Tandem on your phone and try again.")
            case .needsUpdate: String(localized: "Update Tandem on your phone to the newest version.")
            case .cameraRefused: String(localized: "Allow the camera for Tandem in the settings of your phone, then try again.")
            case .unavailable: String(localized: "It has no camera or scanner that Tandem can use.")
            case .cancelledOnPhone: String(localized: "Nothing was inserted.")
            case .lostConnection: String(localized: "Nothing was inserted. Try again when the phone is back.")
            case .timedOut: String(localized: "Nothing arrived within two minutes.")
            case let .notReceived(reason): reason
            }
        }

        /// A request that was simply called off needs no apology and no second try.
        var isGentle: Bool {
            if case .cancelledOnPhone = self { return true }
            return false
        }
    }

    enum Phase: Equatable {
        case idle
        /// A kind and a device are still to be picked. `kind` is set when it is known already.
        case choosing(kind: InsertKind?, device: String?)
        case waiting(Session)
        case receiving(Session, name: String, done: UInt64, total: UInt64)
        /// `app` is where it went when it was pasted.
        case inserted(Session, pasted: Bool, app: String?)
        case failed(Failure, kind: InsertKind?, device: String?)
    }

    private(set) var phase: Phase = .idle {
        didSet {
            guard phase != oldValue else { return }
            InsertHUDController.shared.phaseChanged(from: oldValue, to: phase)
        }
    }

    /// The app that had the keyboard when this started. The picture is pasted into it, even when the
    /// menu bar panel took the focus on the way.
    private var target: NSRunningApplication?
    private var lastForeign: NSRunningApplication?
    @ObservationIgnored private var timeoutTask: Task<Void, Never>?
    @ObservationIgnored private var closeTask: Task<Void, Never>?
    @ObservationIgnored private var lossTask: Task<Void, Never>?
    @ObservationIgnored private var activeOffer: UInt64?
    /// Offers that came from a request of ours, kept after the request ends so the transfer list can tell.
    @ObservationIgnored private var ownedOffers: [UInt64] = []
    @ObservationIgnored private var observers: [NSObjectProtocol] = []

    private let lastDeviceKey = "insertLastDevice"

    // MARK: Which app is in front

    /// Follows which app other than this one was last in front, because the hot key and the Services
    /// menu leave it in front, but a click in the menu bar panel does not.
    func install() {
        remember(NSWorkspace.shared.frontmostApplication)
        let center = NSWorkspace.shared.notificationCenter
        observers.append(center.addObserver(forName: NSWorkspace.didActivateApplicationNotification, object: nil, queue: .main) { [weak self] note in
            let app = note.userInfo?[NSWorkspace.applicationUserInfoKey] as? NSRunningApplication
            MainActor.assumeIsolated { self?.remember(app) }
        })
        InsertShortcutCenter.shared.apply()
    }

    private func remember(_ app: NSRunningApplication?) {
        guard let app, app.bundleIdentifier != Bundle.main.bundleIdentifier, app.activationPolicy == .regular else { return }
        lastForeign = app
    }

    // MARK: Starting

    private var model: EngineModel { EngineModel.shared }

    /// The phones that are there right now.
    var phones: [TandemDevice] {
        model.devices.filter { $0.platform == .android && $0.online }
    }

    /// The hot key: opens the choice, or closes whatever is on screen.
    func toggle() {
        if case .idle = phase { begin() } else { cancel() }
    }

    /// Starts with a kind already chosen (from the panel or the Services menu) or without one.
    func begin(kind: InsertKind? = nil, device: String? = nil) {
        if case .idle = phase {} else { closeNow() }
        target = NSWorkspace.shared.frontmostApplication.flatMap { $0.bundleIdentifier == Bundle.main.bundleIdentifier ? nil : $0 } ?? lastForeign
        let phones = phones
        guard !phones.isEmpty else {
            let paired = model.devices.first { $0.platform == .android }
            phase = .failed(paired.map { Failure.notConnected($0.name) } ?? .noPhone, kind: kind, device: nil)
            return
        }
        let preferred = device
            ?? UserDefaults.standard.string(forKey: lastDeviceKey).flatMap { id in phones.first { $0.id == id }?.id }
            ?? phones[0].id
        if let kind, phones.count == 1 || device != nil {
            send(kind: kind, device: preferred)
        } else if kind != nil {
            // Several phones and none chosen: ask which one.
            phase = .choosing(kind: kind, device: nil)
        } else {
            phase = .choosing(kind: nil, device: preferred)
            scheduleClose(after: 14)
        }
    }

    func choose(kind: InsertKind, device: String) {
        send(kind: kind, device: device)
    }

    func retry() {
        guard case let .failed(_, kind, device) = phase else { return }
        begin(kind: kind, device: device)
    }

    private func send(kind: InsertKind, device id: String) {
        closeTask?.cancel()
        guard let phone = model.device(id), phone.online else {
            phase = .failed(.notConnected(model.device(id)?.name ?? ""), kind: kind, device: id)
            return
        }
        guard phone.caps.contains("capture") else {
            phase = .failed(.needsUpdate(phone.name), kind: kind, device: id)
            return
        }
        guard let engine = model.tandem else { return }
        let session = Session(id: UInt64.random(in: 1...UInt64.max), kind: kind, device: id, deviceName: phone.name, started: Date())
        UserDefaults.standard.set(id, forKey: lastDeviceKey)
        phase = .waiting(session)
        armTimeout(session, after: Self.timeout)
        Task { @MainActor in
            do {
                try await engine.requestCapture(target: id, id: session.id, kind: kind.core)
            } catch {
                guard self.session?.id == session.id else { return }
                self.fail(.notConnected(phone.name), session: session)
            }
        }
    }

    // MARK: Ending

    private var session: Session? {
        switch phase {
        case let .waiting(s), let .receiving(s, _, _, _), let .inserted(s, _, _): s
        default: nil
        }
    }

    /// Called by the person: Cancel on the panel, the shortcut once more, or Escape.
    func cancel() {
        switch phase {
        case let .waiting(session):
            tell(session, why: .cancelled)
        case let .receiving(session, _, _, _):
            tell(session, why: .cancelled)
            if let offer = activeOffer { Task { try? await model.tandem?.declineOffer(from: session.device, offer: offer) } }
        default:
            break
        }
        closeNow()
    }

    /// Dismisses the panel without a word to the phone.
    func close() { closeNow() }

    private func tell(_ session: Session, why: TandemCaptureWhy) {
        guard let engine = model.tandem else { return }
        Task { try? await engine.cancelCapture(target: session.device, id: session.id, why: why) }
    }

    private func closeNow() {
        timeoutTask?.cancel()
        closeTask?.cancel()
        lossTask?.cancel()
        activeOffer = nil
        phase = .idle
    }

    private func scheduleClose(after seconds: TimeInterval) {
        closeTask?.cancel()
        closeTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled else { return }
            self?.closeNow()
        }
    }

    private func armTimeout(_ session: Session, after seconds: TimeInterval) {
        timeoutTask?.cancel()
        timeoutTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            guard !Task.isCancelled, let self, self.session?.id == session.id else { return }
            self.tell(session, why: .cancelled)
            self.fail(.timedOut(session.deviceName), session: session)
        }
    }

    private func fail(_ failure: Failure, session: Session) {
        timeoutTask?.cancel()
        lossTask?.cancel()
        activeOffer = nil
        phase = .failed(failure, kind: session.kind, device: session.device)
        if failure.isGentle { scheduleClose(after: 2.4) }
    }

    // MARK: What the engine says

    /// Every event passes here first. It only reacts to the ones that belong to the request in progress.
    func observe(_ event: TandemEvent) {
        switch event {
        case let .captureCancelled(from, id, why):
            guard let session, session.id == id, session.device == from else { return }
            switch why {
            case .refused: fail(.cameraRefused(session.deviceName), session: session)
            case .unavailable: fail(.unavailable(session.deviceName), session: session)
            case .cancelled, .other: fail(.cancelledOnPhone(session.deviceName), session: session)
            }

        case let .shareOffered(from, offer, origin, items):
            guard case let .capture(request) = origin else { return }
            guard case let .waiting(session) = phase, session.id == request, session.device == from else { return }
            // The Mac may be set to ask before taking files. This one it asked for itself.
            ownedOffers.append(offer)
            if ownedOffers.count > 20 { ownedOffers.removeFirst() }
            activeOffer = offer
            let name = items.first?.name ?? ""
            let total = items.first?.size ?? 0
            phase = .receiving(session, name: name, done: 0, total: total)
            armTimeout(session, after: 180)
            do {
                try model.tandem?.acceptOffer(from: from, offer: offer)
            } catch {
                fail(.notReceived(error.localizedDescription), session: session)
            }

        case let .progress(offer, _, _, incoming, _, done, total):
            guard incoming, offer == activeOffer, case let .receiving(session, name, _, _) = phase else { return }
            phase = .receiving(session, name: name, done: done, total: total)

        case let .finished(offer, _, _, incoming, _, _, location, error):
            guard incoming, offer == activeOffer, let session else { return }
            if let error {
                fail(.notReceived(error), session: session)
            } else if let location {
                deliver(URL(fileURLWithPath: location), session: session)
            }

        case let .disconnected(id):
            guard let session, session.device == id else { return }
            if case .inserted = phase { return }
            // A phone drops off and comes back all the time. Only a phone that stays away ends the request.
            lossTask?.cancel()
            lossTask = Task { @MainActor [weak self] in
                try? await Task.sleep(for: .seconds(8))
                guard !Task.isCancelled, let self, self.session?.id == session.id else { return }
                if self.model.device(id)?.online == true { return }
                self.fail(.lostConnection(session.deviceName), session: session)
            }

        case let .connected(id):
            if session?.device == id { lossTask?.cancel() }

        default:
            break
        }
    }

    /// Whether a transfer is the answer to a request of ours, which is handled here and not as a
    /// received picture.
    func owns(offer: UInt64) -> Bool { ownedOffers.contains(offer) }

    // MARK: The paste

    private func deliver(_ url: URL, session: Session) {
        timeoutTask?.cancel()
        lossTask?.cancel()
        activeOffer = nil
        guard putOnPasteboard(url) else {
            fail(.notReceived(String(localized: "The file could not be read.")), session: session)
            return
        }
        guard AXIsProcessTrusted() else {
            phase = .inserted(session, pasted: false, app: nil)
            scheduleClose(after: 9)
            return
        }
        let app = target
        if let app, NSWorkspace.shared.frontmostApplication?.processIdentifier != app.processIdentifier {
            app.activate()
        }
        phase = .inserted(session, pasted: true, app: app?.localizedName)
        scheduleClose(after: 1.8)
        // A moment for the app to come to the front and for the panel to settle.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.22) { Self.pressPaste() }
    }

    /// Several representations in one item, so each app takes the one it prefers: the picture for
    /// documents and chats, the PDF for apps that keep pages, the file for Finder.
    private func putOnPasteboard(_ url: URL) -> Bool {
        let item = NSPasteboardItem()
        if url.pathExtension.lowercased() == "pdf", let data = try? Data(contentsOf: url), let document = PDFDocument(data: data) {
            if let page = document.page(at: 0) {
                let size = page.bounds(for: .mediaBox).size
                let scale = min(2.5, 2200 / max(size.width, size.height))
                let image = page.thumbnail(of: CGSize(width: size.width * scale, height: size.height * scale), for: .mediaBox)
                if let tiff = image.tiffRepresentation, let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) {
                    item.setData(png, forType: .png)
                    item.setData(tiff, forType: .tiff)
                }
            }
            item.setData(data, forType: .pdf)
        } else if let image = NSImage(contentsOf: url), let tiff = image.tiffRepresentation {
            if let png = NSBitmapImageRep(data: tiff)?.representation(using: .png, properties: [:]) { item.setData(png, forType: .png) }
            item.setData(tiff, forType: .tiff)
        } else {
            return false
        }
        item.setString(url.absoluteString, forType: .fileURL)
        let board = NSPasteboard.general
        board.clearContents()
        return board.writeObjects([item])
    }

    private static func pressPaste() {
        let source = CGEventSource(stateID: .combinedSessionState)
        for down in [true, false] {
            let event = CGEvent(keyboardEventSource: source, virtualKey: 9, keyDown: down) // V
            event?.flags = .maskCommand
            event?.post(tap: .cghidEventTap)
        }
    }

    /// Asks for the Accessibility permission that pasting needs, the way the trackpad does.
    func askForPastePermission() {
        let options = ["AXTrustedCheckOptionPrompt": true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
    }

    // MARK: Debug

    /// Shows the panel in a state without a phone, for the snapshot harness.
    func debugShow(_ stage: String) {
        let session = Session(id: 1, kind: .document, device: "debug", deviceName: "Pixel 9", started: Date())
        switch stage {
        case "choose": phase = .choosing(kind: nil, device: "debug")
        case "which": phase = .choosing(kind: .photo, device: nil)
        case "waiting": phase = .waiting(session)
        case "receiving": phase = .receiving(session, name: "Scan 2026-10-04 at 14.31.pdf", done: 1_400_000, total: 3_900_000)
        case "done": phase = .inserted(session, pasted: true, app: "Pages")
        case "ready": phase = .inserted(session, pasted: false, app: nil)
        case "refused": phase = .failed(.cameraRefused("Pixel 9"), kind: .photo, device: "debug")
        case "offline": phase = .failed(.notConnected("Pixel 9"), kind: .photo, device: "debug")
        case "timeout": phase = .failed(.timedOut("Pixel 9"), kind: .document, device: "debug")
        case "cancelled": phase = .failed(.cancelledOnPhone("Pixel 9"), kind: .photo, device: "debug")
        default: break
        }
    }
}

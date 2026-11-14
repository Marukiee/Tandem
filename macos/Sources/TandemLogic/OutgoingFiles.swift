import Foundation

/// The files this Mac sends, by offer and number: where each came from, so a finished transfer can be opened (two files with the same
/// name no longer point at one source), and the zips made of folders, which go once every device has its copy (R-B16).
public struct OutgoingFiles {
    private struct Offer {
        var sources: [String]
        var temporary: [URL]
        var waiting: Set<String>
        var failed = false
    }

    private var offers: [UInt64: Offer] = [:]
    private var order: [UInt64] = []
    private let kept: Int

    public init(kept: Int = 200) { self.kept = kept }

    /// An offer went to `peers`. `sources[i]` is where file `i` came from; `temporary` are the zips made for it.
    public mutating func sent(offer: UInt64, sources: [String], temporary: [URL], to peers: [String]) {
        let waiting = Set(peers.flatMap { peer in sources.indices.map { Self.key(peer, $0) } })
        offers[offer] = Offer(sources: sources, temporary: temporary, waiting: waiting)
        order.append(offer)
        // Only the sources of old offers are let go of; a zip still waiting stays (it is in the temporary folder either way).
        while order.count > kept {
            let old = order.removeFirst()
            if offers[old]?.waiting.isEmpty ?? true { offers[old] = nil }
        }
    }

    public func source(offer: UInt64, index: UInt32) -> String? {
        guard let entry = offers[offer], Int(index) < entry.sources.count else { return nil }
        return entry.sources[Int(index)]
    }

    /// The source of a transfer by the id the app gives it (`offer-index-out`).
    public func source(transfer id: String) -> String? {
        let parts = id.split(separator: "-")
        guard parts.count == 3, parts[2] == "out", let offer = UInt64(parts[0]), let index = UInt32(parts[1]) else { return nil }
        return source(offer: offer, index: index)
    }

    public static func remove(_ urls: [URL]) {
        for url in urls { try? FileManager.default.removeItem(at: url) }
    }

    /// One file reached one device, or failed to. Returns the zips to remove now: when every device has every file. After a failure they
    /// stay, because the receiver can still pick the transfer up again where it stopped.
    public mutating func finished(offer: UInt64, index: UInt32, peer: String, ok: Bool) -> [URL] {
        guard var entry = offers[offer], entry.waiting.remove(Self.key(peer, Int(index))) != nil else { return [] }
        if !ok { entry.failed = true }
        var done: [URL] = []
        if entry.waiting.isEmpty, !entry.failed {
            done = entry.temporary
            entry.temporary = []
        }
        offers[offer] = entry
        return done
    }

    private static func key(_ peer: String, _ index: Int) -> String { "\(peer)#\(index)" }
}

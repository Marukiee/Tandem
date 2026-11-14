import Foundation

/// The last size each other computer said its screens are, kept over a restart: without it the arrangement shows "Size not known yet"
/// and puts the edge on a guessed size until that computer speaks again (R-B35).
public struct RememberedSizes: Codable, Equatable {
    public struct Size: Codable, Equatable {
        public var width: Double
        public var height: Double

        public init(width: Double, height: Double) {
            self.width = width
            self.height = height
        }
    }

    public private(set) var sizes: [String: Size] = [:]

    public init(_ sizes: [String: Size] = [:]) { self.sizes = sizes }

    /// A size that came in. It always wins over the one kept. True when something changed, so it is only written then: the same size
    /// comes in every few seconds.
    @discardableResult
    public mutating func heard(_ device: String, width: Double, height: Double) -> Bool {
        guard width > 0, height > 0 else { return false }
        let size = Size(width: width, height: height)
        guard sizes[device] != size else { return false }
        sizes[device] = size
        return true
    }

    /// Forgets the devices that left the circle. An empty circle is taken as not known yet (the engine still starting), not as everybody
    /// gone. True when something changed.
    @discardableResult
    public mutating func keep(only circle: Set<String>) -> Bool {
        guard !circle.isEmpty else { return false }
        let before = sizes.count
        sizes = sizes.filter { circle.contains($0.key) }
        return sizes.count != before
    }

    public static func load(from url: URL) -> RememberedSizes {
        guard let data = try? Data(contentsOf: url), let found = try? JSONDecoder().decode(RememberedSizes.self, from: data) else {
            return RememberedSizes()
        }
        return found
    }

    public func save(to url: URL) {
        guard let data = try? JSONEncoder().encode(self) else { return }
        try? data.write(to: url, options: [.atomic])
    }
}

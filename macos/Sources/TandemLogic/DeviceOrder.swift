import Foundation

/// The order devices are shown in, kept from one start to the next so rows do not jump about while devices connect.
public enum DeviceOrder {
    /// The kept order with the devices that are gone left out and the new ones added at the end, in the order they come in.
    public static func merge(kept: [String], current: [String]) -> [String] {
        let present = Set(current)
        var order = kept.filter { present.contains($0) }
        var seen = Set(order)
        for id in current where !seen.contains(id) {
            order.append(id)
            seen.insert(id)
        }
        return order
    }
}

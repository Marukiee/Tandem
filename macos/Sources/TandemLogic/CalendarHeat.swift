import Foundation

/// How strong each day of the month calendar of Health is coloured. Every strength stays inside 0...1: a month with one value or only
/// equal values would otherwise divide by zero.
public enum CalendarHeat {
    public static let sleepGoal = 420
    static let shareFloor = 0.15
    static let heartFloor = 0.2

    /// The usual of a month: the rounded mean of its days with a value.
    public static func usual(_ values: [Int?]) -> Int? {
        let measured = values.compactMap { $0 }
        guard !measured.isEmpty else { return nil }
        return Int((Double(measured.reduce(0, +)) / Double(measured.count)).rounded())
    }

    /// Toward the goal, or without one toward the highest day of the month: from a faint tile for a little to full at the goal.
    public static func shares(_ values: [Int?], goal: Int?) -> [Double?] {
        let top = goal.flatMap { $0 > 0 ? $0 : nil } ?? values.compactMap { $0 }.max().flatMap { $0 > 0 ? $0 : nil }
        return values.map { value in
            guard let value else { return nil }
            guard let top else { return shareFloor }
            return min(1, max(0, shareFloor + (1 - shareFloor) * min(1, max(0, Double(value) / Double(top)))))
        }
    }

    public static func met(_ value: Int?, goal: Int?) -> Bool {
        guard let value, let goal, goal > 0 else { return false }
        return value >= goal
    }

    /// The heart against the usual of the month: above it positive, below it negative, by how far compared with the furthest day of that
    /// month. Within 1 bpm of the usual it is 0, a neutral tile.
    public static func heart(_ values: [Int?]) -> [Double?] {
        guard let usual = usual(values) else { return values.map { _ in nil } }
        let furthest = values.compactMap { $0 }.map { abs($0 - usual) }.max() ?? 0
        return values.map { value in
            guard let value else { return nil }
            let off = value - usual
            guard abs(off) > 1, furthest > 1 else { return 0 }
            let strength = min(1, heartFloor + (1 - heartFloor) * Double(abs(off)) / Double(furthest))
            return off > 0 ? strength : -strength
        }
    }

    /// Today when it has a value, otherwise the last day of the month with one.
    public static func defaultPick(_ values: [Int?], today: Int?) -> Int? {
        if let today, values.indices.contains(today), values[today] != nil { return today }
        return values.lastIndex { $0 != nil }
    }
}

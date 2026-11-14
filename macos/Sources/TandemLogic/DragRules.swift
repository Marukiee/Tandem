import Foundation

/// What a drag that runs over the edge of the screen carries, chosen from the types on the drag pasteboard alone, so the event tap reads
/// no data (R-D17). The order is files, then files an app promises (Photos, Mail, a browser), then a picture, then text, then a link:
/// a picture from a browser also carries its address as text, and used to arrive as that text (R-D5, R-D6).
public enum DragKind: Equatable {
    case files
    case promised
    case picture
    case text
    case link

    public static let fileURL = "public.file-url"
    public static let pictures: Set<String> = ["public.png", "public.tiff", "public.jpeg", "public.heic", "com.compuserve.gif"]
    public static let texts: Set<String> = ["public.utf8-plain-text", "public.plain-text", "NSStringPboardType"]
    public static let links: Set<String> = ["public.url", "Apple URL pasteboard type"]

    /// `promiseTypes` are the types AppKit reads promises from (`NSFilePromiseReceiver.readableDraggedTypes`).
    public static func choose(types: [String], promiseTypes: Set<String>) -> DragKind? {
        let set = Set(types)
        if set.contains(fileURL) { return .files }
        if !set.isDisjoint(with: promiseTypes) { return .promised }
        if !set.isDisjoint(with: pictures) { return .picture }
        if !set.isDisjoint(with: texts) { return .text }
        if !set.isDisjoint(with: links) { return .link }
        return nil
    }
}

/// How files that came by a drag from another computer are offered here (R-D7, R-D8).
public enum CarryRules {
    /// Right under the pointer, where the next press picks them up, only while that press is still to come from the same movement: the
    /// button is still down, or the files came within a moment of the pointer coming over. Later they wait on a card beside the pointer,
    /// so a click or a scroll meant for something else is not caught.
    public static func underPointer(buttonDown: Bool, sinceCrossing: TimeInterval?) -> Bool {
        if buttonDown { return true }
        guard let sinceCrossing else { return false }
        return sinceCrossing >= 0 && sinceCrossing <= 2
    }

    /// A drag of the system takes no files once it has started, so it waits for every file of the offer, or for the rest to stop coming.
    public static func readyToPickUp(arrived: Int, expected: Int?, sinceLast: TimeInterval) -> Bool {
        if let expected, arrived >= expected { return true }
        return sinceLast >= 5
    }
}

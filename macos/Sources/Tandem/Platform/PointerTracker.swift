import CoreGraphics
import Foundation

/// Where the pointer is on the screen of the other computer, followed by counting the movements that were sent there. The other
/// computer says how big its screen is when it takes the pointer in. With that this Mac can take the pointer home by itself when the
/// other one cannot say it (a desktop that does not tell the real place of its pointer, a link that went quiet).
struct RemoteTracker {
    private(set) var x: Double
    private(set) var y: Double
    let width: Double
    let height: Double
    /// The side of the other screen that the pointer came in by.
    let cameInBy: String
    /// How far past that side it may be pushed before it counts as leaving. The counting adds up a few pixels wrong now and then.
    let margin: Double

    /// `edge` is the side of this Mac's screen that the pointer went out by; it comes in at the opposite side over there, `along` of the
    /// way down that side.
    init(width: Double, height: Double, edge: String, along: Double, margin: Double = 30) {
        self.width = max(width, 1)
        self.height = max(height, 1)
        self.margin = margin
        let along = min(max(along, 0), 1)
        switch edge {
        case "right":
            cameInBy = "left"
            (x, y) = (0, along * (self.height - 1))
        case "left":
            cameInBy = "right"
            (x, y) = (self.width - 1, along * (self.height - 1))
        case "bottom":
            cameInBy = "top"
            (x, y) = (along * (self.width - 1), 0)
        default:
            cameInBy = "bottom"
            (x, y) = (along * (self.width - 1), self.height - 1)
        }
    }

    /// A movement. True when the pointer has gone back out past the side it came in by, and then it has to come home.
    mutating func moved(dx: Double, dy: Double) -> Bool {
        let nx = x + dx
        let ny = y + dy
        let leaving: Bool
        switch cameInBy {
        case "left": leaving = nx < -margin
        case "right": leaving = nx > width - 1 + margin
        case "top": leaving = ny < -margin
        default: leaving = ny > height - 1 + margin
        }
        if leaving { return true }
        // Along the side it came in by it may sit a little outside while it is pushed, so a push is counted from the edge and not lost.
        let sideways = cameInBy == "left" || cameInBy == "right"
        x = min(max(nx, sideways ? -margin : 0), sideways ? width - 1 + margin : width - 1)
        y = min(max(ny, sideways ? 0 : -margin), sideways ? height - 1 : height - 1 + margin)
        return false
    }
}

/// Hiding the pointer of this Mac while it is on another computer. The system only lets the app in front hide it, and this app is
/// not in front (it sits in the menu bar), so it first asks the window server for the right to do it from the background, the way
/// the other programs that share a mouse do. If that call is not there on this system the pointer just stays where it froze.
enum CursorHider {
    private static var hidden = false

    private static let allowedInBackground: Bool = {
        typealias MainConnection = @convention(c) () -> Int32
        typealias SetProperty = @convention(c) (Int32, Int32, CFString, CFTypeRef) -> Int32
        guard let handle = dlopen(nil, RTLD_NOW),
              let main = dlsym(handle, "CGSMainConnectionID"),
              let set = dlsym(handle, "CGSSetConnectionProperty")
        else { return false }
        let connection = unsafeBitCast(main, to: MainConnection.self)()
        return unsafeBitCast(set, to: SetProperty.self)(connection, connection, "SetsCursorInBackground" as CFString, kCFBooleanTrue) == 0
    }()

    static func hide() {
        guard !hidden else { return }
        _ = allowedInBackground
        CGDisplayHideCursor(CGMainDisplayID())
        hidden = true
    }

    static func show() {
        guard hidden else { return }
        CGDisplayShowCursor(CGMainDisplayID())
        hidden = false
    }
}

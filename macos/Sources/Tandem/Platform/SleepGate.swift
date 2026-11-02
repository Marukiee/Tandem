import Foundation
import IOKit
import IOKit.pwr_mgt

/// Holds off the sleep of this Mac for a moment, so something can still be said first. `NSWorkspace.willSleepNotification` does not
/// wait: with the lid closed the network was often gone before "asleep" was sent, and the phone showed the Mac as offline.
final class SleepGate: @unchecked Sendable {
    /// Called before sleep with a function to call when done. Sleep waits for that, or for `limit`, whichever comes first.
    var beforeSleep: ((@escaping () -> Void) -> Void)?
    private let limit: TimeInterval = 3
    /// `kIOMessageCanSystemSleep` and `kIOMessageSystemWillSleep`, macros of IOKit that Swift does not see (iokit_common_msg 0x270, 0x280).
    private static let canSleep: natural_t = 0xE000_0270
    private static let willSleep: natural_t = 0xE000_0280
    private var root: io_connect_t = 0
    private var notifier: io_object_t = 0
    private var port: IONotificationPortRef?

    func start() {
        guard root == 0 else { return }
        let context = Unmanaged.passUnretained(self).toOpaque()
        root = IORegisterForSystemPower(context, &port, { context, _, message, argument in
            guard let context else { return }
            Unmanaged<SleepGate>.fromOpaque(context).takeUnretainedValue().handle(message, argument)
        }, &notifier)
        guard root != 0, let port else { return }
        CFRunLoopAddSource(CFRunLoopGetMain(), IONotificationPortGetRunLoopSource(port).takeUnretainedValue(), .commonModes)
    }

    private func handle(_ message: natural_t, _ argument: UnsafeMutableRawPointer?) {
        let id = Int(bitPattern: argument)
        switch message {
        case Self.canSleep:
            IOAllowPowerChange(root, id)
        case Self.willSleep:
            var allowed = false
            let allow = { [weak self] in
                DispatchQueue.main.async {
                    guard let self, !allowed else { return }
                    allowed = true
                    IOAllowPowerChange(self.root, id)
                }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + limit, execute: allow)
            if let beforeSleep { beforeSleep(allow) } else { allow() }
        default:
            break
        }
    }
}

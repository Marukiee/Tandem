import AppKit
import SwiftUI

/// Development helper (`TANDEM_DEBUG_ICONS=1`): every kind of device as the app draws it, in the sizes it is drawn at, while it is
/// there and while it is not, in light and in dark, side by side. One picture in which the family can be judged at a glance.
struct DeviceIconsBoard: View {
    private static let kinds: [(name: String, symbol: String)] = [
        ("Phone", "iphone"), ("Tablet", "ipad"), ("Laptop", "laptopcomputer"), ("Desktop", "desktopcomputer"), ("Windows PC", "pc"),
        ("Watch", DeviceSymbol.watch), ("TV", "tv"),
    ]
    /// The head of a page, a row of the sidebar, and that row with the ring of a battery around it.
    private static let sizes: [CGFloat] = [60, 38, 30]

    var body: some View {
        HStack(spacing: 0) {
            half.environment(\.colorScheme, .light)
            half.environment(\.colorScheme, .dark)
        }
    }

    private var half: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 0) {
                Text(verbatim: "").frame(width: 96)
                Text(verbatim: "Connected").frame(width: 176, alignment: .leading)
                Text(verbatim: "Not connected")
            }
            .font(.caption.weight(.semibold))
            .foregroundStyle(.secondary)
            ForEach(Self.kinds, id: \.symbol) { kind in
                HStack(spacing: 0) {
                    Text(verbatim: kind.name).font(.callout).frame(width: 96, alignment: .leading)
                    ForEach([true, false], id: \.self) { online in
                        HStack(spacing: 12) {
                            ForEach(Self.sizes, id: \.self) { size in
                                DeviceGlyph(platform: .other, online: online, size: size, drawn: kind.symbol)
                            }
                        }
                        .frame(width: 176, alignment: .leading)
                    }
                }
            }
        }
        .padding(24)
        .foregroundStyle(.primary)
        .background(Color(nsColor: .windowBackgroundColor))
    }
}

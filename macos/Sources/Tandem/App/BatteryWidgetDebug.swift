import AppKit
import SwiftUI

/// The battery widget drawn into PNGs, since a widget on the desktop cannot be photographed without recording the screen. Made up
/// devices only: nothing of the engine or of the file the extension reads is touched.
@MainActor
enum BatteryWidgetDebug {
    /// About the sizes of the desktop widgets of macOS, in points.
    private static let frames: [(String, BatteryWidgetSize, CGSize)] = [
        ("small", .small, CGSize(width: 170, height: 170)),
        ("medium", .medium, CGSize(width: 364, height: 170)),
        ("large", .large, CGSize(width: 364, height: 382)),
    ]

    private static var cases: [(String, [BatteryWidgetDevice])] {
        let all = BatteryWidgetSnapshot.sample.devices
        return [("full", all), ("four", Array(all.prefix(4))), ("three", Array(all.prefix(3))), ("two", Array(all.prefix(2))), ("one", Array(all.prefix(1))), ("empty", [])]
    }

    static func run(into directory: URL) {
        var report = ""
        for (caseName, devices) in cases {
            for (sizeName, size, frame) in frames {
                for scheme in [ColorScheme.light, .dark] {
                    let view = BatteryWidgetView(devices: devices, size: size)
                        .padding(16)
                        .frame(width: frame.width, height: frame.height)
                        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(BatteryWidgetStyle.background))
                        .environment(\.colorScheme, scheme)
                    let renderer = ImageRenderer(content: view)
                    renderer.scale = 2
                    let name = "battery-widget-\(caseName)-\(sizeName)-\(scheme == .dark ? "dark" : "light").png"
                    if let image = renderer.cgImage, let png = NSBitmapImageRep(cgImage: image).representation(using: .png, properties: [:]) {
                        try? png.write(to: directory.appendingPathComponent(name))
                        report += "\(name) \(image.width)x\(image.height)\n"
                    } else {
                        report += "\(name) failed\n"
                    }
                }
            }
        }
        try? report.write(to: directory.appendingPathComponent("battery-widget.txt"), atomically: true, encoding: .utf8)
        NSApp.terminate(nil)
    }
}

// Draws the Tandem app icon: two tilted pills, side by side like two riders.
// Usage: swift scripts/make-icon.swift <output.png> [size]
import AppKit

let arguments = CommandLine.arguments
guard arguments.count >= 2 else {
    FileHandle.standardError.write(Data("usage: make-icon.swift <output.png> [size]\n".utf8))
    exit(1)
}
let size = arguments.count > 2 ? CGFloat(Double(arguments[2]) ?? 1024) : 1024
let canvas = NSSize(width: size, height: size)

let bitmap = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: Int(size), pixelsHigh: Int(size),
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: bitmap)
let context = NSGraphicsContext.current!.cgContext
context.scaleBy(x: size / 1024, y: size / 1024)

func color(_ hex: UInt32, _ alpha: CGFloat = 1) -> NSColor {
    NSColor(
        srgbRed: CGFloat((hex >> 16) & 0xFF) / 255,
        green: CGFloat((hex >> 8) & 0xFF) / 255,
        blue: CGFloat(hex & 0xFF) / 255,
        alpha: alpha
    )
}

// The plate: macOS icons sit on a rounded square with room around it for the shadow.
let plate = NSRect(x: 100, y: 100, width: 824, height: 824)
let platePath = NSBezierPath(roundedRect: plate, xRadius: 186, yRadius: 186)

context.saveGState()
let shadow = NSShadow()
shadow.shadowColor = NSColor.black.withAlphaComponent(0.35)
shadow.shadowOffset = NSSize(width: 0, height: -14)
shadow.shadowBlurRadius = 28
shadow.set()
color(0x2E2C6B).setFill()
platePath.fill()
context.restoreGState()

context.saveGState()
platePath.addClip()
NSGradient(colors: [color(0x7A79EE), color(0x4A49C4), color(0x2E2C6B)], atLocations: [0, 0.55, 1], colorSpace: .sRGB)!
    .draw(in: plate, angle: -60)
// A soft light from the top, so the plate reads as glass.
NSGradient(colors: [NSColor.white.withAlphaComponent(0.22), NSColor.white.withAlphaComponent(0)], atLocations: [0, 1], colorSpace: .sRGB)!
    .draw(in: NSRect(x: plate.minX, y: plate.midY, width: plate.width, height: plate.height / 2), angle: -90)
context.restoreGState()

// Two pills, tilted, overlapping in the middle.
func pill(center: NSPoint, length: CGFloat, width: CGFloat, angle: CGFloat, fill: [NSColor]) {
    context.saveGState()
    context.translateBy(x: center.x, y: center.y)
    context.rotate(by: angle * .pi / 180)
    let rect = NSRect(x: -width / 2, y: -length / 2, width: width, height: length)
    let path = NSBezierPath(roundedRect: rect, xRadius: width / 2, yRadius: width / 2)
    let glow = NSShadow()
    glow.shadowColor = NSColor.black.withAlphaComponent(0.28)
    glow.shadowOffset = NSSize(width: 0, height: -10)
    glow.shadowBlurRadius = 22
    glow.set()
    fill[0].setFill()
    path.fill()
    context.setShadow(offset: .zero, blur: 0)
    path.addClip()
    NSGradient(colors: fill)!.draw(in: rect, angle: 90)
    NSGradient(colors: [NSColor.white.withAlphaComponent(0.35), NSColor.white.withAlphaComponent(0)])!
        .draw(in: NSRect(x: rect.minX, y: rect.midY, width: rect.width, height: rect.height / 2), angle: -90)
    context.restoreGState()
}

pill(center: NSPoint(x: 590, y: 512), length: 470, width: 170, angle: 32,
     fill: [color(0xFFB0CB), color(0xE8779F)])
pill(center: NSPoint(x: 434, y: 512), length: 470, width: 170, angle: 32,
     fill: [color(0xFFFFFF), color(0xC9C8FA)])

NSGraphicsContext.restoreGraphicsState()
let png = bitmap.representation(using: .png, properties: [:])!
try png.write(to: URL(fileURLWithPath: arguments[1]))

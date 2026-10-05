import AppKit
import CoreImage.CIFilterBuiltins
import SwiftUI

enum QRCode {
    /// The dark indigo the code is drawn in: the colour of the app, and still about 16 to 1 against the white tile.
    private static let ink = CIColor(red: 27.0 / 255.0, green: 26.0 / 255.0, blue: 74.0 / 255.0)

    static func image(for text: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        let tint = CIFilter.falseColor()
        tint.inputImage = filter.outputImage
        tint.color0 = ink
        tint.color1 = CIColor.white
        guard
            let output = tint.outputImage?.samplingNearest().transformed(by: CGAffineTransform(scaleX: 12, y: 12)),
            let cgImage = CIContext().createCGImage(output, from: output.extent)
        else { return nil }
        return NSImage(cgImage: cgImage, size: NSSize(width: output.extent.width, height: output.extent.height))
    }
}

/// Shows a code another device can scan, and takes a code pasted from somewhere else.
struct PairingPanel: View {
    @Environment(EngineModel.self) private var model
    var onFinished: () -> Void = {}

    @LocalState private var pasted = ""
    @LocalState private var showPaste = false

    var body: some View {
        VStack(spacing: 18) {
            if let name = model.pairing.joinedName {
                PairedSuccess(name: name)
                    .transition(.scale(scale: 0.8).combined(with: .opacity))
            } else {
                VStack(spacing: 8) {
                    Text("Pair a device").font(.title2.weight(.bold))
                    Text("Open Tandem on your other device, choose Pair a device and scan this code, or type the digits below.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 340)
                }

                CodeStage()

                VStack(spacing: 10) {
                    GlassEffectContainer(spacing: 10) {
                        HStack(spacing: 10) {
                            GlassActionButton(title: "Copy link", symbol: "link", wide: true) {
                                if let uri = model.pairing.uri {
                                    NSPasteboard.general.clearContents()
                                    NSPasteboard.general.setString(uri, forType: .string)
                                    model.showToast(String(localized: "Pairing link copied"))
                                }
                            }
                            GlassActionButton(title: "I have a code or link", symbol: "square.and.arrow.down", wide: true) {
                                withAnimation(.tandem) { showPaste.toggle() }
                            }
                        }
                    }
                    .frame(width: 360)
                    if showPaste {
                        HStack(spacing: 8) {
                            TextField("Code of 8 digits or a link", text: $pasted)
                                .textFieldStyle(.roundedBorder)
                                .frame(width: 260)
                                .onSubmit(joinWithPasted)
                            Button("Join", action: joinWithPasted)
                                .buttonStyle(.glassProminent)
                                .tint(Palette.indigo)
                                .disabled(pasted.isEmpty || model.pairing.busy)
                        }
                        .transition(.move(edge: .top).combined(with: .opacity))
                    }
                }

                if model.pairing.busy {
                    HStack(spacing: 10) {
                        PillSpinner(size: 20)
                        Text("Connecting").font(.callout).foregroundStyle(.secondary)
                    }
                    .transition(.opacity)
                }
                if let error = model.pairing.error {
                    Text(error)
                        .font(.callout)
                        .foregroundStyle(Palette.urgent)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 340)
                        .transition(.opacity)
                }
            }
        }
        .padding(.horizontal, 28)
        .padding(.vertical, 12)
        .animation(.tandemSpringy, value: model.pairing.joinedName)
        .animation(.tandem, value: model.pairing.busy)
        .animation(.tandem, value: model.pairing.error)
        .task { model.beginPairing() }
        .onChange(of: model.pairing.joinedName) { _, name in
            guard name != nil else { return }
            Task {
                try? await Task.sleep(for: .seconds(1.6))
                onFinished()
            }
        }
    }

    private func joinWithPasted() {
        model.pair(uri: pasted)
    }
}

/// The code on its white tile, in a frame of four corner brackets like the one a camera draws around what it reads,
/// and under it how long the code still works. It is the same picture as on the phone, which scans with that frame.
private struct CodeStage: View {
    @Environment(EngineModel.self) private var model

    private let side: CGFloat = 208
    private let space: CGFloat = 20
    private let corner: CGFloat = 28

    var body: some View {
        let ready = model.pairing.uri != nil && !expired
        VStack(spacing: 14) {
            ZStack {
                // A quiet glow so the white tile does not sit on the page like a hole.
                Circle()
                    .fill(RadialGradient(colors: [Palette.indigo.opacity(0.20), Palette.indigo.opacity(0)], center: .center, startRadius: 20, endRadius: 230))
                    .frame(width: 420, height: 420)
                    .allowsHitTesting(false)

                Brackets(inset: ready ? 10 : 0, radius: corner + space - 10)
                    .stroke(
                        ready ? AnyShapeStyle(Palette.indigo.gradient) : AnyShapeStyle(Color.primary.opacity(0.14)),
                        style: StrokeStyle(lineWidth: 5, lineCap: .round)
                    )
                    .frame(width: side + space * 2, height: side + space * 2)
                    .animation(.tandemSpringy, value: ready)

                RoundedRectangle(cornerRadius: corner, style: .continuous)
                    .fill(expired ? Color.primary.opacity(0.06) : Color.white)
                    .frame(width: side, height: side)
                    .shadow(color: .black.opacity(0.10), radius: 18, y: 8)
                    .overlay {
                        if let uri = model.pairing.uri, let image = QRCode.image(for: uri), !expired {
                            Image(nsImage: image)
                                .interpolation(.none)
                                .resizable()
                                .scaledToFit()
                                .padding(18)
                                .transition(.opacity)
                        } else if expired {
                            VStack(spacing: 10) {
                                Text("Expired").font(.headline)
                                Button("New code") { model.beginPairing() }
                                    .buttonStyle(.glassProminent)
                                    .tint(Palette.indigo)
                            }
                        } else {
                            PillSpinner(size: 34)
                        }
                    }
                    .animation(.tandemFade, value: expired)
            }
            .frame(width: side + space * 2, height: side + space * 2)

            Countdown(expiresAt: model.pairing.expiresAt)
                .frame(width: side)

            if let code = model.pairing.code, ready {
                VStack(spacing: 2) {
                    Text("Or type this code").font(.caption).foregroundStyle(.secondary)
                    Text(code)
                        .font(.system(size: 30, weight: .semibold, design: .rounded).monospacedDigit())
                        .tracking(2)
                        .textSelection(.enabled)
                }
                .transition(.opacity)
            }
        }
        .animation(.tandemFade, value: ready)
    }

    private var expired: Bool {
        guard let expires = model.pairing.expiresAt else { return false }
        return Date() > expires
    }
}

/// Four corner brackets that follow the curve of the tile inside them. `inset` moves them in towards the code, which
/// is how they close in on it once there is one.
private struct Brackets: Shape {
    var inset: CGFloat
    var radius: CGFloat
    private let arm: CGFloat = 16

    var animatableData: CGFloat {
        get { inset }
        set { inset = newValue }
    }

    func path(in rect: CGRect) -> Path {
        let box = rect.insetBy(dx: inset, dy: inset)
        let r = radius
        var path = Path()
        path.move(to: CGPoint(x: box.minX, y: box.minY + r + arm))
        path.addLine(to: CGPoint(x: box.minX, y: box.minY + r))
        path.addRelativeArc(center: CGPoint(x: box.minX + r, y: box.minY + r), radius: r, startAngle: .degrees(180), delta: .degrees(90))
        path.addLine(to: CGPoint(x: box.minX + r + arm, y: box.minY))

        path.move(to: CGPoint(x: box.maxX - r - arm, y: box.minY))
        path.addLine(to: CGPoint(x: box.maxX - r, y: box.minY))
        path.addRelativeArc(center: CGPoint(x: box.maxX - r, y: box.minY + r), radius: r, startAngle: .degrees(270), delta: .degrees(90))
        path.addLine(to: CGPoint(x: box.maxX, y: box.minY + r + arm))

        path.move(to: CGPoint(x: box.maxX, y: box.maxY - r - arm))
        path.addLine(to: CGPoint(x: box.maxX, y: box.maxY - r))
        path.addRelativeArc(center: CGPoint(x: box.maxX - r, y: box.maxY - r), radius: r, startAngle: .degrees(0), delta: .degrees(90))
        path.addLine(to: CGPoint(x: box.maxX - r - arm, y: box.maxY))

        path.move(to: CGPoint(x: box.minX + r + arm, y: box.maxY))
        path.addLine(to: CGPoint(x: box.minX + r, y: box.maxY))
        path.addRelativeArc(center: CGPoint(x: box.minX + r, y: box.maxY - r), radius: r, startAngle: .degrees(90), delta: .degrees(90))
        path.addLine(to: CGPoint(x: box.minX, y: box.maxY - r - arm))
        return path
    }
}

/// How long the code still works: a bar that drains, and the time it has left.
private struct Countdown: View {
    let expiresAt: Date?

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.5)) { context in
            let left = max(0, expiresAt.map { $0.timeIntervalSince(context.date) } ?? CodeLifetime.seconds)
            let fraction = CGFloat(min(1, left / CodeLifetime.seconds))
            let seconds = Int(left.rounded(.up))
            HStack(spacing: 12) {
                Capsule()
                    .fill(Color.primary.opacity(0.08))
                    .frame(height: 6)
                    .overlay(alignment: .leading) {
                        GeometryReader { proxy in
                            Capsule()
                                .fill(Palette.indigo.gradient)
                                .frame(width: max(6, proxy.size.width * fraction))
                                .animation(.linear(duration: 0.5), value: fraction)
                        }
                    }
                Text("\(seconds / 60):\(String(format: "%02d", seconds % 60))")
                    .font(.callout.monospacedDigit())
                    .foregroundStyle(.secondary)
            }
        }
    }
}

private enum CodeLifetime {
    /// What a pairing code lasts, which is also what the bar runs over.
    static let seconds: Double = 300
}

struct PairedSuccess: View {
    let name: String
    @LocalState private var appeared = false

    var body: some View {
        VStack(spacing: 16) {
            ZStack {
                Circle().fill(Color.green.opacity(0.16)).frame(width: 110, height: 110)
                    .scaleEffect(appeared ? 1 : 0.4)
                Image(systemName: "checkmark")
                    .font(.system(size: 44, weight: .bold))
                    .foregroundStyle(.green)
                    .symbolEffect(.bounce, value: appeared)
            }
            Text("Paired with \(name)").font(.title3.weight(.semibold))
            Text("They will now find each other on their own.")
                .font(.callout)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 30)
        .onAppear { withAnimation(.tandemBouncy) { appeared = true } }
    }
}

struct PairingSheet: View {
    @Environment(EngineModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        PairingPanel { dismiss() }
            .frame(width: 460)
            .overlay(alignment: .topTrailing) {
                Button {
                    dismiss()
                } label: {
                    Image(systemName: "xmark").font(.callout.weight(.semibold)).frame(width: 14, height: 14)
                }
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .keyboardShortcut(.cancelAction)
                .help("Close")
                .padding(14)
            }
            .onDisappear { model.endPairing() }
    }
}

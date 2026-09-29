import AppKit
import CoreImage.CIFilterBuiltins
import SwiftUI

enum QRCode {
    static func image(for text: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard
            let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 12, y: 12)),
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
        VStack(spacing: 22) {
            if let name = model.pairing.joinedName {
                PairedSuccess(name: name)
                    .transition(.scale(scale: 0.8).combined(with: .opacity))
            } else {
                VStack(spacing: 6) {
                    Text("Pair a device").font(.title2.weight(.bold))
                    Text("Open Tandem on the other device, choose Add device and scan this code.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 340)
                }

                QRCard()

                VStack(spacing: 10) {
                    HStack(spacing: 10) {
                        GlassActionButton(title: "Copy link", symbol: "doc.on.doc") {
                            if let uri = model.pairing.uri {
                                NSPasteboard.general.clearContents()
                                NSPasteboard.general.setString(uri, forType: .string)
                                model.showToast(String(localized: "Pairing link copied"))
                            }
                        }
                        GlassActionButton(title: "I have a code", symbol: "text.viewfinder") {
                            withAnimation(.tandem) { showPaste.toggle() }
                        }
                    }
                    if showPaste {
                        HStack(spacing: 8) {
                            TextField("Paste a pairing link", text: $pasted)
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
        .padding(28)
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

private struct QRCard: View {
    @Environment(EngineModel.self) private var model

    var body: some View {
        let size: CGFloat = 220
        ZStack {
            // The countdown runs around the outside, so it is visible without a number.
            TimelineView(.periodic(from: .now, by: 0.5)) { context in
                let remaining = remainingFraction(at: context.date)
                Circle()
                    .stroke(Color.primary.opacity(0.06), lineWidth: 6)
                    .overlay {
                        Circle()
                            .trim(from: 0, to: remaining)
                            .stroke(Palette.indigo.gradient, style: StrokeStyle(lineWidth: 6, lineCap: .round))
                            .rotationEffect(.degrees(-90))
                            .animation(.linear(duration: 0.5), value: remaining)
                    }
                    .frame(width: size + 72, height: size + 72)
            }

            RoundedRectangle(cornerRadius: 30, style: .continuous)
                .fill(Color.white)
                .frame(width: size, height: size)
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
                            Text("Expired").font(.headline).foregroundStyle(.black)
                            Button("New code") { model.beginPairing() }
                                .buttonStyle(.glassProminent)
                        }
                    } else {
                        PillSpinner(size: 34)
                    }
                }
        }
        .frame(height: size + 80)
    }

    private var expired: Bool {
        guard let expires = model.pairing.expiresAt else { return false }
        return Date() > expires
    }

    private func remainingFraction(at date: Date) -> Double {
        guard let expires = model.pairing.expiresAt else { return 1 }
        return max(0, min(1, expires.timeIntervalSince(date) / 300))
    }
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

import AppKit
import SwiftUI
import TandemCore

/// A symbol that beats like a heart, from its centre, by Core Animation so the app sleeps while it does.
struct HeartBeat: View {
    var symbol = "heart.fill"
    let pointSize: CGFloat
    var color: Color = .pink

    var body: some View {
        // A frame a little larger than the symbol, so its swell is not cut off by the edge of the view.
        PulsingSymbol(name: symbol, pointSize: pointSize, weight: .semibold, color: NSColor(color), beat: true)
            .frame(width: pointSize * 1.5, height: pointSize * 1.5)
    }
}

/// The heart rate now at the top of the heart card, as on the phone and on Linux: a beating heart, the number and "Now",
/// while the watch is with its phone and this page can be seen. Without it, the last measured number of the day.
struct HeartNowHead: View {
    let measured: [Int]
    let day: String
    @Environment(EngineModel.self) private var model
    @State private var appVisible = NSApplication.shared.occlusionState.contains(.visible)

    private var phone: TandemDevice? { model.devices.first { $0.id == model.watchPhoneID } }

    private var range: some View {
        Text("\(day), lowest \(measured.min() ?? 0), highest \(measured.max() ?? 0)")
            .font(.caption)
            .foregroundStyle(.secondary)
    }

    var body: some View {
        let watchHere = phone.map { $0.online && $0.status.watch?.online == true } ?? false
        let asking = watchHere && appVisible
        TimelineView(.periodic(from: .now, by: 2)) { context in
            let now = phone.flatMap { model.watchHeartNow[$0.id] }.flatMap { watchHere && $0.fresh(at: context.date) ? $0.bpm : nil }
            if let now {
                HStack(alignment: .center, spacing: 12) {
                    HeartBeat(pointSize: 20, color: Palette.indigo)
                    BigNumber(text: String(localized: "\(now) bpm"))
                    VStack(alignment: .leading, spacing: 1) {
                        Text("Now").font(.callout.weight(.semibold)).foregroundStyle(Palette.indigo)
                        if !measured.isEmpty { range }
                    }
                }
            } else {
                HStack(alignment: .lastTextBaseline, spacing: 12) {
                    BigNumber(text: String(localized: "\(measured.last ?? 0) bpm"))
                    range
                }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: NSApplication.didChangeOcclusionStateNotification)) { _ in
            appVisible = NSApplication.shared.occlusionState.contains(.visible)
        }
        // Asked again every half minute, as the phone stops by itself without that, and never from a hidden window: the
        // watch measures with its light on for as long as anybody asks.
        .task(id: asking) {
            guard let id = phone?.id else { return }
            guard asking else {
                if watchHere { model.watchHeartLive(id, on: false) }
                return
            }
            while !Task.isCancelled {
                model.watchHeartLive(id, on: true)
                try? await Task.sleep(for: .seconds(30))
            }
        }
        .onDisappear { if let id = phone?.id { model.watchHeartLive(id, on: false) } }
    }
}

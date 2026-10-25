import SwiftUI
import WidgetKit

// The battery widget. Its view is `BatteryWidgetView.swift` of the app, compiled into this extension too; the batteries come from the file
// `BatteryBridge` in the app writes.

struct BatteryEntry: TimelineEntry {
    let date: Date
    let snapshot: BatteryWidgetSnapshot
}

struct BatteryProvider: TimelineProvider {
    static func read() -> BatteryWidgetSnapshot? {
        // The sandbox of the extension has its own home; the one of the person is what the app writes in.
        let home = String(cString: getpwuid(getuid()).pointee.pw_dir)
        let folder = Bundle.main.object(forInfoDictionaryKey: "TandemSupportDir") as? String ?? "Tandem"
        let url = URL(fileURLWithPath: home).appendingPathComponent("Library/Application Support/\(folder)/\(BatteryWidgetSnapshot.fileName)")
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(BatteryWidgetSnapshot.self, from: data)
    }

    func placeholder(in context: Context) -> BatteryEntry {
        BatteryEntry(date: Date(), snapshot: .sample)
    }

    func getSnapshot(in context: Context, completion: @escaping (BatteryEntry) -> Void) {
        // The gallery shows what the widget looks like even before Tandem has heard of any battery.
        let read = Self.read()
        let shown = context.isPreview && (read?.devices.isEmpty ?? true) ? .sample : (read ?? BatteryWidgetSnapshot())
        completion(BatteryEntry(date: Date(), snapshot: shown))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<BatteryEntry>) -> Void) {
        // The app asks for a new timeline when a battery changes in a way that shows; this is for the small steps in between.
        let entry = BatteryEntry(date: Date(), snapshot: Self.read() ?? BatteryWidgetSnapshot())
        completion(Timeline(entries: [entry], policy: .after(Date().addingTimeInterval(15 * 60))))
    }
}

struct BatteryWidgetEntryView: View {
    @Environment(\.widgetFamily) private var family
    let entry: BatteryEntry

    private var size: BatteryWidgetSize {
        switch family {
        case .systemSmall: .small
        case .systemLarge, .systemExtraLarge: .large
        default: .medium
        }
    }

    var body: some View {
        BatteryWidgetView(devices: entry.snapshot.devices, size: size)
            .containerBackground(.background, for: .widget)
    }
}

struct BatteryWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: BatteryWidgetSnapshot.kind, provider: BatteryProvider()) { entry in
            BatteryWidgetEntryView(entry: entry)
        }
        .configurationDisplayName(BatteryWidgetStyle.text("Batteries", "Batterijen"))
        .description(BatteryWidgetStyle.text("The battery of this Mac and of your other devices.", "De batterij van deze Mac en van je andere apparaten."))
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

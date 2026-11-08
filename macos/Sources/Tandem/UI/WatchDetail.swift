import SwiftUI
import TandemCore

/// The watch of a phone as a device of its own. It only ever talks to that phone, so everything here goes by way of it, and what needs
/// the phone is grey and says why while the phone is away. Built like the page of the watch on the phone: what it measures near the top,
/// what it does, and what is set for the watch itself behind one row at the bottom.
struct WatchDetail: View {
    @Environment(EngineModel.self) private var model
    let phone: TandemDevice
    var onOpenPhone: () -> Void = {}

    @State private var speaker = WatchAudio.shared.chosen
    @State private var showSettings = false

    private var watch: TandemWatchStatus? { phone.status.watch }
    private var reachable: Bool { phone.online && watch?.online == true }
    private var name: String { watch?.name ?? String(localized: "Watch") }
    /// What is set for the watch, as its phone said last. Nil from a phone older than these: then nothing is shown as off that may be on.
    private var settings: TandemWatchSettings? { watch?.settings }
    /// Why the choices cannot be changed now, or nil when they can.
    private var settingsWhy: String? { phone.online ? nil : String(localized: "Its phone is not connected") }

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                header
                WatchHealthCard()
                actions
                choices
                sleepChoices
                settingsRow
            }
            .pagePadding()
            .frame(maxWidth: 760)
            .frame(maxWidth: .infinity)
        }
        .animation(.tandem, value: reachable)
        .animation(.tandemFade, value: watch?.sleeping)
        .animation(.tandemFade, value: settings == nil)
        .sheet(isPresented: $showSettings) { WatchSettingsSheet(phone: phone) }
    }

    private var header: some View {
        Card(radius: Metrics.card, padding: 22, tint: reachable ? Palette.indigo : nil) {
            HStack(spacing: 20) {
                Image(systemName: "applewatch")
                    .font(.system(size: 34, weight: .semibold))
                    .foregroundStyle(Palette.indigo)
                    .frame(width: 80, height: 80)
                    .background(Palette.indigo.opacity(0.14), in: Circle())
                VStack(alignment: .leading, spacing: 8) {
                    Text(verbatim: name).font(.title.weight(.bold)).lineLimit(1).contentTransition(.opacity)
                    Text("Wear OS \u{00B7} by way of \(phone.name)").font(.callout).foregroundStyle(.secondary).lineLimit(1)
                    HStack(spacing: 8) {
                        Chip(
                            symbol: reachable ? "checkmark.circle.fill" : "applewatch.slash",
                            text: reachable ? "Connected" : "Watch away",
                            tint: reachable ? .green : .secondary,
                            strong: reachable
                        )
                        if reachable, watch?.sleeping == true {
                            Chip(symbol: "bed.double.fill", text: "Recording your sleep", tint: Palette.indigo)
                        }
                    }
                }
                Spacer(minLength: 0)
                if let level = watch?.level {
                    BatteryRing(battery: TandemBattery(level: level, charging: watch?.charging == true, powerSave: false), size: 74, connected: reachable)
                        .transition(.scale.combined(with: .opacity))
                }
            }
        }
    }

    private var actions: some View {
        ActionGroup(title: "Watch") {
            GlassActionButton(
                title: watch?.ringing == true ? "Stop ringing" : "Find watch",
                symbol: watch?.ringing == true ? "bell.slash.fill" : "applewatch.radiowaves.left.and.right",
                prominent: watch?.ringing == true
            ) {
                model.ringWatch(phone.id, on: watch?.ringing != true)
            }
            .disabled(!reachable)
            .help(reachable ? "" : "The watch is not connected to its phone")
            // The watch opens its mouse screen by itself, so it need not be touched.
            GlassActionButton(title: "Use watch as mouse", symbol: "cursorarrow.motionlines") { model.startWatchMouse(phone.id) }
                .disabled(!reachable)
                .help(reachable ? "" : "The watch is not connected to its phone")
            GlassActionButton(title: "Open \(phone.name)", symbol: "iphone") { onOpenPhone() }
        }
    }

    /// What the watch does during the day, kept on its phone, which passes it on: the same choices as there, so they can be made from here.
    private var choices: some View {
        Card(radius: Metrics.card, padding: 6) {
            VStack(spacing: 0) {
                // Over Bluetooth straight to this Mac, not by way of the phone: the watch is paired with it as its mouse.
                SettingRow(symbol: "speaker.wave.2", title: "Use the watch as a speaker", subtitle: "Over Bluetooth, in phone call quality. Only when you choose it here") {
                    Toggle("", isOn: Binding(
                        get: { speaker },
                        set: { on in
                            if WatchAudio.shared.choose(on) { speaker = on }
                            else { model.showToast(String(localized: "Connect the watch to this Mac over Bluetooth first")) }
                        }
                    ))
                    .controlSize(.mini)
                }
                if let settings {
                    rowDivider
                    toggle(
                        symbol: "cursorarrow.motionlines", title: "Use the watch as a mouse",
                        subtitle: "Steers a computer, only while the mouse screen is open on the unlocked watch",
                        on: settings.mouse
                    ) { TandemWatchSettingsChange(mouse: $0) }
                    rowDivider
                    toggle(
                        symbol: "heart", title: "Heart rate all day",
                        subtitle: "Half a minute in every five while you wear your watch. Off saves battery.",
                        on: settings.heartAllDay
                    ) { TandemWatchSettingsChange(heartAllDay: $0) }
                } else {
                    // A phone with an older Tandem does not say them: a switch that shows off could be wrong, so none is shown.
                    rowDivider
                    SettingRow(
                        symbol: "arrow.down.circle", title: "More choices for the watch",
                        subtitle: "Update Tandem on \(phone.name) to make them from here"
                    ) { EmptyView() }
                }
            }
        }
    }

    /// What happens while the watch records a night, under a title of its own as on the phone.
    @ViewBuilder
    private var sleepChoices: some View {
        if let settings {
            VStack(alignment: .leading, spacing: 8) {
                Text("While you sleep").font(.headline).padding(.horizontal, 4)
                Card(radius: Metrics.card, padding: 6) {
                    VStack(spacing: 0) {
                        toggle(
                            symbol: "moon", title: "Quiet while you sleep",
                            subtitle: "Your phone goes into a quiet mode of its own while the watch records your sleep, and out of it when you wake up",
                            on: settings.sleepQuiet
                        ) { TandemWatchSettingsChange(sleepQuiet: $0) }
                        rowDivider
                        toggle(
                            symbol: "alarm", title: "Wake me gently",
                            subtitle: "Five minutes before your alarm your watch starts one long, quiet buzz that rises and falls a few times, a little more each time. It never gets strong: your alarm wakes you the rest of the way",
                            on: settings.smartWake
                        ) { TandemWatchSettingsChange(smartWake: $0) }
                    }
                }
            }
        }
    }

    /// As the page of a computer has on the phone: the name, the app on the watch and unpairing are set once, if ever, and wait behind
    /// one row instead of sitting between the things that are used.
    private var settingsRow: some View {
        Card(radius: Metrics.card, padding: 6) {
            Button { showSettings = true } label: {
                SettingRow(symbol: "slider.horizontal.3", title: "Settings for this device", subtitle: "Name, Tandem on your watch, and unpairing") {
                    Image(systemName: "chevron.right").foregroundStyle(.secondary)
                }
                .contentShape(.rect)
            }
            .buttonStyle(.plain)
        }
    }

    private var rowDivider: some View {
        Divider().opacity(0.4).padding(.horizontal, 14)
    }

    private func toggle(
        symbol: String, title: LocalizedStringKey, subtitle: LocalizedStringKey, on: Bool, change: @escaping (Bool) -> TandemWatchSettingsChange
    ) -> some View {
        SettingRow(symbol: symbol, title: title, subtitle: subtitle) {
            Toggle("", isOn: Binding(get: { on }, set: { model.setWatchSettings(phone.id, change($0)) }))
                .controlSize(.mini)
                .disabled(settingsWhy != nil)
                .help(settingsWhy ?? "")
        }
    }
}

/// What is set for the watch itself: its name, the Tandem on it, and unpairing it. A sheet, so unpairing is never one click away on the
/// page that is used every day.
private struct WatchSettingsSheet: View {
    @Environment(EngineModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    let phone: TandemDevice

    @State private var renaming = false
    @State private var newName = ""
    @State private var confirmUnpair = false

    private var name: String { phone.status.watch?.name ?? String(localized: "Watch") }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: name).font(.title2.weight(.bold))
                Text("Settings for this device").foregroundStyle(.secondary)
            }
            Card(radius: Metrics.card, padding: 6) {
                VStack(spacing: 0) {
                    SettingRow(symbol: "pencil", title: "Name", subtitle: "\(name). Your phone and your other computers show it by this name too.") {
                        Button("Rename") {
                            newName = name
                            renaming = true
                        }
                        .buttonStyle(.tandemGlass)
                        .disabled(!phone.online)
                        .help(phone.online ? "" : "Its phone is not connected")
                    }
                    Divider().opacity(0.4).padding(.horizontal, 14)
                    // The phone updates the app on the watch by itself; this Mac does not hear which version it has.
                    SettingRow(symbol: "arrow.triangle.2.circlepath", title: "Tandem on your watch", subtitle: "\(phone.name) keeps it up to date by itself") {
                        EmptyView()
                    }
                }
            }
            Card(radius: Metrics.card, padding: 6) {
                SettingRow(symbol: "trash", title: "Unpair this watch", subtitle: "Pairing it again is done on your phone") {
                    Button("Unpair", role: .destructive) { confirmUnpair = true }
                        .buttonStyle(.tandemGlass)
                        .disabled(!phone.online)
                        .help(phone.online ? "" : "Its phone is not connected")
                }
            }
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(22)
        .frame(width: 520)
        .alert("Rename watch", isPresented: $renaming) {
            TextField("Name", text: $newName)
            Button("Save") { model.renameWatch(phone.id, name: newName == String(localized: "Watch") ? "" : newName) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Your phone and your other computers show it by this name too.")
        }
        .confirmationDialog("Unpair \(name)?", isPresented: $confirmUnpair, titleVisibility: .visible) {
            Button("Unpair", role: .destructive) {
                model.unpairWatch(phone.id)
                dismiss()
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Pairing it again is done on your phone.")
        }
    }
}

/// The way to everything the watch measures, as on the phone: what it is called and what is in it, with the last numbers as tiles that
/// open it too. Only what this Mac was given: a computer sees whole days, so the steps are of the last whole day.
private struct WatchHealthCard: View {
    @Environment(EngineModel.self) private var model

    var body: some View {
        let insights = model.healthInsights
        let night = model.healthRecords.first { $0.kind == .sleep }
        let stepsDay = insights?.week.last { $0.steps != nil }
        Card(radius: Metrics.card, padding: 6) {
            VStack(spacing: 6) {
                Button { AppScenes.showSettings(.health) } label: {
                    HStack(spacing: 14) {
                        Image(systemName: "heart.fill")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(Palette.indigo)
                            .frame(width: 40, height: 40)
                            .background(Palette.indigo.opacity(0.14), in: .circle)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Health").font(.title3.weight(.semibold))
                            Text("Sleep, steps, heart rate and workouts").font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 12)
                        Image(systemName: "chevron.right").foregroundStyle(.secondary)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .contentShape(.rect)
                    .hoverHighlight(radius: Metrics.inner(Metrics.card, inset: 6))
                }
                .buttonStyle(.plain)
                .help("Open Health")
                if night != nil || stepsDay != nil || insights?.resting != nil {
                    HStack(alignment: .top, spacing: 8) {
                        if let night {
                            let score = insights?.latestNight?.id == night.id ? insights?.latestNight?.score : nil
                            GlanceTile(
                                symbol: "bed.double", title: "Last night", number: minutesText(Int(night.sleepMinutes)),
                                caption: score.map { String(localized: "Sleep score \(Int($0))") } ?? dayName(night.endMs)
                            )
                        }
                        if let day = stepsDay, let steps = day.steps {
                            GlanceTile(symbol: "figure.walk", title: "Steps", number: Int(steps).formatted(), caption: dayName(day.startMs))
                        }
                        if let resting = insights?.resting {
                            GlanceTile(
                                symbol: "heart", title: "Resting heart rate", number: String(localized: "\(Int(resting.latest)) bpm"),
                                caption: resting.usual.map { String(localized: "Usually \(Int($0))") } ?? String(localized: "\(Int(resting.nightsNeeded)) more nights to show this")
                            )
                        }
                    }
                    .padding([.horizontal, .bottom], 6)
                }
            }
        }
    }
}

/// One number of what the watch measured, which opens the page with all of it.
private struct GlanceTile: View {
    let symbol: String
    let title: LocalizedStringKey
    let number: String
    let caption: String

    var body: some View {
        Button { AppScenes.showSettings(.health) } label: {
            VStack(alignment: .leading, spacing: 6) {
                Label(title, systemImage: symbol)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Text(verbatim: number)
                    .font(.title2.weight(.bold))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Text(verbatim: caption)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .background(Color.primary.opacity(0.05), in: .rect(cornerRadius: Metrics.inner(Metrics.card, inset: 6), style: .continuous))
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .help("Open Health")
    }
}

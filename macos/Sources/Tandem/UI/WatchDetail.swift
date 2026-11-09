import SwiftUI
import TandemCore

/// The watch of a phone as a device of its own. It only ever talks to that phone, so everything here goes by way of it, and what needs
/// the phone is grey and says why while the phone is away. Built like the page of the watch on the phone: what it measures near the top,
/// what it does, and what is set for the watch itself behind one row at the bottom.
struct WatchDetail: View {
    @Environment(EngineModel.self) private var model
    let phone: TandemDevice
    var onOpenPhone: () -> Void = {}
    /// Opens Health, as a page of the main window; in Settings where there is no main window to open it in.
    var onOpenHealth: () -> Void = { AppScenes.showSettings(.health) }

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
                WatchHealthCard(onOpen: onOpenHealth)
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
                        // Without Bluetooth on the phone the watch can never connect: say that, not only that it is away.
                        if !reachable, phone.online, watch?.bluetoothOff == true {
                            Chip(symbol: "antenna.radiowaves.left.and.right.slash", text: "Bluetooth off on \(phone.name)", tint: .orange, strong: true)
                        } else {
                            Chip(
                                symbol: reachable ? "checkmark.circle.fill" : "applewatch.slash",
                                text: reachable ? "Connected" : "Watch away",
                                tint: reachable ? .green : .secondary,
                                strong: reachable
                            )
                        }
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

    /// What the watch steers now, as its phone said: this Mac, another computer, or nothing.
    private var mouse: TandemWatchMouse? { reachable ? watch?.mouse : nil }
    private var steersThisMac: Bool { mouse?.target == model.myId }
    private var steeredName: String? {
        guard let mouse, !steersThisMac else { return nil }
        return model.device(mouse.target)?.name ?? String(localized: "another computer")
    }

    /// What the watch can do from here, as tiles that say what they are doing: one that is on says so, and how to stop it.
    private var actions: some View {
        let away = reachable ? nil : String(localized: "The watch is not connected to its phone")
        let ringing = watch?.ringing == true
        let inAir = steersThisMac && mouse?.table == false
        let onTable = steersThisMac && mouse?.table == true
        return VStack(alignment: .leading, spacing: 8) {
            Text("Watch").font(.headline).padding(.horizontal, 4)
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                WatchActionTile(
                    symbol: ringing ? "bell.slash.fill" : "applewatch.radiowaves.left.and.right",
                    title: ringing ? "Stop sound" : "Play sound",
                    subtitle: ringing ? String(localized: "Ringing now, also on silent") : String(localized: "It rings, even on silent"),
                    on: ringing, why: away
                ) { model.ringWatch(phone.id, on: !ringing) }
                WatchActionTile(
                    symbol: "cursorarrow.motionlines",
                    title: inAir ? "Stop using watch as mouse" : "Use watch as mouse",
                    subtitle: inAir ? String(localized: "Steering this Mac now")
                        : steeredName.map { String(localized: "Steering \($0) now") } ?? String(localized: "Point with your arm, pinch to click"),
                    on: inAir, why: away
                ) { inAir ? model.stopWatchMouse(phone.id) : model.startWatchMouse(phone.id) }
                WatchActionTile(
                    symbol: "hand.raised.fingers.spread",
                    title: onTable ? "Stop the table mouse" : "Table mouse",
                    subtitle: onTable ? String(localized: "Your hand on the table steers this Mac") : String(localized: "Your hand on the table, a tap clicks"),
                    on: onTable, why: away
                ) { onTable ? model.stopWatchMouse(phone.id) : model.startWatchMouse(phone.id, table: true) }
                WatchActionTile(
                    symbol: "iphone", title: "Open \(phone.name)", subtitle: String(localized: "Its page, with everything it does"), on: false, why: nil
                ) { onOpenPhone() }
            }
        }
        .animation(.tandemFade, value: ringing)
        .animation(.tandemFade, value: mouse)
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
                        subtitle: "Half a minute in every five while you wear your watch.",
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
                SettingRow(symbol: "slider.horizontal.3", title: "Settings for this device", subtitle: "Name, watch app, and unpairing") {
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
                    SettingRow(symbol: "arrow.triangle.2.circlepath", title: "Watch app", subtitle: "\(phone.name) keeps it up to date by itself") {
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
    let onOpen: () -> Void

    var body: some View {
        let insights = model.healthInsights
        let night = model.healthRecords.first { $0.kind == .sleep }
        let stepsDay = insights?.week.last { $0.steps != nil }
        Card(radius: Metrics.card, padding: 6) {
            VStack(spacing: 6) {
                Button(action: onOpen) {
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
                                onOpen: onOpen, symbol: "bed.double", title: "Last night", number: minutesText(Int(night.sleepMinutes)),
                                caption: score.map { String(localized: "Sleep score \(Int($0))") } ?? dayName(night.endMs)
                            )
                        }
                        if let day = stepsDay, let steps = day.steps {
                            GlanceTile(onOpen: onOpen, symbol: "figure.walk", title: "Steps", number: Int(steps).formatted(), caption: dayName(day.startMs))
                        }
                        if let resting = insights?.resting {
                            GlanceTile(
                                onOpen: onOpen, symbol: "heart", title: "Resting heart rate", number: String(localized: "\(Int(resting.latest)) bpm"),
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
    let onOpen: () -> Void
    let symbol: String
    let title: LocalizedStringKey
    let number: String
    let caption: String

    var body: some View {
        Button(action: onOpen) {
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

/// One thing the watch can do, as a tile: its icon in a round, what it does, and what it is doing now. On, it takes the colour of the
/// page, so what is running is seen at a glance and stopped with the same tile.
private struct WatchActionTile: View {
    let symbol: String
    let title: LocalizedStringKey
    let subtitle: String
    let on: Bool
    /// Why it cannot be used now, or nil when it can.
    let why: String?
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: symbol)
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(on ? Color.white : Palette.indigo)
                    .frame(width: 38, height: 38)
                    .background(on ? Palette.indigo : Palette.indigo.opacity(0.14), in: .circle)
                    .contentTransition(.symbolEffect(.replace))
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.callout.weight(.semibold)).lineLimit(1).contentTransition(.opacity)
                    Text(verbatim: subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(2).contentTransition(.opacity)
                }
                Spacer(minLength: 0)
            }
            .padding(12)
            .frame(maxWidth: .infinity, minHeight: 64, alignment: .leading)
            .background(
                on ? Palette.indigo.opacity(0.16) : Color.primary.opacity(0.05),
                in: .rect(cornerRadius: Metrics.inner(Metrics.card, inset: 6), style: .continuous)
            )
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(why != nil)
        .opacity(why == nil ? 1 : 0.5)
        .help(why ?? "")
    }
}

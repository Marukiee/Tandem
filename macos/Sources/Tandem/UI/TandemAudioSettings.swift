import SwiftUI

/// In Settings, General, under the sound of other devices: one switch that installs or removes Tandem's sound devices, and an (i) that
/// says what they are. The switch follows what is really installed; turning it either way asks for the password.
struct TandemAudioSettings: View {
    @Environment(EngineModel.self) private var model
    @Bindable private var driver = TandemAudioDriver.shared
    @LocalState private var explaining = false

    var body: some View {
        Section {
            Toggle(isOn: Binding(get: { driver.installed }, set: { change(to: $0) })) {
                HStack(spacing: 6) {
                    Text("Tandem Speaker and Microphone")
                    Button { explaining.toggle() } label: { Image(systemName: "info.circle").foregroundStyle(.secondary) }
                        .buttonStyle(.borderless)
                        .popover(isPresented: $explaining, arrowEdge: .bottom) {
                            Text("Puts Tandem Speaker and Tandem Microphone in the sound menu, like AirPods: pick them to play this Mac's sound on your phone, or to use your phone as the microphone. Turning this on or off asks for your password. macOS asks once whether Tandem may use the microphone: it needs that to pass the sound on.")
                                .font(.callout)
                                .frame(width: 300, alignment: .leading)
                                .fixedSize(horizontal: false, vertical: true)
                                .padding(14)
                        }
                    if driver.busy {
                        PillSpinner(size: 14)
                    } else if driver.outdated {
                        Button("Update") { run { await driver.install() } }
                            .buttonStyle(.borderless)
                            .controlSize(.small)
                    }
                }
            }
            .disabled(driver.busy)
        }
        .animation(.tandem, value: driver.busy)
        .animation(.tandem, value: driver.outdated)
    }

    private func change(to on: Bool) {
        guard on != driver.installed else { return }
        run { on ? await driver.install() : await driver.uninstall() }
    }

    private func run(_ work: @escaping @MainActor () async -> String?) {
        Task { @MainActor in
            if let problem = await work() { model.showToast(problem) }
        }
    }
}

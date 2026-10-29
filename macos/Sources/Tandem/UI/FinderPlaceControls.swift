import SwiftUI
import TandemCore

/// The files page's buttons for the place in the Finder sidebar (`FilesInFinder`), where the extension is in this build.
struct FinderPlaceControls: View {
    @Environment(EngineModel.self) private var model
    let device: TandemDevice

    var body: some View {
        let places = FilesInFinder.shared
        HStack(spacing: 10) {
            switch places.state(of: device.id) {
            case .off:
                Button(action: turnOn) {
                    Label("Show in Finder", systemImage: "sidebar.left")
                }
                .buttonStyle(.tandemGlass)
                .disabled(model.tandem == nil)
                .help("Put these files in the Finder sidebar, so any app can use them")
            case .adding:
                PillSpinner(size: 18)
                Text("Adding to Finder").font(.callout).foregroundStyle(.secondary)
            case .on:
                // Also while the phone is away: Finder shows what it fetched before, and says the phone cannot be reached.
                Button { places.open(device) } label: {
                    Label("Open in Finder", systemImage: "folder.fill")
                }
                .buttonStyle(.tandemGlass)
                .help("In the Finder sidebar under Locations")
                Button { Task { await places.turnOff(device, engine: model.tandem) } } label: {
                    Label("Remove from Finder", systemImage: "minus.circle")
                }
                .buttonStyle(.tandemGlass)
            }
            Spacer(minLength: 0)
        }
    }

    private func turnOn() {
        guard let engine = model.tandem else { return }
        Task {
            guard await !FilesInFinder.shared.turnOn(device, engine: engine) else { return }
            // The system would not take it: the drive does the same, and it says why once.
            model.showToast(String(localized: "Finder did not take it (\(FilesInFinder.shared.refusal ?? "")), so it opens as a drive"))
            if device.online { model.drives.mount(device: device, engine: engine) }
        }
    }
}

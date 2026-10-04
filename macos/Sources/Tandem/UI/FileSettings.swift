import AppKit
import SwiftUI
import TandemCore

/// What other devices may do with the files of this Mac. The choices are kept, and enforced, by the core, so this page
/// only shows them and changes them.
struct FileSettings: View {
    @Environment(EngineModel.self) private var model

    /// Nil is the choices for all devices; an id is one device.
    @LocalState private var target: String?
    @LocalState private var version = 0
    @LocalState private var activity: [TandemFileActivity] = []

    /// What the limit on a file that is sent here goes through. 0 is no limit.
    private static let limits: [UInt64] = [0, 10_000_000, 100_000_000, 1_000_000_000, 10_000_000_000]

    private var engine: TandemEngine? { model.tandem }

    private var policy: TandemFilePolicy? {
        _ = version
        guard let engine else { return nil }
        if let target { return try? engine.filePolicy(id: target) }
        return engine.fileDefaultPolicy()
    }

    private var own: Bool {
        guard let target else { return true }
        _ = version
        return engine?.hasOwnFilePolicy(id: target) ?? false
    }

    /// A device that follows the choices for all devices shows them, but they are changed under All devices.
    private var editable: Bool { target == nil || own }

    var body: some View {
        Form {
            Section {
                Picker("For", selection: $target) {
                    Text("All devices").tag(String?.none)
                    ForEach(model.devices, id: \.id) { device in
                        Text(device.name).tag(String?.some(device.id))
                    }
                }
                if let target {
                    DescribedToggle(
                        "Own settings for this device",
                        subtitle: "Otherwise it follows the settings for all devices",
                        isOn: Binding(
                            get: { own },
                            set: { wantOwn in
                                if wantOwn {
                                    // It starts from what it follows now, so nothing changes until a person changes it.
                                    if let policy { try? engine?.setFilePolicy(id: target, policy: policy) }
                                } else {
                                    try? engine?.clearFilePolicy(id: target)
                                }
                                version += 1
                            }
                        )
                    )
                }
            }

            if let policy {
                Section("What they may do") {
                    DescribedToggle("Share files", subtitle: "Without this, nothing of this Mac can be seen", isOn: flag(\.enabled))
                    DescribedToggle("Allow changing files", subtitle: "Putting files here, replacing them and renaming them", isOn: flag(\.write))
                    DescribedToggle("Allow deleting files", subtitle: "Removing files and folders from this Mac", isOn: flag(\.delete))
                    DescribedToggle("Show hidden files", subtitle: "Names that start with a dot", isOn: flag(\.hidden))
                    Picker("Largest file they may send", selection: Binding(
                        get: { Self.limits.contains(policy.maxUpload) ? policy.maxUpload : 0 },
                        set: { value in change { $0.maxUpload = value } }
                    )) {
                        ForEach(Self.limits, id: \.self) { limit in
                            Text(limit == 0 ? String(localized: "No limit") : formatBytes(limit)).tag(limit)
                        }
                    }
                }
                .disabled(!editable)

                Section("Folders") {
                    if policy.shares.isEmpty {
                        Text("No folder is shared yet, so other devices see nothing of this Mac.")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                    }
                    ForEach(policy.shares, id: \.name) { share in
                        folderRow(share)
                    }
                    HStack(spacing: 10) {
                        Button("Add a folder…", action: addFolders)
                        Button("Share the usual folders", action: addUsualFolders)
                            .disabled(missingUsualFolders.isEmpty)
                        Spacer(minLength: 0)
                    }
                }
                .disabled(!editable)
            }

            Section("Recent activity") {
                if activity.isEmpty {
                    Text("Nothing yet. Changes other devices make, and what was refused, show up here.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(Array(activity.prefix(12).enumerated()), id: \.offset) { _, item in
                        activityRow(item)
                    }
                }
            }
        }
        .formStyle(.grouped)
        .task {
            // What other devices did while the page is open shows up without leaving it and coming back.
            while !Task.isCancelled {
                activity = engine?.fileActivity() ?? []
                try? await Task.sleep(for: .seconds(3))
            }
        }
        .onChange(of: target) { version += 1 }
    }

    // MARK: Rows

    private func folderRow(_ share: TandemShare) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "folder.fill").foregroundStyle(Palette.indigo)
            VStack(alignment: .leading, spacing: 1) {
                Text(share.name)
                Text(Self.shortPath(share.path))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)
            }
            Spacer(minLength: 12)
            Toggle("Changes allowed", isOn: Binding(
                get: { share.write },
                set: { on in change { policy in policy.shares = policy.shares.map { $0.name == share.name ? TandemShare(name: $0.name, path: $0.path, write: on) : $0 } } }
            ))
            .toggleStyle(.switch)
            .controlSize(.small)
            Button {
                change { policy in policy.shares.removeAll { $0.name == share.name } }
            } label: {
                Image(systemName: "minus.circle.fill").foregroundStyle(.secondary)
            }
            .buttonStyle(.borderless)
            .help("Stop sharing this folder")
        }
    }

    private func activityRow(_ item: TandemFileActivity) -> some View {
        let who = model.devices.first { $0.id == item.device }?.name ?? String(item.device.prefix(6))
        let what: String = switch item.action {
        case "write": String(localized: "Put")
        case "remove": String(localized: "Removed")
        case "mkdir": String(localized: "Made folder")
        case "rename": String(localized: "Moved")
        case "read": String(localized: "Read")
        default: item.action
        }
        let date = Date(timeIntervalSince1970: Double(item.atMs) / 1000)
        let when = Date().timeIntervalSince(date) < 60 ? String(localized: "Just now") : date.formatted(.relative(presentation: .named))
        let refused = item.ok ? "" : ", " + String(localized: "refused: \(item.detail)")
        return VStack(alignment: .leading, spacing: 2) {
            Text("\(what) \(item.path)").lineLimit(2).truncationMode(.middle)
            Text("\(who), \(when)\(refused)").font(.caption).foregroundStyle(item.ok ? Color.secondary : Palette.urgent)
        }
    }

    // MARK: Changing

    private func flag(_ keyPath: WritableKeyPath<TandemFilePolicy, Bool>) -> Binding<Bool> {
        Binding(
            get: { policy?[keyPath: keyPath] ?? false },
            set: { value in change { $0[keyPath: keyPath] = value } }
        )
    }

    private func change(_ edit: (inout TandemFilePolicy) -> Void) {
        guard var next = policy, let engine else { return }
        edit(&next)
        if let target {
            try? engine.setFilePolicy(id: target, policy: next)
        } else {
            try? engine.setFileDefaultPolicy(policy: next)
        }
        version += 1
    }

    private func addFolders() {
        let panel = NSOpenPanel()
        panel.canChooseFiles = false
        panel.canChooseDirectories = true
        panel.allowsMultipleSelection = true
        panel.prompt = String(localized: "Share")
        NSApp.activate(ignoringOtherApps: true)
        guard panel.runModal() == .OK else { return }
        add(panel.urls)
    }

    private func addUsualFolders() {
        add(missingUsualFolders)
    }

    /// Downloads, Documents and Desktop, for the ones that are not shared yet.
    private var missingUsualFolders: [URL] {
        let taken = Set(policy?.shares.map(\.path) ?? [])
        let kinds: [FileManager.SearchPathDirectory] = [.downloadsDirectory, .documentDirectory, .desktopDirectory]
        return kinds.compactMap { FileManager.default.urls(for: $0, in: .userDomainMask).first }
            .filter { !taken.contains($0.path) }
    }

    private func add(_ urls: [URL]) {
        change { policy in
            for url in urls where !policy.shares.contains(where: { $0.path == url.path }) {
                let taken = Set(policy.shares.map(\.name))
                let base = url.lastPathComponent.isEmpty ? "Files" : url.lastPathComponent
                let name = (1...).lazy.map { $0 == 1 ? base : "\(base) \($0)" }.first { !taken.contains($0) } ?? base
                policy.shares.append(TandemShare(name: name, path: url.path, write: true))
            }
        }
        // macOS asks whether Tandem may look into Documents, Desktop and Downloads the first time it does. Looking now
        // puts that question in front of the person who is here, instead of leaving a phone waiting for an answer.
        let paths = urls.map(\.path)
        DispatchQueue.global(qos: .utility).async {
            for path in paths { _ = try? FileManager.default.contentsOfDirectory(atPath: path) }
        }
    }

    private static func shortPath(_ path: String) -> String {
        let home = NSHomeDirectory()
        return path.hasPrefix(home) ? "~" + path.dropFirst(home.count) : path
    }
}

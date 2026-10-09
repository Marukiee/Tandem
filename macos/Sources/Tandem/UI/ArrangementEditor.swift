import SwiftUI
import TandemCore

/// The screens of the computers next to this Mac, drawn the way the display settings of a desktop draw them: this Mac in the middle,
/// the others as boxes that are as big as their screens are, to drag against the side where they sit. A box that is let go near an
/// edge sticks to it (and lines up with the top, the middle or the bottom when it is close); one that is let go far from everything
/// goes back to the row below. The arithmetic is the core's, the same for every app.
struct ArrangementEditor: View {
    @Bindable var share: PointerShare
    let devices: [TandemDevice]

    /// The box that is being dragged, and where its middle is (in the view).
    @LocalState private var dragged: String? = nil
    @LocalState private var middle: CGPoint = .zero

    private let canvasHeight: CGFloat = 250
    private let trayHeight: CGFloat = 78

    private var main: CGSize {
        let size = share.mainSize
        return size.width > 0 ? size : CGSize(width: 1512, height: 982)
    }

    private func size(of device: TandemDevice) -> CGSize {
        share.sizes[device.id] ?? CGSize(width: 1440, height: 900)
    }

    private func sizeKnown(_ device: TandemDevice) -> Bool { share.sizes[device.id] != nil }

    /// The box of a placed screen in the coordinates of the screens (this Mac at the origin).
    private func modelRect(of device: TandemDevice) -> CGRect? {
        guard let placement = share.layout[device.id] else { return nil }
        let size = size(of: device)
        let r = layoutRect(
            mainWidth: Int32(main.width), mainHeight: Int32(main.height),
            placement: TandemPlacement(edge: PointerShare.edge(placement.edge), offset: Int32(share.offset(of: device.id))),
            width: Int32(size.width), height: Int32(size.height)
        )
        return CGRect(x: Double(r.x), y: Double(r.y), width: Double(r.width), height: Double(r.height))
    }

    /// How the screens map to the view: one scale for everything, with this Mac in the middle of the room that is left for the boxes.
    private struct Frame {
        var scale: CGFloat
        var origin: CGPoint

        func view(_ model: CGPoint) -> CGPoint { CGPoint(x: origin.x + model.x * scale, y: origin.y + model.y * scale) }
        func view(_ model: CGRect) -> CGRect {
            CGRect(x: origin.x + model.minX * scale, y: origin.y + model.minY * scale, width: model.width * scale, height: model.height * scale)
        }
        func model(_ point: CGPoint) -> CGPoint { CGPoint(x: (point.x - origin.x) / scale, y: (point.y - origin.y) / scale) }
    }

    private func frame(in available: CGSize) -> Frame {
        // The room: this Mac with room for the biggest screen on every side.
        let biggest = devices.map { size(of: $0) }.reduce(CGSize(width: 1280, height: 800)) { CGSize(width: max($0.width, $1.width), height: max($0.height, $1.height)) }
        let room = CGSize(width: main.width + 2 * biggest.width * 0.92, height: main.height + 2 * biggest.height * 0.92)
        let scale = min(available.width / room.width, (available.height - trayHeight) / room.height)
        let origin = CGPoint(x: (available.width - main.width * scale) / 2, y: (available.height - trayHeight - main.height * scale) / 2)
        return Frame(scale: scale, origin: origin)
    }

    /// Where an unplaced box waits, in the row below.
    private func trayRect(index: Int, count: Int, in available: CGSize) -> CGRect {
        let box = CGSize(width: 112, height: 58)
        let gap: CGFloat = 12
        let total = CGFloat(count) * box.width + CGFloat(max(count - 1, 0)) * gap
        let x = (available.width - total) / 2 + CGFloat(index) * (box.width + gap)
        return CGRect(x: x, y: available.height - trayHeight + (trayHeight - box.height) / 2, width: box.width, height: box.height)
    }

    var body: some View {
        GeometryReader { geometry in
            let available = geometry.size
            let frame = frame(in: available)
            let unplaced = devices.filter { share.layout[$0.id] == nil }
            ZStack(alignment: .topLeading) {
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .fill(Color.primary.opacity(0.05))
                RoundedRectangle(cornerRadius: 14, style: .continuous)
                    .strokeBorder(Color.primary.opacity(0.08))

                // Where the box that is dragged would stick.
                if let id = dragged, let device = devices.first(where: { $0.id == id }), let snap = preview(of: device, frame: frame) {
                    ScreenBox(name: "", detail: "", symbol: nil, main: false, online: true, ghost: true)
                        .frame(width: snap.width, height: snap.height)
                        .position(x: snap.midX, y: snap.midY)
                }

                ScreenBox(name: String(localized: "This Mac"), detail: sizeText(main), symbol: "laptopcomputer", main: true, online: true)
                    .frame(width: main.width * frame.scale, height: main.height * frame.scale)
                    .position(x: frame.origin.x + main.width * frame.scale / 2, y: frame.origin.y + main.height * frame.scale / 2)

                ForEach(devices, id: \.id) { device in
                    let placed = modelRect(of: device).map { frame.view($0) }
                    let index = unplaced.firstIndex(where: { $0.id == device.id })
                    let rest = index.map { trayRect(index: $0, count: unplaced.count, in: available) }
                    let rect = boxRect(for: device, placed: placed, tray: rest, frame: frame)
                    ScreenBox(
                        name: device.name,
                        detail: sizeKnown(device) ? sizeText(size(of: device)) : String(localized: "Size not known yet"),
                        symbol: device.platform.symbol, main: false, online: device.online,
                        dashed: placed == nil && dragged != device.id, lifted: dragged == device.id
                    )
                    .frame(width: rect.width, height: rect.height)
                    .position(x: rect.midX, y: rect.midY)
                    .zIndex(dragged == device.id ? 2 : 1)
                    .gesture(
                        DragGesture(minimumDistance: 2, coordinateSpace: .named("arrangement"))
                            .onChanged { value in
                                dragged = device.id
                                middle = value.location
                            }
                            .onEnded { value in
                                let final = dropRect(for: device, at: value.location, frame: frame)
                                drop(device, rect: final, frame: frame)
                                withAnimation(.tandem) { dragged = nil }
                            }
                    )
                    .animation(dragged == device.id ? nil : .tandem, value: rect)
                }

                if unplaced.isEmpty == false {
                    Text("Drag a screen next to this Mac")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                        .position(x: available.width / 2, y: available.height - trayHeight - 6)
                }
            }
            .coordinateSpace(name: "arrangement")
        }
        .frame(height: canvasHeight + trayHeight)
    }

    // MARK: Boxes

    /// Where a box is drawn: under the pointer while it is dragged, at its place when it is placed, in the row below when it is not.
    private func boxRect(for device: TandemDevice, placed: CGRect?, tray: CGRect?, frame: Frame) -> CGRect {
        if dragged == device.id { return dropRect(for: device, at: middle, frame: frame) }
        return placed ?? tray ?? .zero
    }

    /// The box of a screen as big as it really is (to scale), with its middle at `point`.
    private func dropRect(for device: TandemDevice, at point: CGPoint, frame: Frame) -> CGRect {
        let size = size(of: device)
        let w = size.width * frame.scale
        let h = size.height * frame.scale
        return CGRect(x: point.x - w / 2, y: point.y - h / 2, width: w, height: h)
    }

    /// Where a box that is let go now would stick, as a box in the view.
    private func preview(of device: TandemDevice, frame: Frame) -> CGRect? {
        guard let placement = snap(device, rect: dropRect(for: device, at: middle, frame: frame), frame: frame) else { return nil }
        let size = size(of: device)
        let r = layoutRect(
            mainWidth: Int32(main.width), mainHeight: Int32(main.height), placement: placement,
            width: Int32(size.width), height: Int32(size.height)
        )
        return frame.view(CGRect(x: Double(r.x), y: Double(r.y), width: Double(r.width), height: Double(r.height)))
    }

    private func snap(_ device: TandemDevice, rect: CGRect, frame: Frame) -> TandemPlacement? {
        let model = CGRect(origin: frame.model(rect.origin), size: CGSize(width: rect.width / frame.scale, height: rect.height / frame.scale))
        let others: [TandemNeighbour] = devices.compactMap { other in
            guard other.id != device.id, let placement = share.layout[other.id] else { return nil }
            let size = size(of: other)
            return TandemNeighbour(
                id: other.id,
                placement: TandemPlacement(edge: PointerShare.edge(placement.edge), offset: Int32(share.offset(of: other.id))),
                width: Int32(size.width), height: Int32(size.height)
            )
        }
        return layoutPlace(
            mainWidth: Int32(main.width), mainHeight: Int32(main.height),
            dragged: TandemRect(x: Int32(model.minX), y: Int32(model.minY), width: Int32(model.width), height: Int32(model.height)),
            others: others, snap: Int32(90 / frame.scale)
        )
    }

    private func drop(_ device: TandemDevice, rect: CGRect, frame: Frame) {
        if let placement = snap(device, rect: rect, frame: frame) {
            share.layout[device.id] = PointerShare.Placement(edge: PointerShare.name(of: placement.edge), offset: Int(placement.offset))
        } else {
            share.layout[device.id] = nil
        }
    }

    private func sizeText(_ size: CGSize) -> String { "\(Int(size.width)) × \(Int(size.height))" }
}

/// One screen in the arrangement.
private struct ScreenBox: View {
    let name: String
    let detail: String
    let symbol: String?
    let main: Bool
    let online: Bool
    var ghost = false
    var dashed = false
    var lifted = false

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(main ? AnyShapeStyle(Palette.indigo.opacity(0.85)) : ghost ? AnyShapeStyle(Palette.indigo.opacity(0.12)) : AnyShapeStyle(.regularMaterial))
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .strokeBorder(
                    ghost ? Palette.indigo.opacity(0.6) : lifted ? Palette.indigo : Color.primary.opacity(main ? 0 : 0.22),
                    style: StrokeStyle(lineWidth: ghost || lifted ? 2 : 1, dash: dashed || ghost ? [5, 4] : [])
                )
            if !ghost {
                VStack(spacing: 2) {
                    if let symbol { Image(systemName: symbol).font(.system(size: 15, weight: .semibold)) }
                    Text(name).font(.system(size: 11, weight: .semibold)).lineLimit(1)
                    Text(detail).font(.system(size: 9)).opacity(0.75).lineLimit(1)
                }
                .padding(4)
                .minimumScaleFactor(0.6)
                .foregroundStyle(main ? Color.white : Color.primary)
            }
        }
        .opacity(online || main || ghost ? 1 : 0.55)
        .shadow(color: .black.opacity(lifted ? 0.25 : 0), radius: lifted ? 10 : 0, y: 4)
    }
}

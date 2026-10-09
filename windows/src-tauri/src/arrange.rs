//! Where the screens of the other computers sit next to this one, the way the display settings of a desktop show them. The computers say
//! how big their screens are when they connect; the person drags their boxes against the sides of this screen in the settings, and the
//! shared mouse (`capture.rs`) uses that to know where the pointer goes over and where it comes back. The arithmetic is in the core
//! (`tandem_core::layout`), the same for every app.

use std::collections::HashMap;
use std::sync::{LazyLock, Mutex};

use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tandem_core::ffi::TandemPointerShare;
use tandem_core::layout::{self, Neighbour, Placement, Rect};
use tandem_core::pointer_share::Edge;
use tauri::{AppHandle, Manager};

use crate::{settings, state::AppState};

/// A screen that was put next to this one.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct Spot {
    pub device: String,
    pub edge: String,
    /// How far along that edge the screen starts. Not given: in the middle of the side.
    #[serde(default)]
    pub offset: Option<i32>,
}

static SIZES: LazyLock<Mutex<HashMap<String, (i32, i32)>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

/// A computer said how big its screen is.
pub fn learned(device: &str, width: i32, height: i32) {
    if width > 0 && height > 0 {
        SIZES.lock().unwrap().insert(device.to_string(), (width, height));
    }
}

pub fn size_of(device: &str) -> Option<(i32, i32)> {
    SIZES.lock().unwrap().get(device).copied()
}

/// The size of this computer's screens together, in the units its pointer moves in.
pub fn own_size() -> (i32, i32) {
    #[cfg(windows)]
    {
        let (_, _, w, h) = tandem_winsys::desktop();
        if w > 0 && h > 0 {
            return (w, h);
        }
    }
    #[cfg(target_os = "linux")]
    {
        if tandem_winsys::portal::is_wayland() {
            if let Some(size) = tandem_winsys::portal::logical_screen() {
                return size;
            }
        }
        let (w, h) = tandem_winsys::screen();
        if w > 0 && h > 0 {
            return (w, h);
        }
    }
    (1920, 1080)
}

/// Tells a computer how big the screen of this one is.
pub fn announce(app: &AppHandle, device: &str) {
    let (w, h) = own_size();
    let Ok(engine) = app.state::<AppState>().engine() else { return };
    let device = device.to_string();
    tauri::async_runtime::spawn(async move {
        let _ = engine.send_pointer_share(device, TandemPointerShare::Size { width: w as u32, height: h as u32 }).await;
    });
}

pub fn edge_of(text: &str) -> Option<Edge> {
    match text {
        "left" => Some(Edge::Left),
        "right" => Some(Edge::Right),
        "top" => Some(Edge::Top),
        "bottom" => Some(Edge::Bottom),
        _ => None,
    }
}

pub fn edge_text(edge: Edge) -> &'static str {
    match edge {
        Edge::Left => "left",
        Edge::Right => "right",
        Edge::Top => "top",
        Edge::Bottom => "bottom",
    }
}

fn along_len(edge: Edge, size: (i32, i32)) -> i32 {
    match edge {
        Edge::Left | Edge::Right => size.1,
        Edge::Top | Edge::Bottom => size.0,
    }
}

/// The placement of a spot: the offset that was set, or the middle of the side as far as the sizes are known.
pub fn placement_of(spot: &Spot) -> Option<Placement> {
    let edge = edge_of(&spot.edge)?;
    let main = own_size();
    let size = size_of(&spot.device).unwrap_or(main);
    let offset = spot.offset.unwrap_or_else(|| (along_len(edge, main) - along_len(edge, size)) / 2);
    Some(Placement { edge, offset })
}

fn is_online(app: &AppHandle, id: &str) -> bool {
    app.state::<AppState>().data.lock().unwrap().devices.iter().any(|d| d["id"] == id && d["online"].as_bool().unwrap_or(false))
}

/// The screens next to this one that can be gone to now.
pub fn neighbours(app: &AppHandle, online_only: bool) -> Vec<Neighbour> {
    let main = own_size();
    settings::get(app)
        .layout
        .iter()
        .filter(|spot| !online_only || is_online(app, &spot.device))
        .filter_map(|spot| {
            let placement = placement_of(spot)?;
            let (width, height) = size_of(&spot.device).unwrap_or(main);
            Some(Neighbour { id: spot.device.clone(), placement, width, height })
        })
        .collect()
}

/// Whether a device can sit next to this screen: a computer, or a phone that can take a pointer in.
fn eligible(device: &Value) -> bool {
    match device["platform"].as_str() {
        Some("macos") | Some("windows") | Some("linux") => true,
        Some("android") => device["caps"].as_array().is_some_and(|caps| caps.iter().any(|c| c == "pointer.in")),
        _ => false,
    }
}

fn state_of(app: &AppHandle) -> Value {
    let main = own_size();
    let current = settings::get(app);
    let devices: Vec<Value> = app
        .state::<AppState>()
        .data
        .lock()
        .unwrap()
        .devices
        .iter()
        .filter(|d| eligible(d))
        .map(|d| {
            let id = d["id"].as_str().unwrap_or_default();
            let spot = current.layout.iter().find(|s| s.device == id);
            let size = size_of(id);
            json!({
                "id": id,
                "name": d["name"],
                "platform": d["platform"],
                "online": d["online"],
                "width": size.map(|s| s.0),
                "height": size.map(|s| s.1),
                "placed": spot.and_then(placement_of).map(|p| json!({ "edge": edge_text(p.edge), "offset": p.offset })),
            })
        })
        .collect();
    json!({ "main": { "width": main.0, "height": main.1 }, "devices": devices })
}

#[tauri::command]
pub fn arrange_state(app: AppHandle) -> Value {
    state_of(&app)
}

fn save(app: &AppHandle, change: impl FnOnce(&mut Vec<Spot>)) {
    {
        let state = app.state::<AppState>();
        let mut current = state.settings.lock().unwrap();
        change(&mut current.layout);
    }
    settings::save(app);
    crate::capture::configure(app);
}

/// A box was dragged here (in the coordinates of this screen at the origin): where it sticks, or off when it is too far from this screen.
#[tauri::command]
pub fn arrange_place(app: AppHandle, id: String, x: i32, y: i32, width: i32, height: i32, snap: i32) -> Value {
    let main = own_size();
    let others: Vec<Neighbour> = neighbours(&app, false).into_iter().filter(|n| n.id != id).collect();
    let placed = layout::place(main, Rect { x, y, width, height }, &others, snap);
    save(&app, |list| {
        list.retain(|s| s.device != id);
        if let Some(p) = placed {
            list.push(Spot { device: id.clone(), edge: edge_text(p.edge).into(), offset: Some(p.offset) });
        }
    });
    state_of(&app)
}

/// Where a box that is let go here would stick, without changing anything: for the outline that shows it while the box is dragged.
#[tauri::command]
pub fn arrange_snap(app: AppHandle, id: String, x: i32, y: i32, width: i32, height: i32, snap: i32) -> Value {
    let main = own_size();
    let others: Vec<Neighbour> = neighbours(&app, false).into_iter().filter(|n| n.id != id).collect();
    match layout::place(main, Rect { x, y, width, height }, &others, snap) {
        Some(p) => {
            let r = layout::rect_of(main, p, (width, height));
            json!({ "edge": edge_text(p.edge), "offset": p.offset, "x": r.x, "y": r.y, "width": r.width, "height": r.height })
        }
        None => Value::Null,
    }
}

#[tauri::command]
pub fn arrange_remove(app: AppHandle, id: String) -> Value {
    save(&app, |list| list.retain(|s| s.device != id));
    state_of(&app)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_spot_without_an_offset_is_in_the_middle_of_its_side() {
        learned("pc", 1000, 600);
        let spot = Spot { device: "pc".into(), edge: "right".into(), offset: None };
        let placement = placement_of(&spot).unwrap();
        let main = own_size();
        assert_eq!(placement.offset, (main.1 - 600) / 2);
        let set = Spot { offset: Some(-50), ..spot };
        assert_eq!(placement_of(&set).unwrap().offset, -50);
        assert!(placement_of(&Spot { device: "pc".into(), edge: "sideways".into(), offset: None }).is_none());
    }

    #[test]
    fn only_computers_and_phones_that_take_a_pointer_are_eligible() {
        assert!(eligible(&json!({ "platform": "macos" })));
        assert!(eligible(&json!({ "platform": "linux" })));
        assert!(eligible(&json!({ "platform": "android", "caps": ["pointer.in"] })));
        assert!(!eligible(&json!({ "platform": "android", "caps": ["clipboard"] })));
        assert!(!eligible(&json!({ "platform": "ios" })));
    }
}

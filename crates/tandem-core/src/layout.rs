//! Where the screens of several computers sit next to each other, the way the display settings of a desktop show them: one screen is
//! the main one (the one with the real mouse), the others are boxes around it. This is the arithmetic, once, for every app: where the
//! pointer comes out when it leaves the main screen, where it comes back, and where a box that was dragged lands (snapped to an edge of
//! the main screen).
//!
//! All numbers are in the units the pointer moves in (points on a Mac, pixels elsewhere). The main screen is at (0, 0). A placement is an
//! edge and an offset: the distance of the other screen along that edge from the start of the main one (its top for the left and right
//! edge, its left side for the top and bottom edge). It can be negative: a screen that starts above the main one.

use serde::{Deserialize, Serialize};

use crate::pointer_share::Edge;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Placement {
    pub edge: Edge,
    pub offset: i32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Rect {
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
}

/// A screen next to the main one, with the size it reported.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Neighbour {
    pub id: String,
    pub placement: Placement,
    pub width: i32,
    pub height: i32,
}

fn along_len(edge: Edge, width: i32, height: i32) -> i32 {
    match edge {
        Edge::Left | Edge::Right => height,
        Edge::Top | Edge::Bottom => width,
    }
}

/// The box of a screen of `size` at `placement`, with the main screen of size `main` at the origin.
pub fn rect_of(main: (i32, i32), placement: Placement, size: (i32, i32)) -> Rect {
    let (mw, mh) = main;
    let (w, h) = size;
    match placement.edge {
        Edge::Left => Rect { x: -w, y: placement.offset, width: w, height: h },
        Edge::Right => Rect { x: mw, y: placement.offset, width: w, height: h },
        Edge::Top => Rect { x: placement.offset, y: -h, width: w, height: h },
        Edge::Bottom => Rect { x: placement.offset, y: mh, width: w, height: h },
    }
}

/// The pointer runs into `edge` of the main screen at `position` (along that edge, from its start). Which screen is there, and how far
/// along its own edge (0 to 1) the pointer comes in. `None` where no screen touches that part of the edge: the edge is a wall there.
pub fn cross(edge: Edge, position: i32, list: &[Neighbour]) -> Option<(usize, f32)> {
    list.iter().enumerate().find_map(|(index, n)| {
        if n.placement.edge != edge {
            return None;
        }
        let len = along_len(edge, n.width, n.height).max(1);
        let q = position - n.placement.offset;
        (0..len).contains(&q).then(|| (index, (q as f32 / (len - 1).max(1) as f32).clamp(0.0, 1.0)))
    })
}

/// The pointer comes back from a screen at `placement` and `size`, `along` (0 to 1) of the way along its own edge: where that is on the
/// main screen, along the same edge, kept inside the main screen when the other one is bigger.
pub fn back(main_len: i32, placement: Placement, size: (i32, i32), along: f32) -> i32 {
    let len = along_len(placement.edge, size.0, size.1).max(1);
    let position = placement.offset + (along.clamp(0.0, 1.0) * (len - 1) as f32).round() as i32;
    position.clamp(0, (main_len - 1).max(0))
}

fn overlap(a: (i32, i32), b: (i32, i32)) -> i32 {
    (a.1.min(b.1) - a.0.max(b.0)).max(0)
}

/// A box that was dragged to `dragged` (in the coordinates of the main screen at `main`): the placement it snaps to, or `None` when it is
/// too far from every edge, or does not touch enough of one. `snap` is how far from an edge it may be and still stick.
pub fn place(main: (i32, i32), dragged: Rect, others: &[Neighbour], snap: i32) -> Option<Placement> {
    let (mw, mh) = main;
    let candidates = [
        (Edge::Left, (dragged.x + dragged.width).abs(), (dragged.y, dragged.height), mh),
        (Edge::Right, (dragged.x - mw).abs(), (dragged.y, dragged.height), mh),
        (Edge::Top, (dragged.y + dragged.height).abs(), (dragged.x, dragged.width), mw),
        (Edge::Bottom, (dragged.y - mh).abs(), (dragged.x, dragged.width), mw),
    ];
    let (edge, _, (start, len), main_len) = candidates
        .into_iter()
        .filter(|(_, gap, (start, len), main_len)| *gap <= snap && overlap((*start, *start + *len), (0, *main_len)) >= (*len).min(*main_len) / 4)
        .min_by_key(|(_, gap, _, _)| *gap)?;
    // At least a quarter of the smaller screen has to touch, or the pointer would have nowhere to go through.
    let least = len.min(main_len) / 4;
    let mut offset = start.clamp(least - len, main_len - least);
    // A little pull towards the lines that look right: the starts, the middles and the ends lined up.
    let pull = 40;
    for aligned in [0, (main_len - len) / 2, main_len - len] {
        if (offset - aligned).abs() <= pull {
            offset = aligned;
            break;
        }
    }
    // Two screens do not sit on top of each other: it goes to the nearest free place along the edge.
    let mut taken: Vec<(i32, i32)> = others
        .iter()
        .filter(|n| n.placement.edge == edge)
        .map(|n| (n.placement.offset, n.placement.offset + along_len(edge, n.width, n.height)))
        .collect();
    taken.sort_unstable();
    for _ in 0..taken.len() + 1 {
        let Some(clash) = taken.iter().find(|(a, b)| offset < *b && offset + len > *a) else { break };
        let after = clash.1;
        let before = clash.0 - len;
        // The nearer of the two sides, as far as that still touches the main screen enough.
        let fits = |o: i32| o >= least - len && o <= main_len - least;
        offset = match (fits(before), fits(after)) {
            (true, true) => if (offset - before).abs() <= (after - offset).abs() { before } else { after },
            (true, false) => before,
            (false, true) => after,
            (false, false) => return None,
        };
    }
    Some(Placement { edge, offset })
}

#[cfg(test)]
mod tests {
    use super::*;

    const MAIN: (i32, i32) = (1500, 1000);

    fn neighbour(id: &str, edge: Edge, offset: i32, width: i32, height: i32) -> Neighbour {
        Neighbour { id: id.into(), placement: Placement { edge, offset }, width, height }
    }

    #[test]
    fn a_box_sits_on_the_edge_it_was_placed_on() {
        let right = rect_of(MAIN, Placement { edge: Edge::Right, offset: 100 }, (1920, 1200));
        assert_eq!(right, Rect { x: 1500, y: 100, width: 1920, height: 1200 });
        let top = rect_of(MAIN, Placement { edge: Edge::Top, offset: -200 }, (800, 600));
        assert_eq!(top, Rect { x: -200, y: -600, width: 800, height: 600 });
    }

    #[test]
    fn the_pointer_comes_out_at_the_height_the_screens_share() {
        // A bigger screen to the right, starting 100 above the top of the main one.
        let list = [neighbour("pc", Edge::Right, -100, 1920, 1200)];
        let (index, along) = cross(Edge::Right, 400, &list).unwrap();
        assert_eq!(index, 0);
        // 400 down the main screen is 500 down the other, of 1200.
        assert!((along - 500.0 / 1199.0).abs() < 0.001);
        // The other edges are walls.
        assert_eq!(cross(Edge::Left, 400, &list), None);
    }

    #[test]
    fn where_two_screens_do_not_touch_the_edge_is_a_wall() {
        // A small screen at the bottom of the right edge only: the top part of the edge goes nowhere.
        let list = [neighbour("laptop", Edge::Right, 600, 1280, 400)];
        assert_eq!(cross(Edge::Right, 300, &list), None);
        assert!(cross(Edge::Right, 700, &list).is_some());
        assert_eq!(cross(Edge::Right, 1000, &list), None);
    }

    #[test]
    fn two_screens_on_one_edge_each_have_their_part() {
        let list = [neighbour("a", Edge::Right, 0, 1000, 500), neighbour("b", Edge::Right, 500, 1000, 500)];
        assert_eq!(cross(Edge::Right, 100, &list).unwrap().0, 0);
        assert_eq!(cross(Edge::Right, 700, &list).unwrap().0, 1);
    }

    #[test]
    fn the_way_back_ends_where_it_started() {
        let placement = Placement { edge: Edge::Right, offset: -100 };
        let size = (1920, 1200);
        let (_, along) = cross(Edge::Right, 400, &[neighbour("pc", Edge::Right, -100, 1920, 1200)]).unwrap();
        let there = back(MAIN.1, placement, size, along);
        assert!((there - 400).abs() <= 1, "{there}");
        // Leaving above the main screen is the top of it.
        assert_eq!(back(MAIN.1, placement, size, 0.0), 0);
        assert_eq!(back(MAIN.1, placement, size, 1.0), 999);
    }

    #[test]
    fn a_dragged_box_snaps_to_the_nearest_edge() {
        // Dropped a little away from the right edge, lower than the top by an amount that lines up with nothing.
        let dropped = Rect { x: 1600, y: 80, width: 1000, height: 700 };
        let placed = place(MAIN, dropped, &[], 300).unwrap();
        assert_eq!(placed.edge, Edge::Right);
        assert_eq!(placed.offset, 80);
        // Far away from everything: not placed.
        assert_eq!(place(MAIN, Rect { x: 4000, y: 4000, width: 800, height: 600 }, &[], 300), None);
    }

    #[test]
    fn a_box_lines_up_with_the_top_the_middle_or_the_bottom_when_it_is_close() {
        let near_top = place(MAIN, Rect { x: 1500, y: 20, width: 1000, height: 700 }, &[], 300).unwrap();
        assert_eq!(near_top.offset, 0);
        let near_middle = place(MAIN, Rect { x: 1500, y: 130, width: 1000, height: 700 }, &[], 300).unwrap();
        assert_eq!(near_middle.offset, 150);
        let near_bottom = place(MAIN, Rect { x: 1500, y: 280, width: 1000, height: 700 }, &[], 300).unwrap();
        assert_eq!(near_bottom.offset, 300);
    }

    #[test]
    fn a_box_has_to_touch_the_main_screen() {
        // Above the right edge, with only a sliver along it.
        let sliver = Rect { x: 1500, y: -690, width: 1000, height: 700 };
        assert_eq!(place(MAIN, sliver, &[], 300), None);
    }

    #[test]
    fn a_box_does_not_land_on_another() {
        let others = [neighbour("a", Edge::Right, 0, 1000, 600)];
        // Dropped on top of the first, mostly over its lower half.
        let placed = place(MAIN, Rect { x: 1500, y: 450, width: 1000, height: 400 }, &others, 300).unwrap();
        assert_eq!(placed.edge, Edge::Right);
        assert!(placed.offset >= 600, "{placed:?}");
    }
}

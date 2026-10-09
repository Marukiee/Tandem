//! One mouse and keyboard over several computers, the way Universal Control, Input Leap and lan-mouse do it. The computer
//! that has the real devices (the main one) knows where the screens of the others sit next to its own. When the pointer
//! reaches the edge that touches another screen, the pointer goes there: the main computer hides and freezes its own
//! pointer and sends what the hands do, and the other computer plays it as input. When the pointer on that screen runs
//! into the edge it came in by, it goes back.
//!
//! This file is only the thinking: where the pointer comes out on the other screen, and when it comes back. The apps do
//! the part that belongs to the system (seeing the devices, holding the pointer, playing the input), and each says to the
//! other what happens with [`PointerShareMsg`].

use serde::{Deserialize, Serialize};

/// A side of a screen.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Edge {
    Left,
    Right,
    Top,
    Bottom,
}

impl Edge {
    /// The side of the other screen that touches this one.
    pub fn opposite(self) -> Edge {
        match self {
            Edge::Left => Edge::Right,
            Edge::Right => Edge::Left,
            Edge::Top => Edge::Bottom,
            Edge::Bottom => Edge::Top,
        }
    }
}

/// What the two computers say to each other about the pointer. Added to the protocol as [`crate::proto::Msg::PointerShare`];
/// a device that does not know it skips it.
#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub enum PointerShareMsg {
    /// The pointer of the main computer crossed `edge` of its screen and comes in at the opposite side of yours, `along` of
    /// the way down that side (0.0 to 1.0, from the top or from the left).
    Enter { edge: Edge, along: f32 },
    /// The pointer ran into the edge it came in by and goes back: `along` of the way down that side.
    Leave { along: f32 },
    /// The main computer takes the pointer back by itself (the release key, or the connection is going).
    Release,
    /// Sent every half second by the computer that has the pointer, for as long as it has it. The other one answers with
    /// [`PointerShareMsg::Pong`]. A link that stops answering (a lid that was closed, a network that went) gives the pointer back at once.
    Ping,
    Pong,
    /// How big the screen is that took the pointer in, in the units it moves the pointer in. Sent right after `Enter`, so the main
    /// computer can follow where its pointer is over there and take it home by itself when the other one cannot say.
    Size { width: u32, height: u32 },
    /// Text that was being dragged when the pointer crossed over. It is let go on this computer, at the pointer, when the mouse button
    /// comes up.
    Carry { text: String },
}

/// How many pings in a row may go unanswered before the pointer is taken back, and how often one is sent.
pub const PING_EVERY_MS: u64 = 500;
pub const PING_PATIENCE_MS: u64 = 2000;

/// A screen, in the units its system uses for the pointer (points on a Mac, pixels on Windows).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Screen {
    pub width: f32,
    pub height: f32,
}

/// What a movement of the pointer on the main computer does.
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum Step {
    /// It stays on this screen, at this place.
    Stay { x: f32, y: f32 },
    /// It crossed the edge that touches the other screen, `along` the way down that side.
    Cross { along: f32 },
}

/// The pointer at `(x, y)` moves by `(dx, dy)` on `screen`, and `neighbour` is the side where the other screen sits.
pub fn step(screen: Screen, (x, y): (f32, f32), (dx, dy): (f32, f32), neighbour: Edge) -> Step {
    let (nx, ny) = (x + dx, y + dy);
    let crossed = match neighbour {
        Edge::Left => nx < 0.0,
        Edge::Right => nx > screen.width - 1.0,
        Edge::Top => ny < 0.0,
        Edge::Bottom => ny > screen.height - 1.0,
    };
    if crossed {
        let along = match neighbour {
            Edge::Left | Edge::Right => ny / (screen.height - 1.0).max(1.0),
            Edge::Top | Edge::Bottom => nx / (screen.width - 1.0).max(1.0),
        };
        return Step::Cross { along: along.clamp(0.0, 1.0) };
    }
    Step::Stay { x: nx.clamp(0.0, screen.width - 1.0), y: ny.clamp(0.0, screen.height - 1.0) }
}

/// Where the pointer is on the other screen when it comes in over `edge` of the main one, `along` the way down.
pub fn enter_at(remote: Screen, edge: Edge, along: f32) -> (f32, f32) {
    let along = along.clamp(0.0, 1.0);
    match edge.opposite() {
        Edge::Left => (0.0, along * (remote.height - 1.0)),
        Edge::Right => (remote.width - 1.0, along * (remote.height - 1.0)),
        Edge::Top => (along * (remote.width - 1.0), 0.0),
        Edge::Bottom => (along * (remote.width - 1.0), remote.height - 1.0),
    }
}

/// Where the pointer is on the main screen when it comes back over `edge` (the edge it left by), `along` the way down.
pub fn back_at(screen: Screen, edge: Edge, along: f32) -> (f32, f32) {
    let along = along.clamp(0.0, 1.0);
    match edge {
        Edge::Left => (0.0, along * (screen.height - 1.0)),
        Edge::Right => (screen.width - 1.0, along * (screen.height - 1.0)),
        Edge::Top => (along * (screen.width - 1.0), 0.0),
        Edge::Bottom => (along * (screen.width - 1.0), screen.height - 1.0),
    }
}

/// The side of the computer that is being controlled: it adds up the movements it is sent, from where the pointer came
/// in, and says when the pointer runs into the edge it came in by.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Controlled {
    screen: Screen,
    came_in_by: Edge,
    x: f32,
    y: f32,
    /// How far past the edge the pointer may be pushed before it counts as leaving. Zero where the real position is known; a
    /// little where it is only counted (the movements of a pointer that could not be placed exactly add up wrong by a few pixels).
    margin: f32,
}

impl Controlled {
    /// The pointer arrives over `edge` of the main screen (so it comes in at the opposite side of this one).
    pub fn enter(screen: Screen, edge: Edge, along: f32) -> Controlled {
        let (x, y) = enter_at(screen, edge, along);
        Controlled { screen, came_in_by: edge.opposite(), x, y, margin: 0.0 }
    }

    /// The same, for a pointer that is only followed by counting: it may be pushed `margin` past the edge before it leaves.
    pub fn enter_counting(screen: Screen, edge: Edge, along: f32, margin: f32) -> Controlled {
        Controlled { margin, ..Controlled::enter(screen, edge, along) }
    }

    /// Where the pointer is now, to move the real one to.
    pub fn at(&self) -> (f32, f32) {
        (self.x.clamp(0.0, self.screen.width - 1.0), self.y.clamp(0.0, self.screen.height - 1.0))
    }

    /// A movement of the main computer's hands. `Some(along)` when the pointer ran into the edge it came in by and has to
    /// go back, `None` while it stays on this screen.
    pub fn moved(&mut self, dx: f32, dy: f32) -> Option<f32> {
        let (nx, ny) = (self.x + dx, self.y + dy);
        let m = self.margin;
        let leaving = match self.came_in_by {
            Edge::Left => nx < -m,
            Edge::Right => nx > self.screen.width - 1.0 + m,
            Edge::Top => ny < -m,
            Edge::Bottom => ny > self.screen.height - 1.0 + m,
        };
        if leaving {
            let along = match self.came_in_by {
                Edge::Left | Edge::Right => ny / (self.screen.height - 1.0).max(1.0),
                Edge::Top | Edge::Bottom => nx / (self.screen.width - 1.0).max(1.0),
            };
            return Some(along.clamp(0.0, 1.0));
        }
        // On the axis it came in by the pointer may sit a little outside while it is pushed, so a push is counted from the edge and not lost.
        let (low_x, high_x) = if matches!(self.came_in_by, Edge::Left | Edge::Right) { (-m, self.screen.width - 1.0 + m) } else { (0.0, self.screen.width - 1.0) };
        let (low_y, high_y) = if matches!(self.came_in_by, Edge::Top | Edge::Bottom) { (-m, self.screen.height - 1.0 + m) } else { (0.0, self.screen.height - 1.0) };
        self.x = nx.clamp(low_x, high_x);
        self.y = ny.clamp(low_y, high_y);
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const MAC: Screen = Screen { width: 1512.0, height: 982.0 };
    const PC: Screen = Screen { width: 1920.0, height: 1080.0 };

    #[test]
    fn a_pointer_that_stays_inside_stays() {
        assert_eq!(step(MAC, (100.0, 100.0), (5.0, -3.0), Edge::Right), Step::Stay { x: 105.0, y: 97.0 });
        // The other three edges only stop the pointer.
        assert_eq!(step(MAC, (2.0, 100.0), (-9.0, 0.0), Edge::Right), Step::Stay { x: 0.0, y: 100.0 });
    }

    #[test]
    fn crossing_the_edge_that_touches_the_other_screen_says_where() {
        match step(MAC, (1510.0, 491.0), (8.0, 0.0), Edge::Right) {
            Step::Cross { along } => assert!((along - 0.5).abs() < 0.01),
            other => panic!("expected a crossing, got {other:?}"),
        }
    }

    #[test]
    fn it_comes_out_at_the_same_height_on_a_screen_of_another_size() {
        let (x, y) = enter_at(PC, Edge::Right, 0.5);
        assert_eq!(x, 0.0);
        assert!((y - 539.5).abs() < 0.01);
        let (x, y) = enter_at(PC, Edge::Bottom, 1.0);
        assert_eq!((x, y), (1919.0, 0.0));
    }

    #[test]
    fn the_controlled_computer_hands_the_pointer_back_at_the_edge_it_came_in_by() {
        let mut controlled = Controlled::enter(PC, Edge::Right, 0.25);
        assert_eq!(controlled.at().0, 0.0);
        // Moving into the screen stays.
        assert_eq!(controlled.moved(40.0, 0.0), None);
        assert_eq!(controlled.at().0, 40.0);
        // Moving back out of the left edge goes back, at the height where it left.
        let along = controlled.moved(-100.0, 0.0).expect("it should leave");
        assert!((along - 0.25).abs() < 0.01);
    }

    #[test]
    fn the_other_edges_of_the_controlled_screen_only_stop_the_pointer() {
        let mut controlled = Controlled::enter(PC, Edge::Right, 0.5);
        assert_eq!(controlled.moved(0.0, -5000.0), None);
        assert_eq!(controlled.at().1, 0.0);
        assert_eq!(controlled.moved(0.0, 5000.0), None);
        assert_eq!(controlled.at().1, 1079.0);
    }

    #[test]
    fn a_pointer_that_is_only_counted_may_be_pushed_a_little_before_it_leaves() {
        let mut counted = Controlled::enter_counting(PC, Edge::Right, 0.5, 30.0);
        assert_eq!(counted.moved(10.0, 0.0), None);
        // Back to the edge and a little past it: still there.
        assert_eq!(counted.moved(-25.0, 0.0), None);
        assert_eq!(counted.at().0, 0.0);
        // Pushed past the margin: it leaves, where it was.
        assert!(counted.moved(-20.0, 0.0).is_some());
    }

    #[test]
    fn a_push_against_the_edge_adds_up_until_it_leaves() {
        let mut counted = Controlled::enter_counting(PC, Edge::Right, 0.5, 30.0);
        assert_eq!(counted.moved(-12.0, 0.0), None);
        assert_eq!(counted.moved(-12.0, 0.0), None);
        assert!(counted.moved(-12.0, 0.0).is_some());
    }

    #[test]
    fn it_comes_back_where_it_left() {
        let (x, y) = back_at(MAC, Edge::Right, 0.5);
        assert_eq!(x, 1511.0);
        assert!((y - 490.5).abs() < 0.01);
    }

    #[test]
    fn the_messages_survive_the_wire() {
        for msg in [
            PointerShareMsg::Enter { edge: Edge::Left, along: 0.4 },
            PointerShareMsg::Leave { along: 0.9 },
            PointerShareMsg::Release,
            PointerShareMsg::Ping,
            PointerShareMsg::Pong,
            PointerShareMsg::Size { width: 2560, height: 1600 },
            PointerShareMsg::Carry { text: "een stukje tekst".into() },
        ] {
            let mut bytes = Vec::new();
            ciborium::into_writer(&msg, &mut bytes).unwrap();
            assert_eq!(ciborium::from_reader::<PointerShareMsg, _>(bytes.as_slice()).unwrap(), msg);
        }
    }
}

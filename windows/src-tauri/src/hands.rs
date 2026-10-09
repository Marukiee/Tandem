//! What presses the keys and moves the pointer on this computer. On Windows and under X11 that is the library `enigo`. On a Wayland
//! desktop a program may not do that by itself, so the keys and the pointer go through the portal of the desktop (`tandem_winsys::portal`),
//! which asks the person once and remembers the answer.
//!
//! The portal moves the pointer by a distance and never says where it is. So this keeps count: it puts the pointer against the top left
//! corner of the screen the first time (a move that is far more than the screen is stopped there by the desktop) and adds up every move
//! from there, and again puts it against an edge whenever a place on that edge is asked for, so a small error never grows.

#[cfg(target_os = "linux")]
use enigo::InputError;
use enigo::{Axis, Button, Coordinate, Direction, Enigo, InputResult, Key, Keyboard, Mouse, Settings};

pub enum Driver {
    X(Enigo),
    #[cfg(target_os = "linux")]
    Portal(Portal),
}

impl Driver {
    pub fn new() -> Result<Driver, String> {
        #[cfg(target_os = "linux")]
        {
            if tandem_winsys::portal::is_wayland() {
                if !tandem_winsys::portal::input_available() {
                    return Err("this desktop does not let programs press keys and move the pointer".into());
                }
                return Portal::start().map(Driver::Portal);
            }
        }
        Enigo::new(&Settings::default()).map(Driver::X).map_err(|e| e.to_string())
    }

    /// Whether this can act on the desktop now. A Wayland desktop closes the session of the portal now and then; this opens a new one
    /// (it is remembered, so it does not ask again) and says whether that worked.
    pub fn alive(&mut self) -> bool {
        match self {
            Driver::X(_) => true,
            #[cfg(target_os = "linux")]
            Driver::Portal(p) => p.ensure().is_ok(),
        }
    }

    pub fn move_mouse(&mut self, x: i32, y: i32, coordinate: Coordinate) -> InputResult<()> {
        match self {
            Driver::X(e) => e.move_mouse(x, y, coordinate),
            #[cfg(target_os = "linux")]
            Driver::Portal(p) => p.move_mouse(x, y, coordinate),
        }
    }

    pub fn button(&mut self, button: Button, direction: Direction) -> InputResult<()> {
        match self {
            Driver::X(e) => e.button(button, direction),
            #[cfg(target_os = "linux")]
            Driver::Portal(p) => p.button(button, direction),
        }
    }

    pub fn scroll(&mut self, length: i32, axis: Axis) -> InputResult<()> {
        match self {
            Driver::X(e) => e.scroll(length, axis),
            #[cfg(target_os = "linux")]
            Driver::Portal(p) => p.scroll(length, axis),
        }
    }

    pub fn key(&mut self, key: Key, direction: Direction) -> InputResult<()> {
        match self {
            Driver::X(e) => e.key(key, direction),
            #[cfg(target_os = "linux")]
            Driver::Portal(p) => p.key(key, direction),
        }
    }

    pub fn text(&mut self, text: &str) -> InputResult<()> {
        match self {
            Driver::X(e) => e.text(text),
            #[cfg(target_os = "linux")]
            Driver::Portal(p) => p.text(text),
        }
    }

    pub fn location(&self) -> InputResult<(i32, i32)> {
        match self {
            Driver::X(e) => e.location(),
            #[cfg(target_os = "linux")]
            Driver::Portal(p) => Ok(p.location()),
        }
    }

    pub fn main_display(&self) -> InputResult<(i32, i32)> {
        match self {
            Driver::X(e) => e.main_display(),
            #[cfg(target_os = "linux")]
            Driver::Portal(p) => Ok(p.size()),
        }
    }

    /// Whether the place that `location` gives is real (the system says it) or counted.
    #[cfg_attr(not(target_os = "linux"), allow(dead_code))]
    pub fn knows_location(&self) -> bool {
        match self {
            Driver::X(_) => true,
            #[cfg(target_os = "linux")]
            Driver::Portal(_) => false,
        }
    }
}

#[cfg(target_os = "linux")]
pub use portal_driver::Portal;

#[cfg(target_os = "linux")]
mod portal_driver {
    use super::*;
    use tandem_winsys::portal::{self, Input};

    pub struct Portal {
        input: Input,
        /// Where the pointer is, counted. Only true once `anchored`.
        at: (f64, f64),
        anchored: bool,
        size: (i32, i32),
        /// When a new session was tried last, so a desktop that says no is not asked in a loop.
        tried: Option<std::time::Instant>,
    }

    /// How far past an edge a move goes when it is meant to land on the edge, so that the desktop stops it exactly there.
    const OVERSHOOT: f64 = 64.0;

    impl Portal {
        pub fn start() -> Result<Portal, String> {
            let input = Input::start()?;
            let mut portal = Portal { input, at: (0.0, 0.0), anchored: false, size: (1920, 1080), tried: None };
            portal.size = portal.measure();
            Ok(portal)
        }

        /// The size of the area the pointer moves in.
        fn measure(&self) -> (i32, i32) {
            portal::logical_screen().or_else(|| Some(tandem_winsys::screen()).filter(|s| s.0 > 0 && s.1 > 0)).unwrap_or((1920, 1080))
        }

        pub fn size(&self) -> (i32, i32) {
            self.size
        }

        /// A session that works: the one there is, or a new one when the desktop closed it. The place of the pointer is not known on a
        /// new session, so it is counted again from an edge.
        pub fn ensure(&mut self) -> InputResult<()> {
            if self.input.healthy() {
                return Ok(());
            }
            if self.tried.is_some_and(|t| t.elapsed() < std::time::Duration::from_secs(2)) {
                return Err(gone());
            }
            self.tried = Some(std::time::Instant::now());
            match Input::start() {
                Ok(input) => {
                    self.input = input;
                    self.anchored = false;
                    Ok(())
                }
                Err(error) => {
                    log::warn!("the input session of the desktop could not be opened again: {error}");
                    Err(gone())
                }
            }
        }

        fn checked(&self) -> InputResult<()> {
            if self.input.healthy() { Ok(()) } else { Err(gone()) }
        }

        pub fn location(&self) -> (i32, i32) {
            (self.at.0.round() as i32, self.at.1.round() as i32)
        }

        pub fn move_mouse(&mut self, x: i32, y: i32, coordinate: Coordinate) -> InputResult<()> {
            self.ensure()?;
            let (w, h) = (f64::from(self.size.0), f64::from(self.size.1));
            match coordinate {
                Coordinate::Rel => {
                    self.input.motion(f64::from(x), f64::from(y));
                    self.at = ((self.at.0 + f64::from(x)).clamp(0.0, w - 1.0), (self.at.1 + f64::from(y)).clamp(0.0, h - 1.0));
                }
                Coordinate::Abs => {
                    // The screens may have changed since the last time.
                    if !self.anchored {
                        self.size = self.measure();
                    }
                    let (w, h) = (f64::from(self.size.0), f64::from(self.size.1));
                    let target = (f64::from(x).clamp(0.0, w - 1.0), f64::from(y).clamp(0.0, h - 1.0));
                    if !self.anchored {
                        self.input.slam(true, false, true, false);
                        self.at = (0.0, 0.0);
                        self.anchored = true;
                    }
                    let edge = |value: f64, max: f64| if value <= 0.0 { -OVERSHOOT } else if value >= max - 1.0 { OVERSHOOT } else { 0.0 };
                    let (ex, ey) = (edge(target.0, w), edge(target.1, h));
                    self.input.motion(target.0 - self.at.0 + ex, target.1 - self.at.1 + ey);
                    self.at = target;
                }
            }
            self.checked()
        }

        pub fn button(&mut self, button: Button, direction: Direction) -> InputResult<()> {
            self.ensure()?;
            // The codes of Linux for the buttons of a mouse.
            let code = match button {
                Button::Left => 0x110,
                Button::Right => 0x111,
                Button::Middle => 0x112,
                Button::Back => 0x116,
                Button::Forward => 0x115,
                _ => 0x110,
            };
            match direction {
                Direction::Press => self.input.button(code, true),
                Direction::Release => self.input.button(code, false),
                Direction::Click => {
                    self.input.button(code, true);
                    self.input.button(code, false);
                }
            }
            self.checked()
        }

        pub fn scroll(&mut self, length: i32, axis: Axis) -> InputResult<()> {
            self.ensure()?;
            if length != 0 {
                self.input.wheel(matches!(axis, Axis::Vertical), length);
            }
            self.checked()
        }

        pub fn key(&mut self, key: Key, direction: Direction) -> InputResult<()> {
            self.ensure()?;
            let Some(symbol) = keysym(key) else { return Err(InputError::InvalidInput("a key that has no name on this desktop")) };
            match direction {
                Direction::Press => self.input.key(symbol, true),
                Direction::Release => self.input.key(symbol, false),
                Direction::Click => {
                    self.input.key(symbol, true);
                    self.input.key(symbol, false);
                }
            }
            self.checked()
        }

        pub fn text(&mut self, text: &str) -> InputResult<()> {
            self.ensure()?;
            for c in text.chars() {
                let symbol = if c == '\n' { 0xff0d } else { unicode_keysym(c) };
                self.input.key(symbol, true);
                self.input.key(symbol, false);
            }
            self.checked()
        }
    }

    fn gone() -> InputError {
        InputError::Simulate("the desktop closed the session for keys and the pointer")
    }

    /// The keysym of a character: Latin-1 is itself, everything else is the Unicode number with a flag.
    pub fn unicode_keysym(c: char) -> i32 {
        let n = c as u32;
        if n < 0x100 { n as i32 } else { (0x0100_0000 + n) as i32 }
    }

    /// The X11 keysym of a key of the library, which is what the portal wants.
    pub fn keysym(key: Key) -> Option<i32> {
        Some(match key {
            Key::Unicode(c) => unicode_keysym(c),
            Key::Return => 0xff0d,
            Key::Tab => 0xff09,
            Key::Space => 0x20,
            Key::Backspace => 0xff08,
            Key::Escape => 0xff1b,
            Key::Delete => 0xffff,
            Key::Home => 0xff50,
            Key::LeftArrow => 0xff51,
            Key::UpArrow => 0xff52,
            Key::RightArrow => 0xff53,
            Key::DownArrow => 0xff54,
            Key::PageUp => 0xff55,
            Key::PageDown => 0xff56,
            Key::End => 0xff57,
            Key::F1 => 0xffbe,
            Key::F2 => 0xffbf,
            Key::F3 => 0xffc0,
            Key::F4 => 0xffc1,
            Key::F5 => 0xffc2,
            Key::F6 => 0xffc3,
            Key::F7 => 0xffc4,
            Key::F8 => 0xffc5,
            Key::F9 => 0xffc6,
            Key::F10 => 0xffc7,
            Key::F11 => 0xffc8,
            Key::F12 => 0xffc9,
            Key::Shift => 0xffe1,
            Key::Control => 0xffe3,
            Key::Alt => 0xffe9,
            Key::Meta => 0xffeb,
            Key::CapsLock => 0xffe5,
            Key::MediaPlayPause => 0x1008_ff14,
            Key::MediaNextTrack => 0x1008_ff17,
            Key::MediaPrevTrack => 0x1008_ff16,
            Key::VolumeUp => 0x1008_ff13,
            Key::VolumeDown => 0x1008_ff11,
            Key::VolumeMute => 0x1008_ff12,
            _ => return None,
        })
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn keys_have_the_names_that_x_gives_them() {
            assert_eq!(keysym(Key::Unicode('a')), Some(0x61));
            assert_eq!(keysym(Key::Return), Some(0xff0d));
            assert_eq!(keysym(Key::Meta), Some(0xffeb));
            assert_eq!(keysym(Key::MediaPlayPause), Some(0x1008_ff14));
            // Past Latin-1 a character is its Unicode number with the flag of a Unicode keysym.
            assert_eq!(unicode_keysym('e'), 0x65);
            assert_eq!(unicode_keysym('\u{20ac}'), 0x0100_20ac);
        }
    }
}

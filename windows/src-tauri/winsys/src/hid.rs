//! Keys and buttons of a viewer, turned into the numbers the Linux input layer knows. The viewer sends the USB HID usage of a key (the
//! same on every system); the desktop wants the evdev code of it (the numbers of `linux/input-event-codes.h`).

/// The evdev code of the key with this USB HID usage on the Keyboard page, for the keys a phone or a computer sends: letters, digits,
/// punctuation, the editing keys, the arrows, the function keys and the modifiers.
pub fn evdev_of(usage: u32) -> Option<u16> {
    // KEY_A to KEY_Z are not in alphabetical order (the rows of a keyboard), so they are looked up.
    const LETTERS: [u16; 26] = [30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50, 49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44];
    // KEY_1 to KEY_9, then KEY_0.
    const DIGITS: [u16; 10] = [2, 3, 4, 5, 6, 7, 8, 9, 10, 11];
    match usage {
        0x04..=0x1D => Some(LETTERS[(usage - 0x04) as usize]),
        0x1E..=0x27 => Some(DIGITS[(usage - 0x1E) as usize]),
        0x28 => Some(28),  // Enter
        0x29 => Some(1),   // Escape
        0x2A => Some(14),  // Backspace
        0x2B => Some(15),  // Tab
        0x2C => Some(57),  // Space
        0x2D => Some(12),  // -
        0x2E => Some(13),  // =
        0x2F => Some(26),  // [
        0x30 => Some(27),  // ]
        0x31 => Some(43),  // \
        0x33 => Some(39),  // ;
        0x34 => Some(40),  // '
        0x35 => Some(41),  // `
        0x36 => Some(51),  // ,
        0x37 => Some(52),  // .
        0x38 => Some(53),  // /
        0x39 => Some(58),  // Caps Lock
        0x3A..=0x43 => Some(59 + (usage - 0x3A) as u16), // F1 to F10
        0x44 => Some(87),  // F11
        0x45 => Some(88),  // F12
        0x49 => Some(110), // Insert
        0x4A => Some(102), // Home
        0x4B => Some(104), // Page Up
        0x4C => Some(111), // Delete
        0x4D => Some(107), // End
        0x4E => Some(109), // Page Down
        0x4F => Some(106), // Right
        0x50 => Some(105), // Left
        0x51 => Some(108), // Down
        0x52 => Some(103), // Up
        0xE0 => Some(29),  // Left Control
        0xE1 => Some(42),  // Left Shift
        0xE2 => Some(56),  // Left Alt
        0xE3 => Some(125), // Left Meta
        0xE4 => Some(97),  // Right Control
        0xE5 => Some(54),  // Right Shift
        0xE6 => Some(100), // Right Alt
        0xE7 => Some(126), // Right Meta
        _ => None,
    }
}

/// The evdev code of a button of the pointer: 0 primary, 1 secondary, 2 middle, 3 back, 4 forward.
pub fn button_code(button: u8) -> i32 {
    match button {
        1 => 0x111,
        2 => 0x112,
        3 => 0x113,
        4 => 0x114,
        _ => 0x110,
    }
}

/// The X11 keysym of a character, which is how a piece of text is typed whatever the layout: Latin 1 is itself, anything else is the
/// code point with 0x01000000 added.
pub fn keysym_of_char(c: char) -> i32 {
    let code = c as u32;
    if c == '\n' {
        0xFF0D
    } else if c == '\t' {
        0xFF09
    } else if (0x20..=0x7E).contains(&code) || (0xA0..=0xFF).contains(&code) {
        code as i32
    } else {
        (0x0100_0000 + code) as i32
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn letters_and_digits_are_where_a_keyboard_has_them() {
        assert_eq!(evdev_of(0x04), Some(30)); // A
        assert_eq!(evdev_of(0x14), Some(16)); // Q
        assert_eq!(evdev_of(0x1D), Some(44)); // Z
        assert_eq!(evdev_of(0x1E), Some(2)); // 1
        assert_eq!(evdev_of(0x27), Some(11)); // 0
    }

    #[test]
    fn editing_keys_arrows_and_modifiers_have_codes_and_unknown_ones_do_not() {
        assert_eq!(evdev_of(0x28), Some(28));
        assert_eq!(evdev_of(0x52), Some(103));
        assert_eq!(evdev_of(0x3A), Some(59));
        assert_eq!(evdev_of(0x43), Some(68));
        assert_eq!(evdev_of(0xE1), Some(42));
        assert_eq!(evdev_of(0x32), None);
        assert_eq!(evdev_of(0xFFFF), None);
    }

    #[test]
    fn buttons_map_to_the_codes_of_the_input_layer() {
        assert_eq!((button_code(0), button_code(1), button_code(2)), (0x110, 0x111, 0x112));
        assert_eq!(button_code(9), 0x110);
    }

    #[test]
    fn text_is_typed_by_keysym() {
        assert_eq!(keysym_of_char('a'), 0x61);
        assert_eq!(keysym_of_char('é'), 0xE9);
        assert_eq!(keysym_of_char('€'), 0x0100_20AC);
        assert_eq!(keysym_of_char('\n'), 0xFF0D);
    }
}

//! File names as Windows accepts them. The core already removes separators and colons, but a phone happily makes a file
//! called `What?.txt`, and Windows refuses to create it.

/// The characters Windows forbids become underscores, a name cannot end in a dot or a space, and the name of a device
/// (CON, NUL, COM1 and so on, with any extension) gets an underscore in front.
pub fn windows_safe(name: &str) -> String {
    let mut cleaned: String = name
        .chars()
        .map(|c| if matches!(c, '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*') || c.is_control() { '_' } else { c })
        .collect();
    while cleaned.ends_with('.') || cleaned.ends_with(' ') {
        cleaned.pop();
    }
    if cleaned.is_empty() {
        cleaned.push_str("file");
    }
    if is_device(cleaned.split('.').next().unwrap_or("")) {
        cleaned.insert(0, '_');
    }
    cleaned
}

fn is_device(stem: &str) -> bool {
    let stem = stem.trim_end().to_ascii_uppercase();
    match stem.as_str() {
        "CON" | "PRN" | "AUX" | "NUL" => true,
        _ => {
            let bytes = stem.as_bytes();
            bytes.len() == 4 && (stem.starts_with("COM") || stem.starts_with("LPT")) && (b'1'..=b'9').contains(&bytes[3])
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn forbidden_characters_become_underscores() {
        assert_eq!(windows_safe("What?.txt"), "What_.txt");
        assert_eq!(windows_safe("a<b>c|d*e\"f.png"), "a_b_c_d_e_f.png");
        assert_eq!(windows_safe("tab\there"), "tab_here");
    }

    #[test]
    fn a_name_does_not_end_in_a_dot_or_a_space() {
        assert_eq!(windows_safe("notes. ."), "notes");
        assert_eq!(windows_safe("..."), "file");
        assert_eq!(windows_safe(""), "file");
    }

    #[test]
    fn device_names_are_stepped_around() {
        assert_eq!(windows_safe("con"), "_con");
        assert_eq!(windows_safe("NUL.txt"), "_NUL.txt");
        assert_eq!(windows_safe("com3.log"), "_com3.log");
        assert_eq!(windows_safe("LPT9"), "_LPT9");
        // Not devices.
        assert_eq!(windows_safe("com0"), "com0");
        assert_eq!(windows_safe("console.txt"), "console.txt");
        assert_eq!(windows_safe("com10"), "com10");
    }

    #[test]
    fn ordinary_names_stay() {
        assert_eq!(windows_safe("Begroting 2027 (1).xlsx"), "Begroting 2027 (1).xlsx");
        assert_eq!(windows_safe("IMG_20261003_141201.jpg"), "IMG_20261003_141201.jpg");
    }
}

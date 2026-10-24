//! Calling from this computer: the phone places the call and the person talks on the phone or its headset. Android lets no app carry
//! the sound of a call to another device.

use tauri::State;

use crate::state::AppState;

/// What a phone can dial: digits, a plus in front, and the star and hash of a keypad.
fn clean(text: &str) -> String {
    text.chars().filter(|c| c.is_ascii_digit() || "+*#".contains(*c)).collect()
}

/// Asks the phone `id` to call `number`.
#[tauri::command]
pub async fn dial(state: State<'_, AppState>, id: String, number: String) -> Result<(), String> {
    let number = clean(&number);
    if number.len() < 3 {
        return Err("not a number to call".into());
    }
    state.engine()?.dial(id, number).await.map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    #[test]
    fn only_what_a_keypad_has_is_dialled() {
        assert_eq!(super::clean("+31 (6) 12-34 56 78"), "+31612345678");
        assert_eq!(super::clean("*#06#"), "*#06#");
    }
}

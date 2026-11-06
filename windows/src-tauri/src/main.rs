// No console window behind the app in a release build.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    if let Some(code) = tandem_windows_lib::command() {
        std::process::exit(code);
    }
    tandem_windows_lib::run();
}

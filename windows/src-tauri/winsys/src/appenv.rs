//! The environment that an AppImage sets for itself (where its libraries, plugins, schemas and programs are) is wrong for the programs of the
//! system that it starts: `gst-launch-1.0` then looks for its plugins only inside the AppImage and finds none of the system's, which is how
//! showing the screen of a Wayland desktop failed without a word.

use std::process::Command;

/// What is left of a list of paths (`a:b:c`) when what lies inside the AppImage is taken out. `None` when nothing is left, so the variable
/// can go and the program uses its own defaults.
fn without_app(value: &str, appdir: &str) -> Option<String> {
    let kept: Vec<&str> = value.split(':').filter(|part| !part.is_empty() && !part.starts_with(appdir)).collect();
    (!kept.is_empty()).then(|| kept.join(":"))
}

/// What to change in the environment of a program of the system: the variable and its new value, or `None` to take it away.
pub fn edits() -> Vec<(String, Option<String>)> {
    // These two never belong to a program of the system, AppImage or not.
    let mut changes = vec![("LD_LIBRARY_PATH".to_string(), None), ("LD_PRELOAD".to_string(), None)];
    let Some(appdir) = std::env::var("APPDIR").ok().filter(|d| d.len() > 1) else { return changes };
    for (key, value) in std::env::vars_os() {
        let (Some(key), Some(value)) = (key.to_str(), value.to_str()) else { continue };
        if value.contains(&appdir) {
            changes.push((key.to_string(), without_app(value, &appdir)));
        }
    }
    changes
}

pub fn clean(command: &mut Command) {
    for (key, value) in edits() {
        match value {
            Some(rest) => command.env(key, rest),
            None => command.env_remove(key),
        };
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn what_lies_inside_the_appimage_is_taken_out_of_a_list_of_paths() {
        let dir = "/tmp/.mount_tandemAbC";
        assert_eq!(without_app("/tmp/.mount_tandemAbC/usr/bin/:/usr/bin:/bin", dir), Some("/usr/bin:/bin".to_string()));
        // A list that was only the AppImage's (with the trailing colon that GStreamer's variable has) is gone altogether.
        assert_eq!(without_app("/tmp/.mount_tandemAbC/usr/lib/gstreamer-1.0:", dir), None);
        assert_eq!(without_app("/usr/share:/usr/local/share", dir), Some("/usr/share:/usr/local/share".to_string()));
    }
}

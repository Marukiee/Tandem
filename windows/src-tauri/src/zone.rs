//! The mark Windows gives a downloaded file: the zone of the internet, in a `Zone.Identifier` stream next to the file. Every file that
//! lands from another device gets it, so SmartScreen asks before a program or a shortcut from it starts, and Office opens it protected.

use std::path::Path;

pub fn mark_from_elsewhere(path: &Path) {
    #[cfg(windows)]
    {
        let mut stream = path.as_os_str().to_owned();
        stream.push(":Zone.Identifier");
        // A drive without streams (FAT, some network shares) cannot carry the mark; the file itself is fine.
        if std::fs::write(&stream, "[ZoneTransfer]\r\nZoneId=3\r\n").is_err() {
            log::info!("no zone mark on {}", path.display());
        }
    }
    #[cfg(not(windows))]
    let _ = path;
}

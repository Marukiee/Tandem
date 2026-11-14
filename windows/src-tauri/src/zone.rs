//! The mark Windows gives a downloaded file: the zone of the internet, in a `Zone.Identifier` stream next to the file. A file that lands
//! from another device gets it when it can start something, so SmartScreen asks before a program or a shortcut from it starts. A
//! document, a picture or a video does not: the mark would only make Office open a file from the person's own phone protected.

use std::path::Path;

/// Kinds that start something when opened, rather than show it: programs, installers, scripts, shortcuts, and disk images (a mounted
/// image hands out its files without the mark of the internet). Windows drops dots and spaces at the end of a name, so `x.exe.` is an
/// `.exe` there.
pub fn runs_something(name: &str) -> bool {
    const KINDS: &[&str] = &[
        // Windows
        "exe", "com", "scr", "pif", "bat", "cmd", "ps1", "psm1", "vbs", "vbe", "js", "jse", "wsf", "wsh", "hta", "msi", "msp", "msc",
        "lnk", "url", "reg", "cpl", "inf", "scf", "chm", "appref-ms", "application", "settingcontent-ms", "library-ms", "iso", "img",
        "vhd", "vhdx", "appx", "msix", "appxbundle", "msixbundle",
        // Linux, and what runs on both
        "sh", "bash", "zsh", "csh", "ksh", "fish", "run", "bin", "desktop", "appimage", "deb", "rpm", "flatpak", "flatpakref",
        "flatpakrepo", "snap", "py", "pyw", "pl", "rb", "jar", "elf", "out",
    ];
    let name = name.trim_end_matches(['.', ' ']);
    name.rsplit_once('.').is_some_and(|(stem, ext)| !stem.is_empty() && KINDS.contains(&ext.to_ascii_lowercase().as_str()))
}

pub fn mark_from_elsewhere(path: &Path) {
    #[cfg(windows)]
    {
        if !path.file_name().is_some_and(|name| runs_something(&name.to_string_lossy())) {
            return;
        }
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

#[cfg(test)]
mod tests {
    use super::runs_something;

    #[test]
    fn only_what_can_start_something_gets_the_mark() {
        for name in ["setup.exe", "Setup.EXE", "run.bat", "x.ps1", "link.lnk", "page.hta", "disk.iso", "app.msix", "tool.sh", "x.AppImage", "evil.exe.", "evil.exe "] {
            assert!(runs_something(name), "{name:?} can start something");
        }
        for name in ["Budget.xlsx", "Letter.docx", "Macros.docm", "photo.jpg", "movie.mp4", "song.mp3", "notes.txt", "scan.pdf", "archive.zip", "noextension", ".exe", "exe"] {
            assert!(!runs_something(name), "{name:?} cannot start anything");
        }
    }
}

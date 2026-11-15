//! Folders that travel as folders (P4). The sender opens a folder up into the files inside it, each with the folder it belongs
//! in (`ShareItem::path`), and lists the folders that hold nothing (`ShareOffer::folders`). Only a device with the cap
//! `share.folders` gets such an offer; for the others `send_files` reports them in `SendReport::no_folders`, and the app sends
//! them a zip as before.
//!
//! The receiver pulls each file like any other, then puts it in a folder of its own for the offer under the data folder. When
//! every file of a top folder has ended, that folder goes to the app at once (`FileStore::store_download` with `FOLDER_MIME`),
//! and only then do its files get their `Finished`, with where they are now. A folder that came halfway stays in the data folder
//! until the transfer goes on or ends, so a resume (P2) finds the files that were already here.

use std::collections::{BTreeMap, BTreeSet, HashSet};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use tracing::info;

use crate::engine::Inner;
use crate::error::{Error, Result};
use crate::events::{Event, TransferDone};
use crate::ids::DeviceId;
use crate::journal::{ItemState, Key};
use crate::platform::sanitize_name;
use crate::transfer::{OutgoingFile, REASON_CANCELLED, REASON_NOT_SAVED};

/// A device that takes a folder as it is. An app lists it in its caps (`EngineConfig::caps`) when its `store_download` takes a
/// folder; then this device also sends folders as they are.
pub const FOLDERS_CAP: &str = "share.folders";
/// The type `store_download` gets with a folder that came in whole.
pub const FOLDER_MIME: &str = "inode/directory";
/// Where the folders of incoming offers are put together, in the data folder.
pub const STAGING_DIR: &str = "folders";
/// More files than this in one offer are refused: the offer has to fit in one message.
const MAX_ITEMS: usize = 20_000;

/// One hand-over at a time, so two of them never move the same folder or read the journal before the other wrote to it.
static HANDING: Mutex<()> = Mutex::new(());

/// The files of `files` with each folder among them opened up, and the folders inside that hold nothing. A file that is not a
/// folder stays as it is, with an empty path.
pub(crate) fn open_up(files: Vec<OutgoingFile>) -> Result<(Vec<(OutgoingFile, String)>, Vec<String>)> {
    let mut out = Vec::new();
    let mut empty = Vec::new();
    for file in files {
        let source = Path::new(&file.source);
        let is_dir = std::fs::symlink_metadata(source).map(|m| m.is_dir()).unwrap_or(false);
        if !is_dir {
            out.push((file, String::new()));
            continue;
        }
        let top = match source.file_name().and_then(|n| n.to_str()) {
            Some(name) if file.name.is_empty() => name.to_string(),
            _ if !file.name.is_empty() => file.name.clone(),
            _ => "Folder".to_string(),
        };
        walk(source, &top, &mut out, &mut empty)?;
    }
    Ok((out, empty))
}

fn walk(dir: &Path, rel: &str, out: &mut Vec<(OutgoingFile, String)>, empty: &mut Vec<String>) -> Result<()> {
    let mut entries: Vec<std::fs::DirEntry> = std::fs::read_dir(dir)?.flatten().collect();
    entries.sort_by_key(|entry| entry.file_name());
    let mut holds_something = false;
    for entry in entries {
        let name = entry.file_name().to_string_lossy().into_owned();
        if name == ".DS_Store" {
            continue;
        }
        let Ok(kind) = entry.file_type() else { continue };
        // A link can point outside the folder or back up into it.
        if kind.is_symlink() {
            continue;
        }
        if kind.is_dir() {
            holds_something = true;
            walk(&entry.path(), &format!("{rel}/{name}"), out, empty)?;
        } else if kind.is_file() {
            holds_something = true;
            let size = entry.metadata().map(|m| m.len()).unwrap_or(0);
            let mime = crate::quickshare::service::mime_of(&name).to_string();
            out.push((OutgoingFile { source: entry.path().to_string_lossy().into_owned(), name, size, mime }, rel.to_string()));
        }
        if out.len() + empty.len() > MAX_ITEMS {
            return Err(Error::invalid("too many files to send at once"));
        }
    }
    if !holds_something {
        empty.push(rel.to_string());
    }
    Ok(())
}

/// One part of a path in an offer, made safe the way a file name is: a part like `..` becomes harmless, it never climbs. Also safe
/// on Windows: no trailing dot or space, and no name Windows keeps for a device (`CON`, `NUL`, `COM1`, ...).
pub(crate) fn safe_part(part: &str) -> String {
    let name = sanitize_name(part);
    let name = name.trim_end_matches(['.', ' ']);
    let name = if name.is_empty() { "file" } else { name };
    let stem = name.split('.').next().unwrap_or("").trim_end().to_ascii_uppercase();
    let reserved = matches!(stem.as_str(), "CON" | "PRN" | "AUX" | "NUL")
        || ((stem.starts_with("COM") || stem.starts_with("LPT")) && stem.len() == 4 && stem.as_bytes()[3].is_ascii_digit() && &stem[3..] != "0");
    if reserved { format!("_{name}") } else { name.to_string() }
}

fn safe_parts(path: &str) -> Vec<String> {
    path.split(['/', '\\']).filter(|part| !part.is_empty()).map(safe_part).collect()
}

/// The top folder a path in an offer belongs to, as it is called here.
fn top_of(path: &str) -> Option<String> {
    safe_parts(path).into_iter().next()
}

/// The folder each top folder of an offer waits in. Two tops that differ only in case (`Photos` and `photos` from a Linux sender) would
/// be one folder on a Mac or on Windows, so the second gets a name of its own (`photos (2)`). Worked out from the offer alone, so the
/// file that is put there and the hand-over always agree.
fn staged_tops(paths: &[&str]) -> BTreeMap<String, String> {
    let tops: BTreeSet<String> = paths.iter().filter_map(|path| top_of(path)).collect();
    let mut taken = HashSet::new();
    let mut staged = BTreeMap::new();
    for top in tops {
        let name = std::iter::once(top.clone())
            .chain((2..).map(|n| format!("{top} ({n})")))
            .find(|name| taken.insert(name.to_lowercase()))
            .unwrap_or_else(|| top.clone());
        staged.insert(top, name);
    }
    staged
}

/// Takes a free name in `dir` by creating it empty, so a file that arrives at the same moment never takes the same one (`unique_path`
/// alone looks, then someone else may write).
fn claim(dir: &Path, name: &str) -> std::io::Result<PathBuf> {
    let first = dir.join(name);
    let (stem, ext) = match name.rfind('.') {
        Some(dot) if dot > 0 => (&name[..dot], &name[dot..]),
        _ => (name, ""),
    };
    for candidate in std::iter::once(first).chain((1..10_000).map(|n| dir.join(format!("{stem} ({n}){ext}")))) {
        match std::fs::OpenOptions::new().write(true).create_new(true).open(&candidate) {
            Ok(_) => return Ok(candidate),
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(e),
        }
    }
    Err(std::io::Error::new(std::io::ErrorKind::AlreadyExists, "no free name left"))
}

/// Where a file of a folder waits in `root` until its folder is whole: under the staged name of its top folder (`staged_tops`), each
/// part made safe. Never outside `root`, and never over another file: the name is claimed (created empty) before the file is moved.
pub(crate) fn staged_path(root: &Path, tops: &BTreeMap<String, String>, path: &str, name: &str) -> Result<PathBuf> {
    let parts = safe_parts(path);
    let Some((top, rest)) = parts.split_first() else {
        return Err(Error::invalid("a folder in the offer is not a folder"));
    };
    let mut dir = root.join(tops.get(top).unwrap_or(top));
    dir.extend(rest);
    if !dir.starts_with(root) || dir == root {
        return Err(Error::invalid("a folder in the offer is not a folder"));
    }
    std::fs::create_dir_all(&dir)?;
    Ok(claim(&dir, &safe_part(name))?)
}

/// A file of a folder whose journal entry changes when its folder is handed over or thrown away.
struct Settled {
    index: usize,
    state: ItemState,
    location: Option<String>,
    error: Option<String>,
}

impl Inner {
    pub(crate) fn folders_on(&self) -> bool {
        self.cfg.caps.iter().any(|cap| cap == FOLDERS_CAP)
    }

    pub(crate) fn staging_root(&self, peer: DeviceId, offer: u64) -> PathBuf {
        self.store.dir().join(STAGING_DIR).join(format!("{peer}-{offer:016x}"))
    }

    /// What is put after the name of a folder that did not come whole when it is handed over anyway ("Trip (incomplete)"); the app
    /// sets it in its own language (`set_incomplete_label`).
    fn incomplete_name(&self, name: &str) -> String {
        format!("{name} ({})", self.incomplete_label.read().unwrap())
    }

    /// Moves a file that came in whole into the folder it belongs in, under the folder of its offer. Gives where it is now.
    pub(crate) fn stage_folder_file(&self, peer: DeviceId, offer: u64, path: &str, name: &str, temp: &Path) -> Result<String> {
        let paths = self
            .journal
            .read(Key::In(peer, offer), |record| record.items.iter().map(|i| i.path.clone()).chain(record.folders.iter().cloned()).collect::<Vec<_>>())
            .unwrap_or_default();
        let tops = staged_tops(&paths.iter().map(String::as_str).collect::<Vec<_>>());
        let target = staged_path(&self.staging_root(peer, offer), &tops, path, name)?;
        if let Err(e) = std::fs::rename(temp, &target) {
            let _ = std::fs::remove_file(&target);
            return Err(e.into());
        }
        Ok(target.to_string_lossy().into_owned())
    }

    /// Settles each top folder of an incoming offer of which every file has ended:
    /// - every file arrived: the folder goes to the app under its own name (`store_download` with `FOLDER_MIME`), then each file gets
    ///   its `Finished` with where it is now;
    /// - a file was cancelled (stopped by a person, or thrown away after a restart): the folder is removed, as a half file is, and
    ///   the files that had arrived end as `cancelled`;
    /// - otherwise (a file failed, or the question lapsed): what arrived goes to the app under a marked name, "Trip (incomplete)", so
    ///   nothing disappears and nothing looks whole that is not.
    ///
    /// A folder with a file that has not ended (it can still be resumed) stays where it is. When the offer is no longer in the journal,
    /// what is left is handed over under the marked name. Blocking: the app may copy the folder to another disk, so callers run it on a
    /// blocking thread.
    pub(crate) fn hand_over_folders(&self, peer: DeviceId, offer: u64) {
        let _one_at_a_time = HANDING.lock().unwrap_or_else(|e| e.into_inner());
        let root = self.staging_root(peer, offer);
        let key = Key::In(peer, offer);
        let Some((items, states, folders)) = self.journal.read(key, |record| {
            let states = record.peers.first().map(|p| p.items.clone()).unwrap_or_default();
            (record.items.clone(), states, record.folders.clone())
        }) else {
            if root.exists() {
                self.hand_over_what_is_left(&root);
            }
            return;
        };
        // An offer of nothing but empty folders has no file that would have made the folder of the offer.
        if !folders.is_empty() {
            let _ = std::fs::create_dir_all(&root);
        }
        if !root.exists() {
            return;
        }
        let paths: Vec<&str> = items.iter().map(|item| item.path.as_str()).chain(folders.iter().map(String::as_str)).collect();
        let tops = staged_tops(&paths);
        for (top, staged_name) in &tops {
            let mine: Vec<usize> = (0..items.len()).filter(|&i| top_of(&items[i].path).as_ref() == Some(top)).collect();
            if !mine.iter().all(|&i| states.get(i).is_some_and(|s| s.state.is_final())) {
                continue;
            }
            for folder in folders.iter().filter(|f| top_of(f).as_ref() == Some(top)) {
                let mut dir = root.join(staged_name);
                dir.extend(safe_parts(folder).into_iter().skip(1));
                let _ = std::fs::create_dir_all(dir);
            }
            let staged = root.join(staged_name);
            if !staged.exists() {
                continue;
            }
            let cancelled = mine.iter().any(|&i| states[i].state == ItemState::Cancelled || states[i].error.as_deref() == Some(REASON_CANCELLED));
            let whole = mine.iter().all(|&i| states[i].state == ItemState::Done);
            let done: Vec<usize> = mine.iter().copied().filter(|&i| states[i].state == ItemState::Done).collect();
            let settled: Vec<Settled> = if cancelled {
                info!(%peer, offer, files = done.len(), "a folder that was stopped is thrown away");
                let _ = std::fs::remove_dir_all(&staged);
                done.iter().map(|&index| Settled { index, state: ItemState::Cancelled, location: None, error: Some(REASON_CANCELLED.into()) }).collect()
            } else {
                let name = if whole { staged_name.clone() } else { self.incomplete_name(staged_name) };
                match self.files.store_download(&staged, &name, FOLDER_MIME) {
                    Ok(location) => {
                        info!(%peer, offer, files = done.len(), whole, "a folder that came in went to its place");
                        done.iter()
                            .map(|&index| {
                                let now = states[index].location.as_deref().and_then(|at| Path::new(at).strip_prefix(&staged).ok()).map(|rest| Path::new(&location).join(rest));
                                let now = now.map(|p| p.to_string_lossy().into_owned()).unwrap_or_else(|| location.clone());
                                Settled { index, state: ItemState::Done, location: Some(now), error: None }
                            })
                            .collect()
                    }
                    Err(e) => {
                        info!(%peer, offer, "could not save a folder that came in: {e}");
                        let _ = std::fs::remove_dir_all(&staged);
                        done.iter().map(|&index| Settled { index, state: ItemState::Failed, location: None, error: Some(REASON_NOT_SAVED.into()) }).collect()
                    }
                }
            };
            // One write for the whole folder: a write per file would rewrite the record thousands of times under the journal lock.
            self.journal.change(key, |record| {
                for one in &settled {
                    if let Some(progress) = record.item_mut(&peer, one.index as u32) {
                        progress.state = one.state;
                        progress.location = one.location.clone();
                        progress.error = one.error.clone();
                    }
                }
            });
            for one in settled {
                self.emit(Event::Finished(TransferDone {
                    offer,
                    index: one.index as u32,
                    peer,
                    incoming: true,
                    name: items[one.index].name.clone(),
                    size: items[one.index].size,
                    location: one.location,
                    error: one.error,
                }));
            }
        }
        let _ = std::fs::remove_dir(&root);
    }

    /// `hand_over_folders` on a blocking thread, for callers on the message loop: the app may take a while with a folder.
    pub(crate) fn hand_over_folders_later(self: &Arc<Self>, peer: DeviceId, offer: u64) {
        if !self.staging_root(peer, offer).exists() && self.journal.read(Key::In(peer, offer), |r| r.folders.is_empty()).unwrap_or(true) {
            return;
        }
        let this = self.clone();
        tokio::task::spawn_blocking(move || this.hand_over_folders(peer, offer));
    }

    fn hand_over_what_is_left(&self, root: &Path) {
        if let Ok(entries) = std::fs::read_dir(root) {
            for entry in entries.flatten() {
                let name = self.incomplete_name(&entry.file_name().to_string_lossy());
                if let Err(e) = self.files.store_download(&entry.path(), &name, FOLDER_MIME) {
                    info!("could not save what came of a folder: {e}");
                }
            }
        }
        let _ = std::fs::remove_dir(root);
    }

    /// After a start: folders of which everything ended before the engine stopped are settled, and what is left of an offer that is
    /// no longer in the journal is handed over as incomplete. Nobody listens yet, so the apps read the locations from the journal.
    pub(crate) fn settle_folders(&self) {
        let Ok(entries) = std::fs::read_dir(self.store.dir().join(STAGING_DIR)) else { return };
        let known: Vec<(String, DeviceId, u64)> = self
            .journal
            .records()
            .into_iter()
            .filter(|record| record.incoming)
            .filter_map(|record| record.peers.first().map(|p| (format!("{}-{:016x}", p.device, record.offer), p.device, record.offer)))
            .collect();
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            match known.iter().find(|(dir, _, _)| *dir == name) {
                Some((_, peer, offer)) => self.hand_over_folders(*peer, *offer),
                None => self.hand_over_what_is_left(&entry.path()),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_path_that_wants_out_stays_inside_and_parts_are_names() {
        let root = tempfile::tempdir().unwrap();
        for (path, name) in [("../x", "a"), ("Top/../../etc", "passwd"), ("/abs/olute", "b"), ("Top\\..\\..\\up", "c"), ("Top/a:b", "..")] {
            let at = staged_path(root.path(), &BTreeMap::new(), path, name).unwrap();
            assert!(at.starts_with(root.path()), "{path} {name} went to {at:?}");
            assert!(at.components().all(|c| !matches!(c, std::path::Component::ParentDir)), "{at:?}");
        }
        let fresh = tempfile::tempdir().unwrap();
        assert_eq!(staged_path(fresh.path(), &BTreeMap::new(), "../x", "a").unwrap(), fresh.path().join("file").join("x").join("a"));
        assert!(staged_path(root.path(), &BTreeMap::new(), "", "a").is_err(), "a file of a folder has a folder");
        assert!(staged_path(root.path(), &BTreeMap::new(), "//", "a").is_err());
    }

    #[test]
    fn names_windows_keeps_for_itself_and_trailing_dots_become_safe_names() {
        assert_eq!(safe_part("CON"), "_CON");
        assert_eq!(safe_part("nul.txt"), "_nul.txt");
        assert_eq!(safe_part("com1"), "_com1");
        assert_eq!(safe_part("LPT9.log"), "_LPT9.log");
        assert_eq!(safe_part("aux "), "_aux");
        assert_eq!(safe_part("COM0"), "COM0");
        assert_eq!(safe_part("CONSOLE"), "CONSOLE");
        assert_eq!(safe_part("notes."), "notes");
        assert_eq!(safe_part("trip . "), "trip");
        assert_eq!(safe_part(". ."), "file");
        let root = tempfile::tempdir().unwrap();
        let at = staged_path(root.path(), &BTreeMap::new(), "Trip/CON/x.", "aux.txt").unwrap();
        assert_eq!(at, root.path().join("Trip").join("_CON").join("x").join("_aux.txt"));
    }

    #[test]
    fn tops_that_differ_only_in_case_wait_apart_and_names_are_claimed() {
        let tops = staged_tops(&["Photos/a", "photos/b", "Photos", "PHOTOS/x/y", "Other"]);
        let names: Vec<&str> = tops.values().map(String::as_str).collect();
        let lower: HashSet<String> = names.iter().map(|n| n.to_lowercase()).collect();
        assert_eq!(lower.len(), 4, "{names:?}");
        // The first in sorted order keeps its name, the others get a number.
        assert_eq!((tops["PHOTOS"].as_str(), tops["Photos"].as_str(), tops["photos"].as_str()), ("PHOTOS", "Photos (2)", "photos (3)"));
        assert_eq!(tops["Other"], "Other");
        let root = tempfile::tempdir().unwrap();
        let one = staged_path(root.path(), &tops, "Photos", "a.jpg").unwrap();
        let two = staged_path(root.path(), &tops, "photos", "a.jpg").unwrap();
        assert_ne!(one.parent(), two.parent());
        // The same name twice in one folder: the second never lands on the first.
        let again = staged_path(root.path(), &tops, "Photos", "a.jpg").unwrap();
        assert_ne!(one, again);
    }

    #[test]
    fn a_folder_opens_up_into_its_files_with_their_folders_and_the_empty_ones() {
        let dir = tempfile::tempdir().unwrap();
        let top = dir.path().join("Trip");
        std::fs::create_dir_all(top.join("day 1/raw")).unwrap();
        std::fs::create_dir_all(top.join("empty")).unwrap();
        std::fs::write(top.join("notes.txt"), "n").unwrap();
        std::fs::write(top.join("day 1/a.jpg"), "aa").unwrap();
        std::fs::write(top.join("day 1/raw/b.dng"), "bbb").unwrap();
        std::fs::write(top.join(".DS_Store"), "x").unwrap();
        std::fs::write(dir.path().join("loose.pdf"), "p").unwrap();
        #[cfg(unix)]
        std::os::unix::fs::symlink(dir.path(), top.join("loop")).unwrap();
        let file = |source: &Path| OutgoingFile { source: source.to_string_lossy().into_owned(), name: String::new(), size: 0, mime: String::new() };
        let (files, empty) = open_up(vec![file(&top), file(&dir.path().join("loose.pdf"))]).unwrap();
        let got: Vec<(String, String, u64)> = files.iter().map(|(f, path)| (path.clone(), f.name.clone(), f.size)).collect();
        assert_eq!(
            got,
            vec![
                ("Trip/day 1".into(), "a.jpg".into(), 2),
                ("Trip/day 1/raw".into(), "b.dng".into(), 3),
                ("Trip".into(), "notes.txt".into(), 1),
                ("".into(), "".into(), 0),
            ]
        );
        assert_eq!(files[0].0.mime, "image/jpeg");
        assert_eq!(empty, vec!["Trip/empty".to_string()]);
    }
}

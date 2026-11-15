//! Folders that travel as folders (P4). The sender opens a folder up into the files inside it, each with the folder it belongs
//! in (`ShareItem::path`), and lists the folders that hold nothing (`ShareOffer::folders`). Only a device with the cap
//! `share.folders` gets such an offer; for the others `send_files` reports them in `SendReport::no_folders`, and the app sends
//! them a zip as before.
//!
//! The receiver pulls each file like any other, then puts it in a folder of its own for the offer under the data folder. When
//! every file of a top folder has ended, that folder goes to the app at once (`FileStore::store_download` with `FOLDER_MIME`),
//! and only then do its files get their `Finished`, with where they are now. A folder that came halfway stays in the data folder
//! until the transfer goes on or ends, so a resume (P2) finds the files that were already here.

use std::collections::BTreeSet;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

use tracing::info;

use crate::engine::Inner;
use crate::error::{Error, Result};
use crate::events::{Event, TransferDone};
use crate::ids::DeviceId;
use crate::journal::{ItemState, Key};
use crate::platform::{sanitize_name, unique_path};
use crate::transfer::{OutgoingFile, REASON_NOT_SAVED};

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

/// The parts of a path in an offer, each made safe the way a file name is: a part like `..` becomes harmless, it never climbs.
fn safe_parts(path: &str) -> Vec<String> {
    path.split(['/', '\\']).filter(|part| !part.is_empty()).map(sanitize_name).collect()
}

/// The top folder a path in an offer belongs to, as it is called here.
fn top_of(path: &str) -> Option<String> {
    safe_parts(path).into_iter().next()
}

/// Where a file of a folder waits in `root` until its folder is whole. Never outside `root`, and never over another file.
pub(crate) fn staged_path(root: &Path, path: &str, name: &str) -> Result<PathBuf> {
    let mut dir = root.to_path_buf();
    for part in safe_parts(path) {
        dir.push(part);
    }
    if !dir.starts_with(root) || dir == root {
        return Err(Error::invalid("a folder in the offer is not a folder"));
    }
    std::fs::create_dir_all(&dir)?;
    Ok(unique_path(&dir, &sanitize_name(name)))
}

impl Inner {
    pub(crate) fn folders_on(&self) -> bool {
        self.cfg.caps.iter().any(|cap| cap == FOLDERS_CAP)
    }

    pub(crate) fn staging_root(&self, peer: DeviceId, offer: u64) -> PathBuf {
        self.store.dir().join(STAGING_DIR).join(format!("{peer}-{offer:016x}"))
    }

    /// Moves a file that came in whole into the folder it belongs in, under the folder of its offer. Gives where it is now.
    pub(crate) fn stage_folder_file(&self, peer: DeviceId, offer: u64, path: &str, name: &str, temp: &Path) -> Result<String> {
        let target = staged_path(&self.staging_root(peer, offer), path, name)?;
        std::fs::rename(temp, &target)?;
        Ok(target.to_string_lossy().into_owned())
    }

    /// Gives the app each top folder of an incoming offer of which every file has ended, and then the `Finished` of the files in it.
    /// When the offer is no longer in the journal, whatever came is handed over without a word: received files are never thrown
    /// away. Blocking: the app may copy the folder to another disk.
    pub(crate) fn hand_over_folders(&self, peer: DeviceId, offer: u64) {
        let _one_at_a_time = HANDING.lock().unwrap_or_else(|e| e.into_inner());
        let root = self.staging_root(peer, offer);
        if !root.exists() {
            return;
        }
        let key = Key::In(peer, offer);
        let Some((items, states, folders)) = self.journal.read(key, |record| {
            let states = record.peers.first().map(|p| p.items.clone()).unwrap_or_default();
            (record.items.clone(), states, record.folders.clone())
        }) else {
            self.hand_over_what_is_left(&root);
            return;
        };
        let tops: BTreeSet<String> =
            items.iter().map(|item| item.path.as_str()).chain(folders.iter().map(String::as_str)).filter_map(top_of).collect();
        for top in tops {
            let mine: Vec<usize> = (0..items.len()).filter(|&i| top_of(&items[i].path).as_deref() == Some(top.as_str())).collect();
            if !mine.iter().all(|&i| states.get(i).is_some_and(|s| s.state.is_final())) {
                continue;
            }
            for folder in folders.iter().filter(|f| top_of(f).as_deref() == Some(top.as_str())) {
                let mut dir = root.clone();
                dir.extend(safe_parts(folder));
                let _ = std::fs::create_dir_all(dir);
            }
            let staged = root.join(&top);
            if !staged.exists() {
                continue;
            }
            let done: Vec<usize> = mine.iter().copied().filter(|&i| states[i].state == ItemState::Done).collect();
            match self.files.store_download(&staged, &top, FOLDER_MIME) {
                Ok(location) => {
                    info!(%peer, offer, files = done.len(), "a folder that came in went to its place");
                    for index in done {
                        let now = states[index].location.as_deref().and_then(|at| Path::new(at).strip_prefix(&staged).ok()).map(|rest| Path::new(&location).join(rest));
                        let now = now.map(|p| p.to_string_lossy().into_owned()).unwrap_or_else(|| location.clone());
                        self.journal.change(key, |record| {
                            if let Some(progress) = record.item_mut(&peer, index as u32) {
                                progress.location = Some(now.clone());
                            }
                        });
                        self.emit(Event::Finished(TransferDone {
                            offer,
                            index: index as u32,
                            peer,
                            incoming: true,
                            name: items[index].name.clone(),
                            size: items[index].size,
                            location: Some(now),
                            error: None,
                        }));
                    }
                }
                Err(e) => {
                    info!(%peer, offer, "could not save a folder that came in: {e}");
                    let _ = std::fs::remove_dir_all(&staged);
                    for index in done {
                        self.journal.change(key, |record| {
                            if let Some(progress) = record.item_mut(&peer, index as u32) {
                                progress.state = ItemState::Failed;
                                progress.error = Some(REASON_NOT_SAVED.to_string());
                                progress.location = None;
                            }
                        });
                        self.emit(Event::Finished(TransferDone {
                            offer,
                            index: index as u32,
                            peer,
                            incoming: true,
                            name: items[index].name.clone(),
                            size: items[index].size,
                            location: None,
                            error: Some(REASON_NOT_SAVED.to_string()),
                        }));
                    }
                }
            }
        }
        let _ = std::fs::remove_dir(&root);
    }

    fn hand_over_what_is_left(&self, root: &Path) {
        if let Ok(entries) = std::fs::read_dir(root) {
            for entry in entries.flatten() {
                let name = entry.file_name().to_string_lossy().into_owned();
                if let Err(e) = self.files.store_download(&entry.path(), &name, FOLDER_MIME) {
                    info!("could not save what came of a folder: {e}");
                }
            }
        }
        let _ = std::fs::remove_dir(root);
    }

    /// After a start: folders of which everything ended before the engine stopped go to their place, and what is left of an offer
    /// that is no longer in the journal too. Nobody listens yet, so the apps read the locations from the journal.
    pub(crate) fn settle_folders(self: &Arc<Self>) {
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
            let at = staged_path(root.path(), path, name).unwrap();
            assert!(at.starts_with(root.path()), "{path} {name} went to {at:?}");
            assert!(at.components().all(|c| !matches!(c, std::path::Component::ParentDir)), "{at:?}");
        }
        assert_eq!(staged_path(root.path(), "../x", "a").unwrap(), root.path().join("file").join("x").join("a"));
        assert!(staged_path(root.path(), "", "a").is_err(), "a file of a folder has a folder");
        assert!(staged_path(root.path(), "//", "a").is_err());
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

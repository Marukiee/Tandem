//! What was sent to one device, counted per kind of message, so a phone's battery use can be explained from its log instead of guessed.
//! Only the name of the kind and the size are kept, never what a message says.

use std::collections::HashMap;
use std::fmt::{self, Write};
use std::time::Duration;

use crate::proto::Msg;

/// How often the counts are written to the log, when anything was sent.
pub(crate) const REPORT_EVERY: Duration = Duration::from_secs(300);
/// A line names this many kinds, the biggest first.
const TOP: usize = 8;
/// The longest name of a message kind that is kept.
const NAME_MAX: usize = 28;

/// The name of a kind of message, copied out of the debug text of a message without formatting the rest of it.
#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub(crate) struct Kind {
    name: [u8; NAME_MAX],
    len: u8,
}

impl Kind {
    pub(crate) fn of(msg: &Msg) -> Kind {
        let mut taker = Taker { kind: Kind { name: [0; NAME_MAX], len: 0 } };
        // The derived debug text starts with the name of the variant and is left as soon as the name ends: the error stops the rest
        // from being formatted, so nothing of the message is built or kept.
        let _ = write!(taker, "{msg:?}");
        taker.kind
    }

    pub(crate) fn as_str(&self) -> &str {
        std::str::from_utf8(&self.name[..self.len as usize]).unwrap_or("?")
    }
}

struct Taker {
    kind: Kind,
}

impl Write for Taker {
    fn write_str(&mut self, text: &str) -> fmt::Result {
        for byte in text.bytes() {
            if !byte.is_ascii_alphanumeric() || self.kind.len as usize == NAME_MAX {
                return Err(fmt::Error);
            }
            self.kind.name[self.kind.len as usize] = byte;
            self.kind.len += 1;
        }
        Ok(())
    }
}

#[derive(Default)]
pub(crate) struct SentTally {
    by_kind: HashMap<Kind, (u64, u64)>,
}

impl SentTally {
    pub(crate) fn add(&mut self, msg: &Msg, bytes: usize) {
        let entry = self.by_kind.entry(Kind::of(msg)).or_default();
        entry.0 += 1;
        entry.1 += bytes as u64;
    }

    /// The line for the log, and the counts start again. Nothing when nothing was sent.
    pub(crate) fn take_line(&mut self) -> Option<String> {
        if self.by_kind.is_empty() {
            return None;
        }
        let mut kinds: Vec<(Kind, (u64, u64))> = self.by_kind.drain().collect();
        kinds.sort_by(|a, b| b.1.1.cmp(&a.1.1).then(a.0.as_str().cmp(b.0.as_str())));
        let more = kinds.len().saturating_sub(TOP);
        let mut line = String::new();
        for (kind, (count, bytes)) in kinds.iter().take(TOP) {
            if !line.is_empty() {
                line.push_str(", ");
            }
            let _ = write!(line, "{} {} ({})", kind.as_str(), count, size(*bytes));
        }
        if more > 0 {
            let _ = write!(line, " and {more} more kinds");
        }
        Some(line)
    }
}

fn size(bytes: u64) -> String {
    if bytes < 1000 { format!("{bytes} B") } else { format!("{} kB", (bytes + 500) / 1000) }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::Status;

    #[test]
    fn the_kind_is_the_name_of_the_variant_whatever_it_holds() {
        assert_eq!(Kind::of(&Msg::Ping { nonce: 7 }).as_str(), "Ping");
        assert_eq!(Kind::of(&Msg::Status(Status::default())).as_str(), "Status");
        assert_eq!(Kind::of(&Msg::Unknown).as_str(), "Unknown");
        assert_eq!(Kind::of(&Msg::MediaPlayers { players: Vec::new() }).as_str(), "MediaPlayers");
    }

    #[test]
    fn a_line_has_the_biggest_kinds_first_and_only_names_and_sizes() {
        let mut tally = SentTally::default();
        assert_eq!(tally.take_line(), None);
        tally.add(&Msg::Ping { nonce: 1 }, 20);
        tally.add(&Msg::Ping { nonce: 2 }, 20);
        tally.add(&Msg::Status(Status::default()), 600);
        tally.add(&Msg::MediaPlayers { players: Vec::new() }, 24_000);
        assert_eq!(tally.take_line().as_deref(), Some("MediaPlayers 1 (24 kB), Status 1 (600 B), Ping 2 (40 B)"));
        // It starts again, and a quiet time says nothing.
        assert_eq!(tally.take_line(), None);
    }
}

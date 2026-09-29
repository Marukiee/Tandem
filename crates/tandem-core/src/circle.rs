//! The circle: the set of devices that trust each other.
//!
//! Membership is a set of signed statements ("A adds B", "A removes B") that
//! every device keeps and gossips. Pair a new device with any one member and
//! every other member learns about it the next time they talk. There is no
//! server and no account.
//!
//! Membership is a pure function of the statement set, so two devices holding
//! the same statements always agree on who is in, whatever order the statements
//! arrived in.
//!
//! Timing uses a Lamport counter (`seq`) rather than the wall clock. A device
//! that is removed at counter N can no longer author anything with a counter
//! above N, and an old statement it signed before N stays valid. A stolen
//! device that was removed cannot reach honest devices at all, because they
//! reject its connection; the counter only settles what happened in the window
//! before the removal had spread.

use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};

use crate::error::{Error, Result};
use crate::identity::{Identity, verify_signature};
use crate::ids::{DeviceId, Platform, bytes_array, now_ms};

const CONTEXT: &[u8] = b"tandem-circle-statement-v1";
const MAX_STATEMENTS: usize = 1024;

pub type StatementId = [u8; 16];

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub enum Action {
    Add {
        #[serde(with = "bytes_array")]
        subject: [u8; 32],
        name: String,
        platform: Platform,
    },
    Remove {
        subject: DeviceId,
    },
    Rename {
        subject: DeviceId,
        name: String,
    },
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct Statement {
    #[serde(with = "bytes_array")]
    pub author: [u8; 32],
    pub seq: u64,
    /// Wall clock, for display only. Validity never depends on it.
    pub ts: u64,
    pub action: Action,
    #[serde(with = "bytes_array")]
    pub sig: [u8; 64],
}

#[derive(Serialize)]
struct Unsigned<'a> {
    #[serde(with = "bytes_array")]
    author: &'a [u8; 32],
    seq: u64,
    ts: u64,
    action: &'a Action,
}

impl Statement {
    fn signing_bytes(&self) -> Vec<u8> {
        let mut out = CONTEXT.to_vec();
        ciborium::into_writer(
            &Unsigned {
                author: &self.author,
                seq: self.seq,
                ts: self.ts,
                action: &self.action,
            },
            &mut out,
        )
        .expect("writing to a Vec cannot fail");
        out
    }

    fn create(identity: &Identity, seq: u64, action: Action) -> Statement {
        let mut statement = Statement {
            author: identity.public_key(),
            seq,
            ts: now_ms(),
            action,
            sig: [0u8; 64],
        };
        statement.sig = identity.sign(&statement.signing_bytes());
        statement
    }

    pub fn is_signed_correctly(&self) -> bool {
        verify_signature(&self.author, &self.signing_bytes(), &self.sig)
    }

    pub fn id(&self) -> StatementId {
        let mut bytes = Vec::new();
        ciborium::into_writer(self, &mut bytes).expect("writing to a Vec cannot fail");
        let hash = blake3::hash(&bytes);
        let mut id = [0u8; 16];
        id.copy_from_slice(&hash.as_bytes()[..16]);
        id
    }

    pub fn author_id(&self) -> DeviceId {
        DeviceId::from_public_key(&self.author)
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Member {
    pub id: DeviceId,
    pub public_key: [u8; 32],
    pub name: String,
    pub platform: Platform,
    pub added_by: DeviceId,
    pub added_at: u64,
    /// True when the device that vouched for this one was later removed. The
    /// interface flags these so the owner can review them.
    pub vouched_by_removed: bool,
}

#[derive(Default, Clone)]
struct View {
    root: Option<[u8; 32]>,
    admitted: BTreeMap<DeviceId, Member>,
    removed_at: BTreeMap<DeviceId, u64>,
    name_seq: BTreeMap<DeviceId, u64>,
}

#[derive(Default, Clone)]
pub struct Circle {
    all: BTreeMap<StatementId, Statement>,
    view: View,
}

impl Circle {
    /// A circle of one: this device, vouching for itself.
    pub fn genesis(identity: &Identity, name: &str, platform: Platform) -> Circle {
        let mut circle = Circle::default();
        let statement = Statement::create(
            identity,
            1,
            Action::Add {
                subject: identity.public_key(),
                name: name.to_string(),
                platform,
            },
        );
        circle.insert(statement);
        circle.recompute();
        circle
    }

    /// Rebuilds a circle from statements received from elsewhere. Statements with a
    /// bad signature are dropped.
    pub fn from_statements(statements: impl IntoIterator<Item = Statement>) -> Circle {
        let mut circle = Circle::default();
        for statement in statements {
            if statement.is_signed_correctly() {
                circle.insert(statement);
            }
        }
        circle.recompute();
        circle
    }

    fn insert(&mut self, statement: Statement) -> bool {
        if self.all.len() >= MAX_STATEMENTS {
            return false;
        }
        self.all.insert(statement.id(), statement).is_none()
    }

    /// Merges statements from a peer. Returns true when the membership or the
    /// statement set changed.
    pub fn merge(&mut self, incoming: impl IntoIterator<Item = Statement>) -> bool {
        let mut changed = false;
        for statement in incoming {
            if !statement.is_signed_correctly() {
                continue;
            }
            changed |= self.insert(statement);
        }
        if changed {
            self.recompute();
        }
        changed
    }

    fn next_seq(&self) -> u64 {
        self.all.values().map(|s| s.seq).max().unwrap_or(0) + 1
    }

    pub fn vouch_for(
        &mut self,
        identity: &Identity,
        subject: [u8; 32],
        name: &str,
        platform: Platform,
    ) -> Result<Statement> {
        self.require_member(identity)?;
        let statement = Statement::create(
            identity,
            self.next_seq(),
            Action::Add {
                subject,
                name: name.to_string(),
                platform,
            },
        );
        self.insert(statement.clone());
        self.recompute();
        Ok(statement)
    }

    pub fn remove(&mut self, identity: &Identity, subject: DeviceId) -> Result<Statement> {
        self.require_member(identity)?;
        let statement = Statement::create(identity, self.next_seq(), Action::Remove { subject });
        self.insert(statement.clone());
        self.recompute();
        Ok(statement)
    }

    pub fn rename_self(&mut self, identity: &Identity, name: &str) -> Result<Statement> {
        self.require_member(identity)?;
        let statement = Statement::create(
            identity,
            self.next_seq(),
            Action::Rename {
                subject: identity.id(),
                name: name.to_string(),
            },
        );
        self.insert(statement.clone());
        self.recompute();
        Ok(statement)
    }

    fn require_member(&self, identity: &Identity) -> Result<()> {
        if self.is_member(&identity.public_key()) {
            Ok(())
        } else {
            Err(Error::NotTrusted)
        }
    }

    pub fn statements(&self) -> Vec<Statement> {
        let mut all: Vec<Statement> = self.all.values().cloned().collect();
        all.sort_by_key(|s| (s.seq, s.id()));
        all
    }

    /// A fingerprint of the statement set. Equal digests mean nothing needs syncing.
    pub fn digest(&self) -> [u8; 32] {
        let mut hasher = blake3::Hasher::new();
        for id in self.all.keys() {
            hasher.update(id);
        }
        *hasher.finalize().as_bytes()
    }

    pub fn statement_count(&self) -> u32 {
        self.all.len() as u32
    }

    pub fn root(&self) -> Option<[u8; 32]> {
        self.view.root
    }

    pub fn is_member(&self, public_key: &[u8; 32]) -> bool {
        self.member(&DeviceId::from_public_key(public_key))
            .is_some_and(|m| &m.public_key == public_key)
    }

    pub fn member(&self, id: &DeviceId) -> Option<&Member> {
        if self.view.removed_at.contains_key(id) {
            return None;
        }
        self.view.admitted.get(id)
    }

    pub fn members(&self) -> Vec<Member> {
        self.view
            .admitted
            .values()
            .filter(|m| !self.view.removed_at.contains_key(&m.id))
            .cloned()
            .collect()
    }

    pub fn is_removed(&self, id: &DeviceId) -> bool {
        self.view.removed_at.contains_key(id)
    }

    /// Only this device is in the circle, so it is free to join another one.
    pub fn is_alone(&self, me: &DeviceId) -> bool {
        let members = self.members();
        members.len() == 1 && members[0].id == *me
    }

    fn recompute(&mut self) {
        let mut ordered: Vec<&Statement> = self.all.values().collect();
        ordered.sort_by_key(|s| (s.seq, s.id()));

        let mut view = View::default();
        // The statements depend on each other, so iterate until the answer stops
        // changing. The sets are tiny, so this costs nothing.
        for _ in 0..16 {
            let next = derive(&ordered, &view.removed_at);
            let stable = next.removed_at == view.removed_at && next.admitted == view.admitted;
            view = next;
            if stable {
                break;
            }
        }

        let removed: BTreeSet<DeviceId> = view.removed_at.keys().copied().collect();
        for member in view.admitted.values_mut() {
            member.vouched_by_removed = removed.contains(&member.added_by);
        }
        self.view = view;
    }
}

/// One pass over the statements, given the removals known so far.
fn derive(ordered: &[&Statement], known_removed: &BTreeMap<DeviceId, u64>) -> View {
    let mut view = View::default();

    // A device may only author while it is admitted and before its own removal.
    fn may_author(view: &View, known_removed: &BTreeMap<DeviceId, u64>, author: &DeviceId, seq: u64) -> bool {
        view.admitted.contains_key(author)
            && known_removed.get(author).is_none_or(|&removed| seq <= removed)
    }

    for statement in ordered {
        let author = statement.author_id();
        match &statement.action {
            Action::Add { subject, name, platform } => {
                let subject_id = DeviceId::from_public_key(subject);
                if *subject == statement.author {
                    // Only the very first self-vouch is a valid root. Any other
                    // device creating its own circle is a different circle.
                    if view.root.is_none() {
                        view.root = Some(*subject);
                        view.admitted.insert(
                            subject_id,
                            Member {
                                id: subject_id,
                                public_key: *subject,
                                name: name.clone(),
                                platform: *platform,
                                added_by: subject_id,
                                added_at: statement.ts,
                                vouched_by_removed: false,
                            },
                        );
                        view.name_seq.insert(subject_id, statement.seq);
                    }
                } else if may_author(&view, known_removed, &author, statement.seq)
                    && !view.admitted.contains_key(&subject_id)
                {
                    view.admitted.insert(
                        subject_id,
                        Member {
                            id: subject_id,
                            public_key: *subject,
                            name: name.clone(),
                            platform: *platform,
                            added_by: author,
                            added_at: statement.ts,
                            vouched_by_removed: false,
                        },
                    );
                    view.name_seq.insert(subject_id, statement.seq);
                }
            }
            Action::Remove { subject } => {
                if may_author(&view, known_removed, &author, statement.seq) {
                    let entry = view.removed_at.entry(*subject).or_insert(statement.seq);
                    *entry = (*entry).min(statement.seq);
                }
            }
            Action::Rename { subject, name } => {
                if *subject == author
                    && may_author(&view, known_removed, &author, statement.seq)
                    && view.name_seq.get(subject).is_none_or(|&seq| statement.seq > seq)
                {
                    if let Some(member) = view.admitted.get_mut(subject) {
                        member.name = name.clone();
                    }
                    view.name_seq.insert(*subject, statement.seq);
                }
            }
        }
    }
    view
}

#[cfg(test)]
mod tests {
    use super::*;

    fn device(name: &str) -> (Identity, String) {
        (Identity::generate().unwrap(), name.to_string())
    }

    #[test]
    fn genesis_is_a_circle_of_one() {
        let (a, _) = device("a");
        let circle = Circle::genesis(&a, "Phone", Platform::Android);
        assert!(circle.is_member(&a.public_key()));
        assert!(circle.is_alone(&a.id()));
        assert_eq!(circle.members().len(), 1);
    }

    #[test]
    fn a_member_can_vouch_for_a_new_device() {
        let (a, _) = device("a");
        let (b, _) = device("b");
        let mut circle = Circle::genesis(&a, "Phone", Platform::Android);
        circle.vouch_for(&a, b.public_key(), "Mac", Platform::MacOs).unwrap();
        assert!(circle.is_member(&b.public_key()));
        assert_eq!(circle.member(&b.id()).unwrap().name, "Mac");
    }

    #[test]
    fn a_stranger_cannot_vouch() {
        let (a, _) = device("a");
        let (stranger, _) = device("s");
        let (victim, _) = device("v");
        let mut circle = Circle::genesis(&a, "Phone", Platform::Android);
        assert!(matches!(
            circle.vouch_for(&stranger, victim.public_key(), "x", Platform::Linux),
            Err(Error::NotTrusted)
        ));

        // A hand-built statement from a stranger is ignored as well.
        let forged = Statement::create(
            &stranger,
            9,
            Action::Add { subject: victim.public_key(), name: "x".into(), platform: Platform::Linux },
        );
        let mut other = circle.clone();
        other.merge([forged]);
        assert!(!other.is_member(&victim.public_key()));
    }

    #[test]
    fn tampered_statements_are_dropped() {
        let (a, _) = device("a");
        let (b, _) = device("b");
        let mut circle = Circle::genesis(&a, "Phone", Platform::Android);
        let mut statement = circle.vouch_for(&a, b.public_key(), "Mac", Platform::MacOs).unwrap();
        if let Action::Add { name, .. } = &mut statement.action {
            *name = "Evil".into();
        }
        let rebuilt = Circle::from_statements(vec![circle.statements()[0].clone(), statement]);
        assert!(!rebuilt.is_member(&b.public_key()));
    }

    #[test]
    fn order_of_arrival_does_not_matter() {
        let (a, _) = device("a");
        let (b, _) = device("b");
        let (c, _) = device("c");
        let mut circle = Circle::genesis(&a, "Phone", Platform::Android);
        circle.vouch_for(&a, b.public_key(), "Mac", Platform::MacOs).unwrap();
        circle.vouch_for(&b, c.public_key(), "Laptop", Platform::Linux).unwrap();
        let mut statements = circle.statements();
        statements.reverse();
        let rebuilt = Circle::from_statements(statements);
        assert!(rebuilt.is_member(&c.public_key()));
        assert_eq!(rebuilt.digest(), circle.digest());
    }

    #[test]
    fn removal_is_permanent_and_blocks_later_statements() {
        let (a, _) = device("a");
        let (b, _) = device("b");
        let (c, _) = device("c");
        let mut circle = Circle::genesis(&a, "Phone", Platform::Android);
        circle.vouch_for(&a, b.public_key(), "Mac", Platform::MacOs).unwrap();
        circle.remove(&a, b.id()).unwrap();
        assert!(!circle.is_member(&b.public_key()));
        assert!(circle.is_removed(&b.id()));

        // B is gone, so anything it signs afterwards means nothing.
        let late = Statement::create(
            &b,
            circle.next_seq(),
            Action::Add { subject: c.public_key(), name: "c".into(), platform: Platform::Linux },
        );
        circle.merge([late]);
        assert!(!circle.is_member(&c.public_key()));
    }

    #[test]
    fn statements_from_before_the_removal_survive_it() {
        let (a, _) = device("a");
        let (b, _) = device("b");
        let (c, _) = device("c");
        let mut circle = Circle::genesis(&a, "Phone", Platform::Android);
        circle.vouch_for(&a, b.public_key(), "Mac", Platform::MacOs).unwrap();
        circle.vouch_for(&b, c.public_key(), "Laptop", Platform::Linux).unwrap();
        circle.remove(&a, b.id()).unwrap();
        assert!(circle.is_member(&c.public_key()));
        assert!(circle.member(&c.id()).unwrap().vouched_by_removed);
    }

    #[test]
    fn two_devices_converge_after_exchanging_statements() {
        let (a, _) = device("a");
        let (b, _) = device("b");
        let (c, _) = device("c");
        let (d, _) = device("d");
        let mut on_a = Circle::genesis(&a, "Phone", Platform::Android);
        on_a.vouch_for(&a, b.public_key(), "Mac", Platform::MacOs).unwrap();
        let mut on_b = Circle::from_statements(on_a.statements());

        // Both add a device while apart.
        on_a.vouch_for(&a, c.public_key(), "Laptop", Platform::Linux).unwrap();
        on_b.vouch_for(&b, d.public_key(), "Tablet", Platform::Android).unwrap();
        assert_ne!(on_a.digest(), on_b.digest());

        let from_a = on_a.statements();
        let from_b = on_b.statements();
        on_a.merge(from_b);
        on_b.merge(from_a);
        assert_eq!(on_a.digest(), on_b.digest());
        assert_eq!(on_a.members().len(), 4);
        assert_eq!(on_b.members().len(), 4);
    }

    #[test]
    fn a_second_self_signed_circle_is_not_accepted() {
        let (a, _) = device("a");
        let (x, _) = device("x");
        let mut circle = Circle::genesis(&a, "Phone", Platform::Android);
        let foreign = Circle::genesis(&x, "Other", Platform::Linux);
        circle.merge(foreign.statements());
        assert!(!circle.is_member(&x.public_key()));
    }

    #[test]
    fn rename_only_works_on_yourself() {
        let (a, _) = device("a");
        let (b, _) = device("b");
        let mut circle = Circle::genesis(&a, "Phone", Platform::Android);
        circle.vouch_for(&a, b.public_key(), "Mac", Platform::MacOs).unwrap();
        circle.rename_self(&b, "Work Mac").unwrap();
        assert_eq!(circle.member(&b.id()).unwrap().name, "Work Mac");

        let vandal = Statement::create(
            &a,
            circle.next_seq(),
            Action::Rename { subject: b.id(), name: "Hacked".into() },
        );
        circle.merge([vandal]);
        assert_eq!(circle.member(&b.id()).unwrap().name, "Work Mac");
    }
}

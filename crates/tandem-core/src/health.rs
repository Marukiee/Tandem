//! Sleep and workouts measured by a watch. The watch hands them to its phone (`Engine::add_health_records`). The phone keeps them and
//! passes them on to the devices the person chose, one by one (`DeviceSettings::health`, off until they say so): how someone slept is
//! not for every Hub or shared computer in a circle. Another phone always gets them: the watch can go from one phone to the other, and
//! what it measured stays the person's on the phone it is with now. For the same reason the choice for a computer is one choice for
//! all the phones (`Msg::HealthChoices`): the newest counts, so a computer keeps getting them when the watch moves.
//!
//! Only the device that took a record from the watch passes it on. On every connection a device says how far it got with the records
//! of the other one (`Msg::HealthHave`) and hears what came after. A device that may not have them any more is told to forget them
//! (`Msg::HealthForget`), also when it was away at the moment the person switched it off. What came from a device that left the circle
//! goes from a computer as soon as it hears it: nobody is left to say forget. A phone keeps it as its own and passes it on from then on,
//! so the nights stay the person's when an old phone goes.

use std::collections::HashSet;

use serde::{Deserialize, Serialize};

use crate::engine::{Engine, Inner};
use crate::error::Result;
use crate::events::Event;
use crate::ids::{DeviceId, Platform, now_ms};
use crate::proto::Msg;
use crate::store::{DeviceSettings, Store};

/// The device knows the health messages.
pub const CAP: &str = "health";

const FILE: &str = "health.cbor";
const CHOICES: &str = "health_choices.cbor";
/// A night, a workout and a day of steps for two years. Beyond this the oldest go, so the file cannot keep growing.
const MAX_RECORDS: usize = 2200;
/// A night is at most a few kilobytes, so this stays far under what one message may hold, also for a first sync of everything.
const BATCH: usize = 50;

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum HealthKind {
    Sleep,
    Workout,
    /// The steps and heart rate of one whole day.
    Day,
    /// From a newer version.
    #[serde(other)]
    Unknown,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SleepStage {
    Wake,
    Light,
    Deep,
    Rem,
    /// From a newer version.
    #[serde(other)]
    Unknown,
}

/// A stretch of one stage, in the order of the night.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct StageRun {
    pub stage: SleepStage,
    pub seconds: u32,
}

/// One night or one workout. It never changes once it is made, so its id is all two devices need to agree on.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct HealthRecord {
    pub id: String,
    pub kind: HealthKind,
    /// Milliseconds since 1970, by the clock of the watch.
    pub start_ms: u64,
    pub end_ms: u64,
    #[serde(default)]
    pub sleep_minutes: u32,
    #[serde(default)]
    pub awake_minutes: u32,
    #[serde(default)]
    pub awakenings: u32,
    #[serde(default)]
    pub efficiency_percent: u32,
    /// The minutes per stage: None when the heart rate was missing too much to tell them apart.
    #[serde(default)]
    pub light_minutes: Option<u32>,
    #[serde(default)]
    pub deep_minutes: Option<u32>,
    #[serde(default)]
    pub rem_minutes: Option<u32>,
    /// Empty for the same reason, and for a workout.
    #[serde(default)]
    pub stages: Vec<StageRun>,
    #[serde(default)]
    pub heart_rate_mean: Option<u32>,
    #[serde(default)]
    pub heart_rate_low: Option<u32>,
    #[serde(default)]
    pub heart_rate_high: Option<u32>,
    #[serde(default)]
    pub steps: u64,
    /// Minutes of brisk walking, for a day.
    #[serde(default)]
    pub active_minutes: u32,
    /// For a day: the heart rate per five minutes from midnight on, 0 where nothing was measured.
    #[serde(default)]
    pub heart_curve: Vec<u8>,
    /// For a day: the steps per hour from midnight on. Empty from a watch from before.
    #[serde(default)]
    pub step_curve: Vec<u32>,
    /// When the device that passes it on got it, by that device's clock and never the same twice. How far another device got.
    #[serde(default)]
    pub added_ms: u64,
    /// The device this one heard it from. None when this device took it from the watch itself.
    #[serde(default)]
    pub origin: Option<DeviceId>,
}

/// What the person chose on one of their phones for a device that is not a phone, and when, by the clock of that phone.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct HealthChoice {
    pub device: DeviceId,
    pub on: bool,
    pub at_ms: u64,
}

#[derive(Clone, Default)]
pub(crate) struct Health {
    records: Vec<HealthRecord>,
    choices: Vec<HealthChoice>,
}

impl Health {
    pub(crate) fn load(store: &Store) -> Health {
        Health {
            records: store.read_cbor(FILE).ok().flatten().unwrap_or_default(),
            choices: store.read_cbor(CHOICES).ok().flatten().unwrap_or_default(),
        }
    }

    /// The choice for `device` as this device knows it. A switch turned on before the phones passed their choices on counts as the
    /// oldest choice there is, so it wins over one never made and loses to any made since.
    fn choice(&self, device: &DeviceId, on_before: bool) -> HealthChoice {
        self.choices.iter().find(|c| c.device == *device).copied().unwrap_or(HealthChoice {
            device: *device,
            on: on_before,
            at_ms: u64::from(on_before),
        })
    }

    /// Keeps `choice` when it is newer than what this device knows. Returns whether it did.
    fn take_choice(&mut self, choice: HealthChoice, on_before: bool) -> bool {
        if choice.at_ms <= self.choice(&choice.device, on_before).at_ms {
            return false;
        }
        self.choices.retain(|c| c.device != choice.device);
        self.choices.push(choice);
        true
    }

    /// Records from a watch connected to this device. Returns the ones that were new here.
    fn add_mine(&mut self, records: Vec<HealthRecord>, now: u64) -> Vec<HealthRecord> {
        let mut last = self.records.iter().filter(|r| r.origin.is_none()).map(|r| r.added_ms).max().unwrap_or(0);
        let mut fresh = Vec::new();
        for mut record in records {
            if self.records.iter().any(|r| r.id == record.id) {
                continue;
            }
            last = now.max(last + 1);
            record.added_ms = last;
            record.origin = None;
            self.records.push(record.clone());
            fresh.push(record);
        }
        self.trim();
        fresh
    }

    /// Records from another device. Returns whether anything is new here.
    fn received(&mut self, from: DeviceId, records: Vec<HealthRecord>) -> bool {
        let mut changed = false;
        for mut record in records {
            record.origin = Some(from);
            match self.records.iter_mut().find(|r| r.id == record.id) {
                // One this device took from the watch itself stays that way.
                Some(known) if known.origin.is_none() => {}
                Some(known) if *known == record => {}
                // The same record by way of a new phone: it is kept with where it comes from now, or that phone would send it each time.
                Some(known) => {
                    *known = record;
                    changed = true;
                }
                None => {
                    self.records.push(record);
                    changed = true;
                }
            }
        }
        self.trim();
        changed
    }

    fn newest_from(&self, from: DeviceId) -> u64 {
        self.records.iter().filter(|r| r.origin == Some(from)).map(|r| r.added_ms).max().unwrap_or(0)
    }

    /// What this device passes on, oldest first, so a connection that drops halfway leaves a clean "how far".
    fn mine_after(&self, after: u64) -> Vec<HealthRecord> {
        let mut mine: Vec<HealthRecord> = self.records.iter().filter(|r| r.origin.is_none() && r.added_ms > after).cloned().collect();
        mine.sort_by_key(|r| r.added_ms);
        mine
    }

    fn forget_from(&mut self, from: DeviceId) -> bool {
        let before = self.records.len();
        self.records.retain(|r| r.origin != Some(from));
        self.records.len() != before
    }

    /// Only what came from `wanted`. With `keep`, what came from another device becomes this device's own instead of going, in the order
    /// it came, and is returned to be passed on.
    fn keep_only_from(&mut self, wanted: &HashSet<DeviceId>, keep: bool, now: u64) -> (bool, Vec<HealthRecord>) {
        let gone = |r: &HealthRecord| r.origin.is_some_and(|from| !wanted.contains(&from));
        if !keep {
            let before = self.records.len();
            self.records.retain(|r| !gone(r));
            return (self.records.len() != before, Vec::new());
        }
        let mut last = self.records.iter().filter(|r| r.origin.is_none()).map(|r| r.added_ms).max().unwrap_or(0);
        let mut left: Vec<&mut HealthRecord> = self.records.iter_mut().filter(|r| gone(r)).collect();
        left.sort_by_key(|r| r.added_ms);
        let mut kept = Vec::new();
        for record in left {
            last = now.max(last + 1);
            record.added_ms = last;
            record.origin = None;
            kept.push(record.clone());
        }
        (!kept.is_empty(), kept)
    }

    fn trim(&mut self) {
        if self.records.len() > MAX_RECORDS {
            self.records.sort_by_key(|r| r.start_ms);
            let extra = self.records.len() - MAX_RECORDS;
            self.records.drain(..extra);
        }
    }
}

/// Whether a device knows the health messages, from what its hello says it can do.
pub fn speaks(caps: &[String]) -> bool {
    caps.iter().any(|c| c == CAP)
}

impl Engine {
    /// Every night and workout this device has, the newest first.
    pub fn health_records(&self) -> Vec<HealthRecord> {
        let mut records = self.inner.health.lock().unwrap().records.clone();
        records.sort_by_key(|r| std::cmp::Reverse(r.start_ms));
        records
    }

    /// Records a watch connected to this device made. When this returns they are safe on disk, so the watch may let go of them.
    pub async fn add_health_records(&self, records: Vec<HealthRecord>) -> Result<()> {
        let fresh = {
            let mut health = self.inner.health.lock().unwrap();
            let mut next = health.clone();
            let fresh = next.add_mine(records, now_ms());
            if !fresh.is_empty() {
                self.inner.store.write_cbor(FILE, &next.records)?;
                *health = next;
            }
            fresh
        };
        if !fresh.is_empty() {
            self.inner.emit(Event::HealthChanged);
            for id in self.inner.health_readers() {
                self.inner.health_send(&id, &fresh).await;
            }
        }
        Ok(())
    }

    /// Whether `id` gets the nights and workouts of this device: another phone always, any other device when the person chose it.
    pub fn health_allowed(&self, id: &DeviceId) -> bool {
        self.inner.health_allowed(id)
    }

    /// Whether `id` gets the nights and workouts of this device. On: it hears everything now. Off: it is told to forget them. Another
    /// phone keeps getting them either way. The other phones hear the choice and make the same one.
    pub async fn set_health_allowed(&self, id: &DeviceId, on: bool) -> Result<()> {
        let on_before = self.device_settings(id).health;
        self.set_device_settings(id, DeviceSettings { health: on, ..self.device_settings(id) })?;
        if self.inner.phone_to_phone(id) {
            return Ok(());
        }
        let choice = {
            let mut health = self.inner.health.lock().unwrap();
            let at_ms = now_ms().max(health.choice(id, on_before).at_ms + 1);
            let choice = HealthChoice { device: *id, on, at_ms };
            health.take_choice(choice, on_before);
            self.inner.store.write_cbor(CHOICES, &health.choices)?;
            choice
        };
        self.inner.health_tell_phones(&[choice], None).await;
        self.inner.health_follow(id, on).await;
        Ok(())
    }
}

impl Inner {
    /// What to tell a device that just connected, when it knows the message.
    pub(crate) fn health_catch_up(&self, id: DeviceId, caps: &[String]) -> Option<Msg> {
        speaks(caps).then(|| Msg::HealthHave { newest: self.health.lock().unwrap().newest_from(id) })
    }

    pub(crate) async fn health_have(&self, from: DeviceId, newest: u64) {
        if self.health_allowed(&from) {
            let mine = self.health.lock().unwrap().mine_after(newest);
            self.health_send(&from, &mine).await;
        } else if newest > 0 {
            let _ = self.send_to(&from, Msg::HealthForget {}).await;
        }
    }

    pub(crate) fn health_received(&self, from: DeviceId, records: Vec<HealthRecord>) {
        self.health_change(|health| health.received(from, records));
    }

    pub(crate) fn health_forget(&self, from: DeviceId) {
        self.health_change(|health| health.forget_from(from));
    }

    /// After the circle changed. `still_in`: this device is still in it, so a phone keeps what came from a device that left.
    pub(crate) fn health_keep_only(&self, wanted: &HashSet<DeviceId>, still_in: bool) {
        let keep = still_in && is_phone(self.cfg.platform);
        let mut kept = Vec::new();
        self.health_change(|health| {
            let (changed, now_mine) = health.keep_only_from(wanted, keep, now_ms());
            kept = now_mine;
            changed
        });
        if kept.is_empty() {
            return;
        }
        // The computers dropped what came from that device just now: they hear it again from here, without waiting for a new connection.
        let sessions: Vec<_> =
            self.health_readers().into_iter().filter(|id| wanted.contains(id)).filter_map(|id| self.session_of(&id)).collect();
        if let Ok(runtime) = tokio::runtime::Handle::try_current() {
            runtime.spawn(async move {
                for session in sessions {
                    for batch in kept.chunks(BATCH) {
                        if session.tx.send(Msg::HealthRecords { records: batch.to_vec() }).await.is_err() {
                            break;
                        }
                    }
                }
            });
        }
    }

    /// What to tell another phone that just connected: every choice for a computer this device knows.
    pub(crate) fn health_choices_catch_up(&self, id: DeviceId, caps: &[String]) -> Option<Msg> {
        if !speaks(caps) || !self.phone_to_phone(&id) {
            return None;
        }
        let settings = self.settings.read().unwrap().clone();
        let mut choices = self.health.lock().unwrap().choices.clone();
        for (key, device) in &settings.devices {
            if let Ok(device_id) = DeviceId::parse(key)
                && device.health
                && !choices.iter().any(|c| c.device == device_id)
            {
                choices.push(HealthChoice { device: device_id, on: true, at_ms: 1 });
            }
        }
        (!choices.is_empty()).then_some(Msg::HealthChoices { choices })
    }

    /// Choices another phone heard from the person: the newer ones are made here too, and passed on to the phones that did not hear them.
    pub(crate) async fn health_choices_heard(&self, from: DeviceId, choices: Vec<HealthChoice>) {
        if !self.phone_to_phone(&from) {
            return;
        }
        let settings = self.settings.read().unwrap().clone();
        let taken: Vec<HealthChoice> = {
            let mut health = self.health.lock().unwrap();
            let taken: Vec<HealthChoice> = choices
                .into_iter()
                .filter(|c| c.device != self.my_id && !self.is_phone_member(&c.device))
                .filter(|c| health.take_choice(*c, settings.for_device(&c.device).health))
                .collect();
            if !taken.is_empty()
                && let Err(e) = self.store.write_cbor(CHOICES, &health.choices)
            {
                tracing::warn!("could not keep the health choices: {e}");
            }
            taken
        };
        for choice in &taken {
            let changed = DeviceSettings { health: choice.on, ..settings.for_device(&choice.device) };
            if let Err(e) = self.set_device_settings(&choice.device, changed) {
                tracing::warn!("could not keep a health choice: {e}");
            }
            self.health_follow(&choice.device, choice.on).await;
        }
        if !taken.is_empty() {
            self.health_tell_phones(&taken, Some(from)).await;
        }
    }

    /// Tells the other phones what the person chose, except the one it came from.
    async fn health_tell_phones(&self, choices: &[HealthChoice], except: Option<DeviceId>) {
        for id in self.health_peers() {
            if Some(id) != except && self.phone_to_phone(&id) {
                let _ = self.send_to(&id, Msg::HealthChoices { choices: choices.to_vec() }).await;
            }
        }
    }

    /// Acts on a choice for `id` when it is connected: on, it hears everything now; off, it forgets what came from here.
    async fn health_follow(&self, id: &DeviceId, on: bool) {
        if !self.health_peers().contains(id) {
            return;
        }
        if on {
            let mine = self.health.lock().unwrap().mine_after(0);
            self.health_send(id, &mine).await;
        } else {
            let _ = self.send_to(id, Msg::HealthForget {}).await;
        }
    }

    fn health_change(&self, change: impl FnOnce(&mut Health) -> bool) {
        let changed = {
            let mut health = self.health.lock().unwrap();
            let changed = change(&mut health);
            if changed && let Err(e) = self.store.write_cbor(FILE, &health.records) {
                tracing::warn!("could not keep the health records: {e}");
            }
            changed
        };
        if changed {
            self.emit(Event::HealthChanged);
        }
    }

    async fn health_send(&self, id: &DeviceId, records: &[HealthRecord]) {
        for batch in records.chunks(BATCH) {
            if self.send_to(id, Msg::HealthRecords { records: batch.to_vec() }).await.is_err() {
                // It says how far it got when it is back.
                return;
            }
        }
    }

    fn health_peers(&self) -> Vec<DeviceId> {
        let peers = self.peers.lock().unwrap();
        peers.iter().filter(|(_, p)| p.session.is_some() && p.hello.as_ref().is_some_and(|h| speaks(&h.caps))).map(|(id, _)| *id).collect()
    }

    fn health_readers(&self) -> Vec<DeviceId> {
        self.health_peers().into_iter().filter(|id| self.health_allowed(id)).collect()
    }

    pub(crate) fn health_allowed(&self, id: &DeviceId) -> bool {
        self.settings.read().unwrap().for_device(id).health || self.phone_to_phone(id)
    }

    /// This device and `id` are both phones, by what the circle says, so also while `id` is away.
    fn phone_to_phone(&self, id: &DeviceId) -> bool {
        is_phone(self.cfg.platform) && self.is_phone_member(id)
    }

    fn is_phone_member(&self, id: &DeviceId) -> bool {
        self.circle.read().unwrap().member(id).is_some_and(|m| is_phone(m.platform))
    }
}

/// A device a watch can be with.
fn is_phone(platform: Platform) -> bool {
    matches!(platform, Platform::Android | Platform::Ios)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::proto::{decode_msg, encode};

    fn id(n: u8) -> DeviceId {
        DeviceId::from_public_key(&[n; 32])
    }

    fn night(name: &str, start_ms: u64) -> HealthRecord {
        HealthRecord {
            id: name.to_string(),
            kind: HealthKind::Sleep,
            start_ms,
            end_ms: start_ms + 1000,
            sleep_minutes: 420,
            awake_minutes: 20,
            awakenings: 2,
            efficiency_percent: 91,
            light_minutes: Some(210),
            deep_minutes: Some(90),
            rem_minutes: Some(120),
            stages: vec![StageRun { stage: SleepStage::Light, seconds: 1800 }, StageRun { stage: SleepStage::Rem, seconds: 600 }],
            heart_rate_mean: Some(58),
            heart_rate_low: Some(47),
            heart_rate_high: None,
            steps: 0,
            active_minutes: 0,
            heart_curve: Vec::new(),
            step_curve: Vec::new(),
            added_ms: 0,
            origin: None,
        }
    }

    #[test]
    fn a_record_from_the_watch_only_counts_once() {
        let mut health = Health::default();
        assert_eq!(health.add_mine(vec![night("a", 1), night("b", 2)], 100).len(), 2);
        assert_eq!(health.add_mine(vec![night("a", 1), night("c", 3)], 100).len(), 1);
        assert_eq!(health.records.len(), 3);
    }

    #[test]
    fn how_far_never_stands_still_within_one_moment() {
        let mut health = Health::default();
        let fresh = health.add_mine(vec![night("a", 1), night("b", 2)], 100);
        assert_eq!((fresh[0].added_ms, fresh[1].added_ms), (100, 101));
        // A clock that went back must not make a new record look old to a device that already got further.
        assert_eq!(health.add_mine(vec![night("c", 3)], 50)[0].added_ms, 102);
    }

    #[test]
    fn a_device_hears_what_came_after_how_far_it_got() {
        let mut phone = Health::default();
        phone.add_mine(vec![night("a", 1), night("b", 2), night("c", 3)], 100);
        let mut computer = Health::default();
        computer.received(id(1), phone.mine_after(0)[..2].to_vec());
        let newest = computer.newest_from(id(1));
        assert_eq!(newest, 101);
        let rest = phone.mine_after(newest);
        assert_eq!(rest.iter().map(|r| r.id.as_str()).collect::<Vec<_>>(), ["c"]);
        assert!(computer.received(id(1), rest));
        assert_eq!(computer.records.len(), 3);
    }

    #[test]
    fn hearing_a_record_again_is_no_news() {
        let mut phone = Health::default();
        let sent = phone.add_mine(vec![night("a", 1)], 100);
        let mut computer = Health::default();
        assert!(computer.received(id(1), sent.clone()));
        assert!(!computer.received(id(1), sent));
        assert_eq!(computer.records.len(), 1);
    }

    #[test]
    fn what_a_device_heard_it_never_passes_on() {
        let mut computer = Health::default();
        computer.received(id(1), vec![HealthRecord { added_ms: 5, ..night("a", 1) }]);
        assert!(computer.mine_after(0).is_empty());
    }

    #[test]
    fn forgetting_only_touches_what_came_from_that_device() {
        let mut health = Health::default();
        health.add_mine(vec![night("mine", 1)], 100);
        health.received(id(1), vec![night("one", 2)]);
        health.received(id(2), vec![night("two", 3)]);
        assert!(health.forget_from(id(1)));
        assert!(!health.forget_from(id(1)));
        assert_eq!(health.records.iter().map(|r| r.id.as_str()).collect::<Vec<_>>(), ["mine", "two"]);
    }

    #[test]
    fn what_came_from_a_device_that_left_goes_with_it() {
        let mut health = Health::default();
        health.add_mine(vec![night("mine", 1)], 100);
        health.received(id(1), vec![night("one", 2)]);
        health.received(id(2), vec![night("two", 3)]);
        let wanted = HashSet::from([id(2)]);
        assert!(health.keep_only_from(&wanted, false, 200).0);
        assert!(!health.keep_only_from(&wanted, false, 200).0);
        assert_eq!(health.records.iter().map(|r| r.id.as_str()).collect::<Vec<_>>(), ["mine", "two"]);
    }

    #[test]
    fn a_phone_keeps_what_came_from_a_device_that_left_as_its_own() {
        let mut health = Health::default();
        health.add_mine(vec![night("mine", 1)], 100);
        health.received(id(1), vec![HealthRecord { added_ms: 9, ..night("late", 3) }, HealthRecord { added_ms: 5, ..night("early", 2) }]);
        let (changed, kept) = health.keep_only_from(&HashSet::new(), true, 50);
        assert!(changed);
        // In the order they came, after what this device had, so a computer that got further still hears them.
        assert_eq!(kept.iter().map(|r| (r.id.as_str(), r.added_ms)).collect::<Vec<_>>(), [("early", 101), ("late", 102)]);
        assert!(health.records.iter().all(|r| r.origin.is_none()));
        assert_eq!(health.mine_after(100).len(), 2);
        assert!(!health.keep_only_from(&HashSet::new(), true, 50).0);
    }

    #[test]
    fn the_newest_choice_counts() {
        let mut health = Health::default();
        let (mac, on, off) = (id(1), true, false);
        // Switched on before the phones passed choices on: wins over a choice never made, loses to any made since.
        assert!(!health.take_choice(HealthChoice { device: mac, on, at_ms: 1 }, true));
        assert!(health.take_choice(HealthChoice { device: mac, on, at_ms: 1 }, false));
        assert!(health.take_choice(HealthChoice { device: mac, on: off, at_ms: 500 }, true));
        assert!(!health.take_choice(HealthChoice { device: mac, on, at_ms: 400 }, true));
        assert_eq!(health.choice(&mac, true), HealthChoice { device: mac, on: off, at_ms: 500 });
    }

    #[test]
    fn the_oldest_go_when_there_are_too_many() {
        let mut health = Health::default();
        let many = (0..MAX_RECORDS as u64 + 3).map(|n| night(&format!("n{n}"), n)).collect();
        health.add_mine(many, 100);
        assert_eq!(health.records.len(), MAX_RECORDS);
        assert!(health.records.iter().all(|r| r.start_ms >= 3));
    }

    #[test]
    fn a_record_survives_the_wire() {
        let record = night("a", 1);
        let bytes = encode(&Msg::HealthRecords { records: vec![record.clone()] }).unwrap();
        match decode_msg(&bytes).unwrap() {
            Msg::HealthRecords { records } => assert_eq!(records, vec![record]),
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn a_stage_or_kind_from_a_newer_version_does_not_lose_the_message() {
        #[derive(Serialize)]
        struct Newer {
            stage: &'static str,
            seconds: u32,
        }
        let mut bytes = Vec::new();
        ciborium::into_writer(&Newer { stage: "nap", seconds: 30 }, &mut bytes).unwrap();
        let run: StageRun = ciborium::from_reader(bytes.as_slice()).unwrap();
        assert_eq!(run.stage, SleepStage::Unknown);
    }

    #[test]
    fn health_starts_off_for_every_device() {
        assert!(!DeviceSettings::default().health);
        assert!(!DeviceSettings::guest().health);
    }
}

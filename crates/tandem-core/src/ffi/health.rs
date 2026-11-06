//! Nights and workouts for the apps (see `crate::health`).

use crate::health::{HealthKind, HealthRecord, SleepStage, StageRun};
use crate::ids::DeviceId;

use super::{TandemEngine, TandemError, join_error};

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum TandemHealthKind {
    Sleep,
    Workout,
    /// From a newer version: show nothing for it.
    Unknown,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum TandemSleepStage {
    Wake,
    Light,
    Deep,
    Rem,
    /// From a newer version.
    Unknown,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemStageRun {
    pub stage: TandemSleepStage,
    pub seconds: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemHealthRecord {
    /// Made by the watch, the same however often it is sent.
    pub id: String,
    pub kind: TandemHealthKind,
    /// Milliseconds since 1970.
    pub start_ms: u64,
    pub end_ms: u64,
    pub sleep_minutes: u32,
    pub awake_minutes: u32,
    pub awakenings: u32,
    pub efficiency_percent: u32,
    /// None when the heart rate was missing too much to tell the stages apart.
    pub light_minutes: Option<u32>,
    pub deep_minutes: Option<u32>,
    pub rem_minutes: Option<u32>,
    /// The stretches of each stage in the order of the night. Empty when the stages are not known, and for a workout.
    pub stages: Vec<TandemStageRun>,
    pub heart_rate_mean: Option<u32>,
    pub heart_rate_low: Option<u32>,
    pub heart_rate_high: Option<u32>,
    pub steps: u64,
}

impl From<HealthRecord> for TandemHealthRecord {
    fn from(r: HealthRecord) -> Self {
        TandemHealthRecord {
            id: r.id,
            kind: match r.kind {
                HealthKind::Sleep => TandemHealthKind::Sleep,
                HealthKind::Workout => TandemHealthKind::Workout,
                HealthKind::Unknown => TandemHealthKind::Unknown,
            },
            start_ms: r.start_ms,
            end_ms: r.end_ms,
            sleep_minutes: r.sleep_minutes,
            awake_minutes: r.awake_minutes,
            awakenings: r.awakenings,
            efficiency_percent: r.efficiency_percent,
            light_minutes: r.light_minutes,
            deep_minutes: r.deep_minutes,
            rem_minutes: r.rem_minutes,
            stages: r
                .stages
                .into_iter()
                .map(|run| TandemStageRun {
                    stage: match run.stage {
                        SleepStage::Wake => TandemSleepStage::Wake,
                        SleepStage::Light => TandemSleepStage::Light,
                        SleepStage::Deep => TandemSleepStage::Deep,
                        SleepStage::Rem => TandemSleepStage::Rem,
                        SleepStage::Unknown => TandemSleepStage::Unknown,
                    },
                    seconds: run.seconds,
                })
                .collect(),
            heart_rate_mean: r.heart_rate_mean,
            heart_rate_low: r.heart_rate_low,
            heart_rate_high: r.heart_rate_high,
            steps: r.steps,
        }
    }
}

impl From<TandemHealthRecord> for HealthRecord {
    fn from(r: TandemHealthRecord) -> Self {
        HealthRecord {
            id: r.id,
            kind: match r.kind {
                TandemHealthKind::Sleep => HealthKind::Sleep,
                TandemHealthKind::Workout => HealthKind::Workout,
                TandemHealthKind::Unknown => HealthKind::Unknown,
            },
            start_ms: r.start_ms,
            end_ms: r.end_ms,
            sleep_minutes: r.sleep_minutes,
            awake_minutes: r.awake_minutes,
            awakenings: r.awakenings,
            efficiency_percent: r.efficiency_percent,
            light_minutes: r.light_minutes,
            deep_minutes: r.deep_minutes,
            rem_minutes: r.rem_minutes,
            stages: r
                .stages
                .into_iter()
                .map(|run| StageRun {
                    stage: match run.stage {
                        TandemSleepStage::Wake => SleepStage::Wake,
                        TandemSleepStage::Light => SleepStage::Light,
                        TandemSleepStage::Deep => SleepStage::Deep,
                        TandemSleepStage::Rem => SleepStage::Rem,
                        TandemSleepStage::Unknown => SleepStage::Unknown,
                    },
                    seconds: run.seconds,
                })
                .collect(),
            heart_rate_mean: r.heart_rate_mean,
            heart_rate_low: r.heart_rate_low,
            heart_rate_high: r.heart_rate_high,
            steps: r.steps,
            added_ms: 0,
            origin: None,
        }
    }
}

#[uniffi::export(async_runtime = "tokio")]
impl TandemEngine {
    /// Every night and workout this device has, the newest first.
    pub fn health_records(&self) -> Vec<TandemHealthRecord> {
        self.engine.health_records().into_iter().map(Into::into).collect()
    }

    /// Nights and workouts from a watch connected to this device. When this returns without error the watch may let go of them.
    pub async fn add_health_records(&self, records: Vec<TandemHealthRecord>) -> Result<(), TandemError> {
        let engine = self.engine.clone();
        let records = records.into_iter().map(Into::into).collect();
        self.runtime.spawn(async move { engine.add_health_records(records).await }).await.map_err(join_error)?.map_err(Into::into)
    }

    /// Whether the device `id` gets the nights and workouts of this one. Off until the person turns it on.
    pub fn health_allowed(&self, id: String) -> bool {
        DeviceId::parse(&id).map(|id| self.engine.device_settings(&id).health).unwrap_or(false)
    }

    /// On: `id` hears everything this device has. Off: it is told to forget them, now or when it is next connected.
    pub async fn set_health_allowed(&self, id: String, allowed: bool) -> Result<(), TandemError> {
        let id = DeviceId::parse(&id)?;
        let engine = self.engine.clone();
        self.runtime.spawn(async move { engine.set_health_allowed(&id, allowed).await }).await.map_err(join_error)?.map_err(Into::into)
    }
}

//! Nights and workouts for the apps (see `crate::health`).

use crate::health::{HealthKind, HealthRecord, SleepStage, StageRun};
use crate::health_insights::{HealthInsights, NightInsight, Regularity, RestingHeart, WeekSummary, export_csv, insights};
use crate::ids::{DeviceId, now_ms};

use super::{TandemEngine, TandemError, join_error};

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum TandemHealthKind {
    Sleep,
    Workout,
    /// The steps of one whole day.
    Day,
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
    #[uniffi(default = 0)]
    pub active_minutes: u32,
    /// For a day: the heart rate per five minutes from midnight on, 0 where nothing was measured.
    #[uniffi(default = [])]
    pub heart_curve: Vec<u32>,
    /// For a day: the steps per hour from midnight on. Empty from a watch from before.
    #[uniffi(default = [])]
    pub step_curve: Vec<u32>,
}

impl From<HealthRecord> for TandemHealthRecord {
    fn from(r: HealthRecord) -> Self {
        TandemHealthRecord {
            id: r.id,
            kind: match r.kind {
                HealthKind::Sleep => TandemHealthKind::Sleep,
                HealthKind::Workout => TandemHealthKind::Workout,
                HealthKind::Day => TandemHealthKind::Day,
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
            active_minutes: r.active_minutes,
            heart_curve: r.heart_curve.iter().map(|&v| u32::from(v)).collect(),
            step_curve: r.step_curve,
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
                TandemHealthKind::Day => HealthKind::Day,
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
            active_minutes: r.active_minutes,
            heart_curve: r.heart_curve.iter().map(|&v| v.min(255) as u8).collect(),
            step_curve: r.step_curve,
            added_ms: 0,
            origin: None,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemWakeMoment {
    pub start_ms: u64,
    pub minutes: u32,
}

/// The newest night, with the parts of its score so a page can say why. All from 0 to 100.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemNightInsight {
    pub id: String,
    pub score: u32,
    pub duration_score: u32,
    pub continuity_score: u32,
    /// None when the stages could not be told apart.
    pub stages_score: Option<u32>,
    pub wake_moments: Vec<TandemWakeMoment>,
    /// The night as Google's Sleep Score shows its parts (see `health_insights::QualityMeasure`). Empty without stages.
    pub quality: Vec<TandemQualityMeasure>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, uniffi::Enum)]
pub enum TandemQualityKind {
    ToSoundSleep,
    SoundSleep,
    Restlessness,
    Interruptions,
    FullAwakenings,
}

/// One part of the quality of a night, with the range most adults fall in and where its bar ends. Minutes, or a count for awakenings.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemQualityMeasure {
    pub kind: TandemQualityKind,
    pub value: u32,
    pub typical_low: u32,
    pub typical_high: u32,
    pub scale_max: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemRestingHeart {
    pub latest: u32,
    /// None until there are enough nights, see `nights_needed`.
    pub usual: Option<u32>,
    pub nights_needed: u32,
}

/// Bedtime and wake time as minutes after midnight, local time, and how far a usual night is from those.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemRegularity {
    pub bedtime_minutes: u32,
    pub bedtime_spread_minutes: u32,
    pub wake_minutes: u32,
    pub wake_spread_minutes: u32,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemWeekSummary {
    pub naps: u32,
    pub nap_minutes: u32,
    pub mean_steps: Option<u32>,
    pub active_minutes: u32,
    pub nights: u32,
    pub mean_sleep_minutes: Option<u32>,
    pub mean_score: Option<u32>,
    pub mean_resting: Option<u32>,
    pub workouts: u32,
    pub workout_minutes: u32,
    pub workout_steps: u64,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemHealthInsights {
    pub latest_night: Option<TandemNightInsight>,
    pub resting: Option<TandemRestingHeart>,
    pub regularity: Option<TandemRegularity>,
    /// How many nights are still missing for `regularity`. 0 once it is there.
    pub regularity_nights_needed: u32,
    /// How many beats per minute the last two nights are above usual, when they are. Not a diagnosis.
    pub alert_above_usual: Option<u32>,
    /// The last seven days, and the seven before that.
    pub this_week: TandemWeekSummary,
    pub last_week: TandemWeekSummary,
    /// The last whole day with a heart rate.
    pub latest_day_heart: Option<TandemDayHeart>,
    /// The last whole day with its steps per hour.
    pub latest_day_steps: Option<TandemDaySteps>,
    /// The naps of the last seven days, newest first.
    pub naps: Vec<TandemNap>,
    /// Always seven days, the oldest first and today last.
    pub week: Vec<TandemWeekDay>,
}

/// One day of the last seven. Nothing measured is None and not zero.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemWeekDay {
    /// Local midnight.
    pub start_ms: u64,
    pub sleep_minutes: Option<u32>,
    pub score: Option<u32>,
    pub resting: Option<u32>,
    /// When the night began and ended, in minutes from this midnight. Negative is the evening before.
    pub bed_minutes: Option<i32>,
    pub wake_minutes: Option<i32>,
    pub steps: Option<u64>,
    pub active_minutes: Option<u32>,
    pub nap_minutes: u32,
    /// The mean heart rate of the whole day.
    #[uniffi(default = None)]
    pub heart_mean: Option<u32>,
}

/// Any run of days with their sums: a week further back, or a month for a calendar.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemHealthRange {
    /// The oldest first.
    pub days: Vec<TandemWeekDay>,
    pub summary: TandemWeekSummary,
}

impl From<crate::health_insights::WeekDay> for TandemWeekDay {
    fn from(d: crate::health_insights::WeekDay) -> Self {
        TandemWeekDay {
            start_ms: d.start_ms,
            sleep_minutes: d.sleep_minutes,
            score: d.score,
            resting: d.resting,
            bed_minutes: d.bed_minutes,
            wake_minutes: d.wake_minutes,
            steps: d.steps,
            active_minutes: d.active_minutes,
            nap_minutes: d.nap_minutes,
            heart_mean: d.heart_mean,
        }
    }
}

impl From<crate::health_insights::HealthRange> for TandemHealthRange {
    fn from(r: crate::health_insights::HealthRange) -> Self {
        TandemHealthRange { days: r.days.into_iter().map(Into::into).collect(), summary: r.summary.into() }
    }
}

/// The steps of a day per hour from midnight.
#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemDaySteps {
    pub start_ms: u64,
    pub curve: Vec<u32>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemDayHeart {
    pub start_ms: u64,
    /// Per five minutes from midnight on, 0 where nothing was measured.
    pub curve: Vec<u32>,
    pub low: Option<u32>,
    pub mean: Option<u32>,
    pub high: Option<u32>,
}

#[derive(Clone, Debug, PartialEq, Eq, uniffi::Record)]
pub struct TandemNap {
    pub start_ms: u64,
    pub end_ms: u64,
    pub sleep_minutes: u32,
    pub heart_rate_mean: Option<u32>,
}

impl From<NightInsight> for TandemNightInsight {
    fn from(n: NightInsight) -> Self {
        Self {
            id: n.id,
            score: n.score,
            duration_score: n.duration_score,
            continuity_score: n.continuity_score,
            stages_score: n.stages_score,
            wake_moments: n.wake_moments.into_iter().map(|m| TandemWakeMoment { start_ms: m.start_ms, minutes: m.minutes }).collect(),
            quality: n
                .quality
                .into_iter()
                .map(|m| TandemQualityMeasure {
                    kind: match m.kind {
                        crate::health_insights::QualityKind::ToSoundSleep => TandemQualityKind::ToSoundSleep,
                        crate::health_insights::QualityKind::SoundSleep => TandemQualityKind::SoundSleep,
                        crate::health_insights::QualityKind::Restlessness => TandemQualityKind::Restlessness,
                        crate::health_insights::QualityKind::Interruptions => TandemQualityKind::Interruptions,
                        crate::health_insights::QualityKind::FullAwakenings => TandemQualityKind::FullAwakenings,
                    },
                    value: m.value,
                    typical_low: m.typical_low,
                    typical_high: m.typical_high,
                    scale_max: m.scale_max,
                })
                .collect(),
        }
    }
}

impl From<RestingHeart> for TandemRestingHeart {
    fn from(r: RestingHeart) -> Self {
        Self { latest: r.latest, usual: r.usual, nights_needed: r.nights_needed }
    }
}

impl From<Regularity> for TandemRegularity {
    fn from(r: Regularity) -> Self {
        Self {
            bedtime_minutes: r.bedtime_minutes,
            bedtime_spread_minutes: r.bedtime_spread_minutes,
            wake_minutes: r.wake_minutes,
            wake_spread_minutes: r.wake_spread_minutes,
        }
    }
}

impl From<WeekSummary> for TandemWeekSummary {
    fn from(w: WeekSummary) -> Self {
        Self {
            nights: w.nights,
            naps: w.naps,
            nap_minutes: w.nap_minutes,
            mean_sleep_minutes: w.mean_sleep_minutes,
            mean_score: w.mean_score,
            mean_resting: w.mean_resting,
            workouts: w.workouts,
            workout_minutes: w.workout_minutes,
            workout_steps: w.workout_steps,
            mean_steps: w.mean_steps,
            active_minutes: w.active_minutes,
        }
    }
}

impl From<HealthInsights> for TandemHealthInsights {
    fn from(i: HealthInsights) -> Self {
        Self {
            latest_night: i.latest_night.map(Into::into),
            resting: i.resting.map(Into::into),
            regularity: i.regularity.map(Into::into),
            regularity_nights_needed: i.regularity_nights_needed,
            alert_above_usual: i.alert.map(|a| a.above_usual),
            latest_day_heart: i.latest_day_heart.map(|d| TandemDayHeart {
                start_ms: d.start_ms,
                curve: d.curve.iter().map(|&v| u32::from(v)).collect(),
                low: d.low,
                mean: d.mean,
                high: d.high,
            }),
            latest_day_steps: i.latest_day_steps.map(|d| TandemDaySteps { start_ms: d.start_ms, curve: d.curve }),
            naps: i
                .naps
                .into_iter()
                .map(|n| TandemNap { start_ms: n.start_ms, end_ms: n.end_ms, sleep_minutes: n.sleep_minutes, heart_rate_mean: n.heart_rate_mean })
                .collect(),
            week: i.week.into_iter().map(Into::into).collect(),
            this_week: i.this_week.into(),
            last_week: i.last_week.into(),
        }
    }
}

/// The same sums for nights that are not this device's own, so a page can be looked at with a made up week.
#[uniffi::export]
pub fn health_insights_for(records: Vec<TandemHealthRecord>, now_ms: u64, utc_offset_minutes: i32) -> TandemHealthInsights {
    let records: Vec<HealthRecord> = records.into_iter().map(Into::into).collect();
    insights(&records, now_ms, utc_offset_minutes).into()
}

/// Any run of days for nights that are not this device's own, like `health_insights_for`.
#[uniffi::export]
pub fn health_range_for(records: Vec<TandemHealthRecord>, until_ms: u64, count: u32, utc_offset_minutes: i32) -> TandemHealthRange {
    let records: Vec<HealthRecord> = records.into_iter().map(Into::into).collect();
    crate::health_insights::range(&records, until_ms, count, utc_offset_minutes).into()
}

#[uniffi::export(async_runtime = "tokio")]
impl TandemEngine {
    /// What the nights say together: score, resting heart rate, regularity, this week next to the last. `utc_offset_minutes` is what
    /// a local clock is ahead of UTC, because only the apps know where they are.
    pub fn health_insights(&self, utc_offset_minutes: i32) -> TandemHealthInsights {
        insights(&self.engine.health_records(), now_ms(), utc_offset_minutes).into()
    }

    /// The `count` days that end with the day of `until_ms`, with their sums: a week further back, or a month for a calendar. From all
    /// the records this device keeps, not only the newest.
    pub fn health_range(&self, until_ms: u64, count: u32, utc_offset_minutes: i32) -> TandemHealthRange {
        crate::health_insights::range(&self.engine.health_records(), until_ms, count, utc_offset_minutes).into()
    }

    /// Everything as a table a spreadsheet can open, in local time.
    pub fn health_export_csv(&self, utc_offset_minutes: i32) -> String {
        export_csv(&self.engine.health_records(), utc_offset_minutes)
    }

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

    /// Whether the device `id` gets the nights and workouts of this one: another phone always, any other device once the person turns it on.
    pub fn health_allowed(&self, id: String) -> bool {
        DeviceId::parse(&id).map(|id| self.engine.health_allowed(&id)).unwrap_or(false)
    }

    /// On: `id` hears everything this device has. Off: it is told to forget them, now or when it is next connected.
    pub async fn set_health_allowed(&self, id: String, allowed: bool) -> Result<(), TandemError> {
        let id = DeviceId::parse(&id)?;
        let engine = self.engine.clone();
        self.runtime.spawn(async move { engine.set_health_allowed(&id, allowed).await }).await.map_err(join_error)?.map_err(Into::into)
    }
}

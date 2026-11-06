//! What a row of nights says: a score, the usual resting heart rate, how regular the bedtimes are, a week next to the week before.
//! Worked out here from the records every device already has, so a phone and a computer never show two different answers. Nothing
//! here is stored: the records are the truth, and a better formula works on old nights too.
//!
//! All of it is an estimate from a wrist, and none of it is a diagnosis.

use crate::health::{HealthKind, HealthRecord, SleepStage};

const MINUTE_MS: u64 = 60_000;
const DAY_MS: u64 = 86_400_000;
/// Less sleep than this is a nap, which says nothing about the score, the resting heart rate or a bedtime.
const MIN_NIGHT_MINUTES: u32 = 180;
/// Nights it takes before "usual" means anything. One person's heart rate differs more from night to night than a cold moves it.
const MIN_USUAL_NIGHTS: usize = 7;
const MAX_USUAL_NIGHTS: usize = 28;
const USUAL_WINDOW_DAYS: u64 = 42;
const MIN_REGULAR_NIGHTS: usize = 5;
const MAX_REGULAR_NIGHTS: usize = 14;
const REGULAR_WINDOW_DAYS: u64 = 21;
/// Two nights this far above usual in a row. One night is too often a late meal or a drink, and a warning that is often wrong gets ignored.
const ALERT_ABOVE_USUAL: u32 = 5;
/// Older than this and the warning would be about a day long gone.
const ALERT_FRESH_HOURS: u64 = 36;
const MIN_WAKE_SECONDS: u32 = 60;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WakeMoment {
    pub start_ms: u64,
    pub minutes: u32,
}

/// The newest night, with the parts of its score so a page can say why. All from 0 to 100.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct NightInsight {
    pub id: String,
    pub score: u32,
    pub duration_score: u32,
    pub continuity_score: u32,
    /// None when the stages could not be told apart: the score then rests on the other two.
    pub stages_score: Option<u32>,
    pub wake_moments: Vec<WakeMoment>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RestingHeart {
    pub latest: u32,
    /// None until there are enough nights, see `nights_needed`.
    pub usual: Option<u32>,
    pub nights_needed: u32,
}

/// Bedtime and wake time as minutes after midnight, local time, and how far a usual night is from those.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Regularity {
    pub bedtime_minutes: u32,
    pub bedtime_spread_minutes: u32,
    pub wake_minutes: u32,
    pub wake_spread_minutes: u32,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct WeekSummary {
    pub nights: u32,
    pub mean_sleep_minutes: Option<u32>,
    pub mean_score: Option<u32>,
    pub mean_resting: Option<u32>,
    pub workouts: u32,
    pub workout_minutes: u32,
    pub workout_steps: u64,
    /// Over the days the watch counted, which may be fewer than seven.
    pub mean_steps: Option<u32>,
    pub active_minutes: u32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HealthAlert {
    /// How many beats per minute the last night is above usual.
    pub above_usual: u32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct HealthInsights {
    pub latest_night: Option<NightInsight>,
    pub resting: Option<RestingHeart>,
    pub regularity: Option<Regularity>,
    /// How many nights are still missing for `regularity`. 0 once it is there.
    pub regularity_nights_needed: u32,
    pub alert: Option<HealthAlert>,
    /// The last seven days, and the seven before that.
    pub this_week: WeekSummary,
    pub last_week: WeekSummary,
}

fn scale(value: f64, zero: f64, full: f64) -> f64 {
    ((value - zero) / (full - zero)).clamp(0.0, 1.0) * 100.0
}

/// Seven to nine hours is what the sleep societies advise for adults. Above that it hardly counts against you: that is more often
/// catching up than a bad night.
fn duration_score(minutes: u32) -> f64 {
    let m = f64::from(minutes);
    if m <= 420.0 { scale(m, 240.0, 420.0) } else { 100.0 - scale(m, 540.0, 660.0) * 0.3 }
}

/// Everyone wakes a little at night, and the model misses some of it, so the first ten minutes cost nothing.
fn continuity_score(awake_minutes: u32) -> f64 {
    100.0 - scale(f64::from(awake_minutes), 10.0, 70.0)
}

/// Deep sleep and REM together. It counts least, because this is the part a wrist can tell the least well.
fn stages_score(record: &HealthRecord) -> Option<f64> {
    let share = f64::from(record.deep_minutes? + record.rem_minutes?) / f64::from(record.sleep_minutes.max(1));
    record.light_minutes?;
    Some(scale(share, 0.15, 0.40))
}

fn score_of(record: &HealthRecord) -> u32 {
    let duration = duration_score(record.sleep_minutes);
    let continuity = continuity_score(record.awake_minutes);
    let total = match stages_score(record) {
        Some(stages) => duration * 0.55 + continuity * 0.30 + stages * 0.15,
        None => duration * 0.65 + continuity * 0.35,
    };
    total.round() as u32
}

fn wake_moments(record: &HealthRecord) -> Vec<WakeMoment> {
    let mut at = record.start_ms;
    let mut moments = Vec::new();
    for run in &record.stages {
        if run.stage == SleepStage::Wake && run.seconds >= MIN_WAKE_SECONDS {
            moments.push(WakeMoment { start_ms: at, minutes: run.seconds / 60 });
        }
        at += u64::from(run.seconds) * 1000;
    }
    moments
}

fn insight_of(record: &HealthRecord) -> NightInsight {
    NightInsight {
        id: record.id.clone(),
        score: score_of(record),
        duration_score: duration_score(record.sleep_minutes).round() as u32,
        continuity_score: continuity_score(record.awake_minutes).round() as u32,
        stages_score: stages_score(record).map(|s| s.round() as u32),
        wake_moments: wake_moments(record),
    }
}

fn median(mut values: Vec<u32>) -> Option<u32> {
    if values.is_empty() {
        return None;
    }
    values.sort_unstable();
    Some(values[values.len() / 2])
}

fn mean(values: impl Iterator<Item = u32>) -> Option<u32> {
    let (sum, count) = values.fold((0u64, 0u64), |(sum, count), v| (sum + u64::from(v), count + 1));
    (count > 0).then(|| ((sum as f64) / (count as f64)).round() as u32)
}

/// The usual lowest heart rate of `nights`, newest first, counted back from the night at `from`. The nights being judged stay out of it.
fn usual_resting(nights: &[&HealthRecord], from: usize) -> (Option<u32>, usize) {
    let Some(reference) = nights.get(from.saturating_sub(1)) else { return (None, 0) };
    let oldest = reference.end_ms.saturating_sub(USUAL_WINDOW_DAYS * DAY_MS);
    let values: Vec<u32> = nights
        .iter()
        .skip(from)
        .take(MAX_USUAL_NIGHTS)
        .filter(|night| night.end_ms >= oldest)
        .filter_map(|night| night.heart_rate_low)
        .collect();
    let count = values.len();
    (median(values).filter(|_| count >= MIN_USUAL_NIGHTS), count)
}

fn resting_of(nights: &[&HealthRecord]) -> Option<RestingHeart> {
    let latest = nights.first()?.heart_rate_low?;
    let (usual, count) = usual_resting(nights, 1);
    Some(RestingHeart { latest, usual, nights_needed: MIN_USUAL_NIGHTS.saturating_sub(count) as u32 })
}

fn alert_of(nights: &[&HealthRecord], now_ms: u64) -> Option<HealthAlert> {
    let last = nights.first()?;
    if now_ms.saturating_sub(last.end_ms) > ALERT_FRESH_HOURS * 3_600_000 {
        return None;
    }
    let (usual, _) = usual_resting(nights, 2);
    let usual = usual?;
    let latest = last.heart_rate_low?;
    let before = nights.get(1)?.heart_rate_low?;
    (latest >= usual + ALERT_ABOVE_USUAL && before >= usual + ALERT_ABOVE_USUAL).then(|| HealthAlert { above_usual: latest - usual })
}

fn local_minutes(ms: u64, utc_offset_minutes: i32) -> i64 {
    ((ms / MINUTE_MS) as i64 + i64::from(utc_offset_minutes)).rem_euclid(1440)
}

/// The mean and the spread of minutes in a day, counted from `start` (minutes after midnight), so that a bedtime just before and one
/// just after midnight are close together and not almost a day apart.
fn mean_and_spread(minutes: &[i64], start: i64) -> (u32, u32) {
    let values: Vec<f64> = minutes.iter().map(|m| (m - start).rem_euclid(1440) as f64).collect();
    let mean = values.iter().sum::<f64>() / values.len() as f64;
    let variance = values.iter().map(|v| (v - mean).powi(2)).sum::<f64>() / values.len() as f64;
    let local = (mean.round() as i64 + start).rem_euclid(1440);
    (local as u32, variance.sqrt().round() as u32)
}

fn regularity_of(nights: &[&HealthRecord], now_ms: u64, utc_offset_minutes: i32) -> (Option<Regularity>, u32) {
    let oldest = now_ms.saturating_sub(REGULAR_WINDOW_DAYS * DAY_MS);
    let recent: Vec<&&HealthRecord> = nights.iter().filter(|night| night.end_ms >= oldest).take(MAX_REGULAR_NIGHTS).collect();
    if recent.len() < MIN_REGULAR_NIGHTS {
        return (None, (MIN_REGULAR_NIGHTS - recent.len()) as u32);
    }
    let beds: Vec<i64> = recent.iter().map(|night| local_minutes(night.start_ms, utc_offset_minutes)).collect();
    let wakes: Vec<i64> = recent.iter().map(|night| local_minutes(night.end_ms, utc_offset_minutes)).collect();
    // A bedtime counts from noon and a wake time from the evening before: each is then far from the place where a day wraps around.
    let (bedtime_minutes, bedtime_spread_minutes) = mean_and_spread(&beds, 12 * 60);
    let (wake_minutes, wake_spread_minutes) = mean_and_spread(&wakes, 18 * 60);
    (Some(Regularity { bedtime_minutes, bedtime_spread_minutes, wake_minutes, wake_spread_minutes }), 0)
}

fn week_of(records: &[HealthRecord], from_ms: u64, until_ms: u64) -> WeekSummary {
    let inside = |ms: u64| ms >= from_ms && ms < until_ms;
    let nights: Vec<&HealthRecord> = records.iter().filter(|r| is_night(r) && inside(r.end_ms)).collect();
    let workouts: Vec<&HealthRecord> = records.iter().filter(|r| r.kind == HealthKind::Workout && inside(r.start_ms)).collect();
    let days: Vec<&HealthRecord> = records.iter().filter(|r| r.kind == HealthKind::Day && inside(r.end_ms)).collect();
    WeekSummary {
        nights: nights.len() as u32,
        mean_sleep_minutes: mean(nights.iter().map(|night| night.sleep_minutes)),
        mean_score: mean(nights.iter().map(|night| score_of(night))),
        mean_resting: mean(nights.iter().filter_map(|night| night.heart_rate_low)),
        workouts: workouts.len() as u32,
        workout_minutes: workouts.iter().map(|w| (w.end_ms.saturating_sub(w.start_ms) / MINUTE_MS) as u32).sum(),
        workout_steps: workouts.iter().map(|w| w.steps).sum(),
        mean_steps: mean(days.iter().map(|d| d.steps.min(u64::from(u32::MAX)) as u32)),
        active_minutes: days.iter().map(|d| d.active_minutes).sum(),
    }
}

fn is_night(record: &HealthRecord) -> bool {
    record.kind == HealthKind::Sleep && record.sleep_minutes >= MIN_NIGHT_MINUTES
}

pub fn insights(records: &[HealthRecord], now_ms: u64, utc_offset_minutes: i32) -> HealthInsights {
    let mut nights: Vec<&HealthRecord> = records.iter().filter(|r| is_night(r)).collect();
    nights.sort_by_key(|night| std::cmp::Reverse(night.end_ms));
    let (regularity, regularity_nights_needed) = regularity_of(&nights, now_ms, utc_offset_minutes);
    let week_ago = now_ms.saturating_sub(7 * DAY_MS);
    HealthInsights {
        latest_night: nights.first().map(|night| insight_of(night)),
        resting: resting_of(&nights),
        regularity,
        regularity_nights_needed,
        alert: alert_of(&nights, now_ms),
        this_week: week_of(records, week_ago, now_ms.saturating_add(1)),
        last_week: week_of(records, now_ms.saturating_sub(14 * DAY_MS), week_ago),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::health::StageRun;

    /// A day on which the clock is easy to read: midnight UTC, far from any edge.
    const MIDNIGHT: u64 = 1_780_000_000_000 / DAY_MS * DAY_MS;

    fn night(name: &str, bed_minutes: i64, day: u64, sleep: u32, low: Option<u32>) -> HealthRecord {
        let start_ms = (MIDNIGHT as i64 + day as i64 * DAY_MS as i64 + bed_minutes * MINUTE_MS as i64) as u64;
        HealthRecord {
            id: name.to_string(),
            kind: HealthKind::Sleep,
            start_ms,
            end_ms: start_ms + u64::from(sleep) * MINUTE_MS,
            sleep_minutes: sleep,
            awake_minutes: 0,
            awakenings: 0,
            efficiency_percent: 100,
            light_minutes: None,
            deep_minutes: None,
            rem_minutes: None,
            stages: Vec::new(),
            heart_rate_mean: None,
            heart_rate_low: low,
            heart_rate_high: None,
            steps: 0,
            active_minutes: 0,
            added_ms: 0,
            origin: None,
        }
    }

    fn row(nights: usize, low: u32) -> Vec<HealthRecord> {
        (0..nights).map(|day| night(&format!("n{day}"), 0, day as u64, 450, Some(low))).collect()
    }

    fn after(records: &[HealthRecord]) -> u64 {
        records.iter().map(|r| r.end_ms).max().unwrap() + 3_600_000
    }

    #[test]
    fn eight_good_hours_score_full() {
        let mut good = night("n", 0, 0, 480, None);
        assert_eq!(score_of(&good), 100);
        good.deep_minutes = Some(100);
        good.rem_minutes = Some(100);
        good.light_minutes = Some(280);
        assert_eq!(score_of(&good), 100);
    }

    #[test]
    fn little_sleep_and_a_broken_night_score_low() {
        let short = night("n", 0, 0, 240, None);
        assert!(score_of(&short) < 40);
        let mut broken = night("n", 0, 0, 480, None);
        broken.awake_minutes = 70;
        assert!(score_of(&broken) < score_of(&night("n", 0, 0, 480, None)));
    }

    #[test]
    fn a_long_night_costs_little() {
        assert!(score_of(&night("n", 0, 0, 660, None)) >= 80);
    }

    #[test]
    fn stages_only_count_when_they_are_there() {
        let plain = insight_of(&night("n", 0, 0, 480, None));
        assert_eq!(plain.stages_score, None);
        let mut known = night("n", 0, 0, 400, None);
        known.deep_minutes = Some(20);
        known.rem_minutes = Some(40);
        known.light_minutes = Some(340);
        assert_eq!(insight_of(&known).stages_score, Some(0));
    }

    #[test]
    fn wake_moments_come_from_the_runs_of_the_night() {
        let mut record = night("n", 0, 0, 480, None);
        record.stages = vec![
            StageRun { stage: SleepStage::Light, seconds: 3600 },
            StageRun { stage: SleepStage::Wake, seconds: 540 },
            StageRun { stage: SleepStage::Deep, seconds: 1800 },
            StageRun { stage: SleepStage::Wake, seconds: 30 },
            StageRun { stage: SleepStage::Rem, seconds: 900 },
        ];
        assert_eq!(wake_moments(&record), vec![WakeMoment { start_ms: record.start_ms + 3_600_000, minutes: 9 }]);
    }

    #[test]
    fn a_nap_is_no_night() {
        let mut records = row(1, 50);
        let mut nap = night("nap", 14 * 60, 1, 40, Some(70));
        nap.end_ms = nap.start_ms + 40 * MINUTE_MS;
        records.push(nap);
        let result = insights(&records, after(&records), 0);
        assert_eq!(result.latest_night.unwrap().id, "n0");
        assert_eq!(result.this_week.nights, 1);
        assert_eq!(result.resting.unwrap().latest, 50);
    }

    #[test]
    fn usual_needs_a_week_of_nights() {
        let records = row(5, 50);
        let resting = insights(&records, after(&records), 0).resting.unwrap();
        assert_eq!(resting.usual, None);
        assert_eq!(resting.nights_needed, 3);
        let records = row(8, 50);
        let resting = insights(&records, after(&records), 0).resting.unwrap();
        assert_eq!(resting.usual, Some(50));
        assert_eq!(resting.nights_needed, 0);
    }

    #[test]
    fn one_high_night_is_no_alert_but_two_are() {
        let mut records = row(10, 50);
        records.push(night("high", 0, 10, 450, Some(56)));
        assert_eq!(insights(&records, after(&records), 0).alert, None);
        records.push(night("higher", 0, 11, 450, Some(58)));
        assert_eq!(insights(&records, after(&records), 0).alert, Some(HealthAlert { above_usual: 8 }));
    }

    #[test]
    fn an_old_alert_is_no_alert() {
        let mut records = row(10, 50);
        records.push(night("high", 0, 10, 450, Some(56)));
        records.push(night("higher", 0, 11, 450, Some(58)));
        assert_eq!(insights(&records, after(&records) + 2 * DAY_MS, 0).alert, None);
    }

    #[test]
    fn regularity_needs_five_recent_nights() {
        let records = row(3, 50);
        let result = insights(&records, after(&records), 0);
        assert_eq!(result.regularity, None);
        assert_eq!(result.regularity_nights_needed, 2);
    }

    #[test]
    fn bedtimes_around_midnight_are_close_together() {
        let records: Vec<HealthRecord> = [-20, 20, -20, 20, -20, 20]
            .iter()
            .enumerate()
            .map(|(day, bed)| night(&format!("n{day}"), *bed, day as u64 + 1, 450, None))
            .collect();
        let regularity = insights(&records, after(&records), 0).regularity.unwrap();
        assert_eq!(regularity.bedtime_minutes, 0);
        assert_eq!(regularity.bedtime_spread_minutes, 20);
    }

    #[test]
    fn regularity_is_in_local_time() {
        let records = row(6, 50);
        let utc = insights(&records, after(&records), 0).regularity.unwrap();
        let dutch = insights(&records, after(&records), 60).regularity.unwrap();
        assert_eq!(utc.bedtime_minutes, 0);
        assert_eq!(dutch.bedtime_minutes, 60);
        assert_eq!(dutch.wake_minutes, utc.wake_minutes + 60);
    }

    #[test]
    fn a_week_is_counted_next_to_the_one_before() {
        let mut records = row(12, 50);
        let mut workout = night("run", 8 * 60, 11, 0, None);
        workout.kind = HealthKind::Workout;
        workout.end_ms = workout.start_ms + 30 * MINUTE_MS;
        workout.steps = 4000;
        records.push(workout);
        let result = insights(&records, after(&records), 0);
        assert_eq!(result.this_week.nights, 7);
        assert_eq!(result.last_week.nights, 5);
        assert_eq!(result.this_week.workouts, 1);
        assert_eq!(result.this_week.workout_minutes, 30);
        assert_eq!(result.this_week.workout_steps, 4000);
        assert_eq!(result.this_week.mean_sleep_minutes, Some(450));
        assert_eq!(result.last_week.workouts, 0);
    }

    #[test]
    fn a_week_counts_the_days_of_steps() {
        let day = |name: &str, n: u64, steps: u64, active: u32| {
            let mut record = night(name, 0, n, 0, None);
            record.kind = HealthKind::Day;
            record.end_ms = record.start_ms + DAY_MS;
            record.steps = steps;
            record.active_minutes = active;
            record
        };
        let records = vec![day("d1", 1, 6000, 10), day("d2", 2, 10000, 30), day("old", 0, 1, 1)];
        let now = records[1].end_ms + 3_600_000 + 5 * DAY_MS;
        let result = insights(&records, now, 0);
        assert_eq!(result.this_week.mean_steps, Some(8000));
        assert_eq!(result.this_week.active_minutes, 40);
        assert_eq!(result.last_week.mean_steps, Some(1));
        assert_eq!(result.latest_night, None);
    }

    #[test]
    fn no_records_says_nothing() {
        let result = insights(&[], MIDNIGHT, 0);
        assert_eq!(result.latest_night, None);
        assert_eq!(result.resting, None);
        assert_eq!(result.this_week, WeekSummary::default());
    }
}

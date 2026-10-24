//! On which side of a computer another device stands, found by ear. The computer plays a short sound from its left and its right speaker;
//! the other device records it with its microphone and says how loud each side was. The side that sounded louder is the side it stands on.
//! Used to put a second screen on the right side, and to place a computer next to this one for the shared mouse.
//!
//! The sound is a sweep from low to high on the left and from high to low on the right, at the same time, the way a speaker that tunes
//! itself to a room does. The recording is compared with each sweep (a matched filter: for a sound that is known in advance, nothing finds
//! it better in noise), and the two sweeps hardly resemble each other, so both sides can play at once and are still told apart. A sweep
//! covers every pitch, so a room that makes some pitches louder from one speaker than from the other cannot make that the difference (a
//! bell and a beep were tried too, and could not tell a small difference apart in a room with echoes). The device says when it listens,
//! and only then is the sound played, so there is no silence to wait through. The apps only play and record; the sound and the reading of
//! it are here, so every system does it the same way.
//!
//! The sweeps come in two kinds. The one the person asks for covers what a voice covers and is heard. The one for a check that runs by
//! itself (`QUIET`) sweeps between 18.5 and 20 kHz, above what most grown-ups hear, softly and with long fades: a sound that starts or
//! stops at once clicks, and a click has every pitch in it. Laptop speakers still play that high and microphones that record at 44.1 or
//! 48 kHz still take it in (measured on a MacBook Pro, see docs/SIDE.md). The listener is told which kind comes and reads only that one:
//! a room sound (music, a voice) can look more like the audible sweeps than the quiet ones do from across a desk.

use std::f32::consts::PI;
use std::sync::Arc;

use rustfft::num_complex::Complex32;
use rustfft::{Fft, FftPlanner};

/// The shape of one kind of sound: where it sweeps, how long, how often, and how loud.
struct Signal {
    /// A moment of silence before the sound, for the sound card to open.
    lead_s: f32,
    sweep_s: f32,
    low_hz: f32,
    high_hz: f32,
    /// Between two sweeps, for the echoes of a room to die down enough.
    gap_s: f32,
    /// The sound comes this many times, so one spoilt by a noise in the room does not decide it.
    repeats: usize,
    amplitude: f32,
    fade_s: f32,
}

const AMPLITUDE: f32 = 0.45;

const AUDIBLE: Signal = Signal {
    lead_s: 0.12,
    sweep_s: 0.2,
    low_hz: 400.0,
    high_hz: 6000.0,
    gap_s: 0.15,
    repeats: 3,
    amplitude: AMPLITUDE,
    fade_s: 0.01,
};

/// Longer sweeps than the audible ones: a narrow band needs more time for the matched filter to lift it as far out of the noise. The fades
/// take a fifth of each sweep, which keeps what is left below 15 kHz far under anything a person hears. Soft: a MacBook Pro still heard it
/// with its own microphone at a fifty times lower level (docs/SIDE.md), which leaves room for distance, a turned away microphone and a lower
/// volume.
const QUIET: Signal = Signal {
    lead_s: 0.12,
    sweep_s: 0.25,
    low_hz: 18_500.0,
    high_hz: 20_000.0,
    gap_s: 0.12,
    repeats: 3,
    amplitude: 0.04,
    fade_s: 0.05,
};

/// Below this the quiet sweeps do not fit in a recording (nor in what a sound card plays): their highest pitch is too close to half the
/// rate, where every sound card filters.
pub const QUIET_MIN_RATE: u32 = 44_100;

/// How long the device that listens records, from the moment it said it listens: the sound, the time it takes to arrive, and the echoes.
pub const LISTEN_MS: u32 = 2000;

/// A difference smaller than this (in decibels) does not say which side is nearer.
pub const SURE_DB: f32 = 1.5;

/// The same for a check that nobody asked for: it moves a screen without a word first, so it has to be surer. High pitches are blocked
/// more by the body of the computer, so the difference between the sides is larger for the quiet sweeps anyway.
pub const QUIET_SURE_DB: f32 = 3.0;

/// The sweep of one side (0 left, 1 right), once: up on the left, down on the right. An exponential sweep gives every octave the same
/// time, so the low and the high end both come through a small speaker.
fn sweep(signal: &Signal, side: usize, rate: f32) -> Vec<f32> {
    let n = (signal.sweep_s * rate) as usize;
    let (low, length, rate) = (f64::from(signal.low_hz), f64::from(signal.sweep_s), f64::from(rate));
    let k = (f64::from(signal.high_hz) / low).ln();
    let samples: Vec<f32> = (0..n)
        .map(|i| {
            let t = i as f64 / rate;
            // In f64: at 20 kHz the phase runs to thousands of radians, where an f32 loses the fraction that makes the pitch.
            (2.0 * std::f64::consts::PI * low * length / k * ((t / length * k).exp() - 1.0)).sin() as f32
        })
        .collect();
    let mut samples = shape(samples, rate as f32, signal.fade_s, signal.amplitude);
    if side == 1 {
        samples.reverse();
    }
    samples
}

/// A soft start and end, or the speaker clicks, and a click is heard by both sides; and the loudness of the whole to `amplitude`.
fn shape(mut samples: Vec<f32>, rate: f32, fade_s: f32, amplitude: f32) -> Vec<f32> {
    let n = samples.len();
    let fade = ((fade_s * rate) as usize).min(n / 2).max(1);
    for i in 0..fade {
        let ramp = 0.5 - 0.5 * (PI * i as f32 / fade as f32).cos();
        samples[i] *= ramp;
        samples[n - 1 - i] *= ramp;
    }
    let peak = samples.iter().fold(0.0f32, |m, s| m.max(s.abs())).max(1e-6);
    samples.iter_mut().for_each(|s| *s *= amplitude / peak);
    samples
}

fn duration_of(signal: &Signal) -> f32 {
    signal.lead_s + signal.repeats as f32 * (signal.sweep_s + signal.gap_s)
}

/// How long the pattern lasts, in seconds.
pub fn duration() -> f32 {
    duration_of(&AUDIBLE)
}

/// The pattern as stereo samples, left and right after each other, at `rate` samples a second.
pub fn pattern(rate: u32) -> Vec<f32> {
    pattern_at(rate, 1.0)
}

/// The same, at `loudness` (0 to 1) of the full level. A first try is soft, so a quiet room is not startled; when the device did not hear
/// it well, the second try is at full loudness.
pub fn pattern_at(rate: u32, loudness: f32) -> Vec<f32> {
    stereo(&AUDIBLE, rate, loudness.clamp(0.05, 1.0))
}

/// The quiet pattern, for a check that nobody asked for, as stereo samples like `pattern`. Empty at a rate too low to play it.
pub fn quiet_pattern(rate: u32) -> Vec<f32> {
    if rate < QUIET_MIN_RATE {
        return Vec::new();
    }
    stereo(&QUIET, rate, 1.0)
}

fn stereo(signal: &Signal, rate: u32, gain: f32) -> Vec<f32> {
    let rate_f = rate as f32;
    let (left, right) = (sweep(signal, 0, rate_f), sweep(signal, 1, rate_f));
    let frames = (duration_of(signal) * rate_f) as usize;
    let mut out = vec![0.0f32; frames * 2];
    let step = ((signal.sweep_s + signal.gap_s) * rate_f) as usize;
    let lead = (signal.lead_s * rate_f) as usize;
    for repeat in 0..signal.repeats {
        let at = lead + repeat * step;
        for i in 0..left.len().min(frames.saturating_sub(at)) {
            out[(at + i) * 2] = left[i] * gain;
            out[(at + i) * 2 + 1] = right[i] * gain;
        }
    }
    out
}

/// How loud each side was heard, as amplitudes (the same unit for both, so only their ratio matters).
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Heard {
    pub left: f32,
    pub right: f32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Side {
    Left,
    Right,
    /// The two were about as loud: the device stands in front, behind, or too far away to tell.
    Unsure,
}

/// Which side the device stands on, from what it heard.
pub fn decide(heard: Heard) -> Side {
    decide_with(heard, SURE_DB)
}

/// The same, sure only from a difference of `sure_db` decibels.
pub fn decide_with(heard: Heard, sure_db: f32) -> Side {
    let floor = 1e-9;
    let db = 20.0 * ((heard.left + floor) / (heard.right + floor)).log10();
    if db >= sure_db {
        Side::Left
    } else if db <= -sure_db {
        Side::Right
    } else {
        Side::Unsure
    }
}

/// How strongly `template` sounds at each moment of `samples`: the envelope of their correlation, done with the Fourier transform. The
/// envelope (and not the correlation itself) is taken, so a moment that falls between two samples of a high sound is not missed.
fn envelope(samples: &[f32], template: &[f32], planner: &mut FftPlanner<f32>) -> Vec<f32> {
    let size = (samples.len() + template.len()).next_power_of_two();
    let forward: Arc<dyn Fft<f32>> = planner.plan_fft_forward(size);
    let inverse: Arc<dyn Fft<f32>> = planner.plan_fft_inverse(size);
    let mut x: Vec<Complex32> = samples.iter().map(|&s| Complex32::new(s, 0.0)).collect();
    x.resize(size, Complex32::new(0.0, 0.0));
    let mut t: Vec<Complex32> = template.iter().map(|&s| Complex32::new(s, 0.0)).collect();
    t.resize(size, Complex32::new(0.0, 0.0));
    forward.process(&mut x);
    forward.process(&mut t);
    let half = size / 2;
    for (k, (a, b)) in x.iter_mut().zip(t.iter()).enumerate() {
        let product = *a * b.conj();
        // Only the positive frequencies, twice: the result is the analytic signal, whose size is the envelope.
        *a = if k == 0 || k == half {
            product
        } else if k < half {
            product * 2.0
        } else {
            Complex32::new(0.0, 0.0)
        };
    }
    inverse.process(&mut x);
    let energy = template.iter().map(|s| s * s).sum::<f32>().sqrt().max(1e-9);
    let scale = 1.0 / (size as f32 * energy);
    x.iter().take(samples.len()).map(|c| c.norm() * scale).collect()
}

/// The loudest moments of an envelope, at least `apart` samples from each other.
fn peaks(envelope: &[f32], count: usize, apart: usize) -> Vec<f32> {
    let mut taken: Vec<(usize, f32)> = Vec::new();
    let mut order: Vec<usize> = (0..envelope.len()).collect();
    order.sort_by(|&a, &b| envelope[b].partial_cmp(&envelope[a]).unwrap_or(std::cmp::Ordering::Equal));
    for i in order {
        if taken.len() == count {
            break;
        }
        if taken.iter().all(|&(at, _)| at.abs_diff(i) >= apart) {
            taken.push((i, envelope[i]));
        }
    }
    taken.into_iter().map(|(_, v)| v).collect()
}

fn median(mut values: Vec<f32>) -> f32 {
    if values.is_empty() {
        return 0.0;
    }
    values.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));
    values[values.len() / 2]
}

/// Reads a recording (one channel, `rate` samples a second) for the pattern. Nothing when it was not heard at all.
pub fn analyze(samples: &[f32], rate: u32) -> Option<Heard> {
    analyze_for(&AUDIBLE, samples, rate, &mut FftPlanner::new()).map(|(heard, _)| heard)
}

/// The same for the quiet pattern. Nothing at a rate too low to hold it.
pub fn analyze_quiet(samples: &[f32], rate: u32) -> Option<Heard> {
    if rate < QUIET_MIN_RATE {
        return None;
    }
    analyze_for(&QUIET, samples, rate, &mut FftPlanner::new()).map(|(heard, _)| heard)
}

/// What was heard of one kind of sound, and how far it stands out above the room.
fn analyze_for(signal: &Signal, samples: &[f32], rate: u32, planner: &mut FftPlanner<f32>) -> Option<(Heard, f32)> {
    let rate_f = rate as f32;
    if samples.len() < (duration_of(signal) * rate_f) as usize / 2 {
        return None;
    }
    let left_env = envelope(samples, &sweep(signal, 0, rate_f), planner);
    let right_env = envelope(samples, &sweep(signal, 1, rate_f), planner);
    // The sweeps come three times; the middle of the three is the loudness, so one spoilt by a noise in the room does not decide it. Two
    // moments that are closer than most of a step are one sweep and its echo.
    let apart = ((signal.sweep_s + signal.gap_s) * 0.8 * rate_f) as usize;
    let left = median(peaks(&left_env, signal.repeats, apart));
    let right = median(peaks(&right_env, signal.repeats, apart));
    // What the room gives at any moment, to tell a sound from no sound.
    let room = median(left_env.iter().chain(right_env.iter()).step_by(7).copied().collect()).max(1e-12);
    if left.max(right) < room * 6.0 {
        return None;
    }
    Some((Heard { left, right }, left.max(right) / room))
}

#[cfg(test)]
mod tests {
    use super::*;

    const RATE: u32 = 48_000;

    /// What a microphone hears: each side at its own loudness, late by `late` seconds, with the echoes of a room and noise. The two
    /// speakers stand in different places, so their echoes come at other moments, which makes some pitches louder from one than the other.
    fn recording(left_gain: f32, right_gain: f32, late: f32, noise: f32, room: bool, seed: u32) -> Vec<f32> {
        recording_of(&pattern(RATE), RATE, left_gain, right_gain, late, noise, room, seed)
    }

    /// The same for the quiet pattern, at `rate`.
    fn quiet_recording(rate: u32, left_gain: f32, right_gain: f32, late: f32, noise: f32, room: bool, seed: u32) -> Vec<f32> {
        recording_of(&quiet_pattern(rate), rate, left_gain, right_gain, late, noise, room, seed)
    }

    #[allow(clippy::too_many_arguments)]
    fn recording_of(stereo: &[f32], rate: u32, left_gain: f32, right_gain: f32, late: f32, noise: f32, room: bool, seed: u32) -> Vec<f32> {
        let pad = (late * rate as f32) as usize;
        let tail = (LISTEN_MS as usize * rate as usize / 1000).saturating_sub(pad + stereo.len() / 2);
        let channel = |c: usize, gain: f32| -> Vec<f32> {
            let mut v = vec![0.0f32; pad];
            v.extend(stereo.chunks_exact(2).map(|s| s[c] * gain));
            v.extend(std::iter::repeat_n(0.0, tail));
            v
        };
        let (left, right) = (channel(0, left_gain), channel(1, right_gain));
        let mut out: Vec<f32> = left.iter().zip(&right).map(|(l, r)| l + r).collect();
        if room {
            let echoes = [
                (&left, [(9.0f32, 0.6f32), (23.0, 0.45), (41.0, 0.3), (77.0, 0.2), (130.0, 0.12)]),
                (&right, [(11.5f32, 0.55f32), (19.0, 0.5), (47.0, 0.3), (69.0, 0.25), (140.0, 0.12)]),
            ];
            for (dry, list) in echoes {
                for (delay_ms, gain) in list {
                    let d = (delay_ms / 1000.0 * rate as f32) as usize;
                    for i in d..out.len() {
                        out[i] += dry[i - d] * gain;
                    }
                }
            }
        }
        let mut state = seed.max(1);
        for s in out.iter_mut() {
            state ^= state << 13;
            state ^= state >> 17;
            state ^= state << 5;
            *s += (state as f32 / u32::MAX as f32 - 0.5) * 2.0 * noise;
        }
        out
    }

    /// A microphone that hears less of the highest pitches: a low-pass of the second order (Butterworth) at `corner` Hz.
    fn rolled_off(samples: &[f32], rate: u32, corner: f32) -> Vec<f32> {
        let w = 2.0 * PI * corner / rate as f32;
        let alpha = w.sin() / (2.0 * std::f32::consts::FRAC_1_SQRT_2);
        let (b0, b1, a0, a1, a2) = ((1.0 - w.cos()) / 2.0, 1.0 - w.cos(), 1.0 + alpha, -2.0 * w.cos(), 1.0 - alpha);
        let (mut x1, mut x2, mut y1, mut y2) = (0.0f32, 0.0f32, 0.0f32, 0.0f32);
        samples
            .iter()
            .map(|&x| {
                let y = (b0 * x + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2) / a0;
                (x2, x1, y2, y1) = (x1, x, y1, y);
                y
            })
            .collect()
    }

    #[test]
    fn the_sound_is_short_stereo_and_softer_when_asked() {
        assert!(duration() < 1.3, "{}", duration());
        let p = pattern(RATE);
        assert_eq!(p.len() % 2, 0);
        assert!(p.iter().all(|s| s.abs() <= AMPLITUDE + 1e-4));
        let soft = pattern_at(RATE, 0.35);
        assert!(soft.iter().all(|s| s.abs() <= AMPLITUDE * 0.35 + 1e-4));
        // A device hears it all within the time it records, even when the sound starts a little late.
        assert!(duration() + 0.6 < LISTEN_MS as f32 / 1000.0);
    }

    #[test]
    fn the_two_sweeps_are_told_apart_although_they_play_at_once() {
        // Only the left speaker: the right sweep must find almost nothing of it.
        let heard = analyze(&recording(1.0, 0.0, 0.1, 0.0, false, 1), RATE).unwrap();
        assert!(heard.right < heard.left * 0.1, "{heard:?}");
    }

    #[test]
    fn the_near_side_is_found_whenever_the_sound_arrives() {
        for late in [0.0, 0.2, 0.45] {
            let left = analyze(&recording(1.0, 0.5, late, 0.02, false, 7), RATE).unwrap();
            assert_eq!(decide(left), Side::Left, "late {late}: {left:?}");
            let right = analyze(&recording(0.3, 0.9, late, 0.02, false, 9), RATE).unwrap();
            assert_eq!(decide(right), Side::Right, "late {late}: {right:?}");
        }
    }

    #[test]
    fn the_loudness_found_follows_the_loudness_played() {
        let heard = analyze(&recording(1.0, 0.5, 0.2, 0.01, false, 3), RATE).unwrap();
        assert!((heard.left / heard.right - 2.0).abs() < 0.2, "{heard:?}");
    }

    #[test]
    fn a_side_too_soft_to_hear_counts_as_the_soft_one() {
        let heard = analyze(&recording(0.0, 0.8, 0.2, 0.01, false, 5), RATE).unwrap();
        assert_eq!(decide(heard), Side::Right, "{heard:?}");
    }

    #[test]
    fn about_as_loud_on_both_sides_is_unsure_and_silence_is_nothing() {
        let heard = analyze(&recording(0.7, 0.68, 0.2, 0.01, false, 11), RATE).unwrap();
        assert_eq!(decide(heard), Side::Unsure, "{heard:?}");
        assert_eq!(analyze(&recording(0.0, 0.0, 0.2, 0.02, false, 13), RATE), None);
    }

    #[test]
    fn a_room_with_echoes_and_noise_still_gives_a_small_difference() {
        // 3 dB nearer to the left, in a room where the two speakers echo differently, with noise louder than the sound.
        for seed in 0..8 {
            let heard = analyze(&recording(0.1, 0.071, 0.05 + seed as f32 * 0.05, 0.3, true, 40 + seed), RATE).unwrap();
            assert_eq!(decide(heard), Side::Left, "seed {seed}: {heard:?}");
        }
    }

    #[test]
    fn another_sample_rate_reads_the_same() {
        let rate = 44_100;
        let stereo = pattern(rate);
        let mono: Vec<f32> = stereo.chunks_exact(2).map(|s| s[0] * 0.4 + s[1] * 0.9).collect();
        assert_eq!(decide(analyze(&mono, rate).unwrap()), Side::Right);
    }

    #[test]
    fn the_quiet_sound_is_soft_short_and_only_where_it_is_not_heard() {
        let p = quiet_pattern(RATE);
        assert_eq!(p.len() % 2, 0);
        assert!(p.iter().all(|s| s.abs() <= QUIET.amplitude + 1e-4));
        assert!(duration_of(&QUIET) + 0.6 < LISTEN_MS as f32 / 1000.0, "{}", duration_of(&QUIET));
        // A click would be heard: what the fades leave below 15 kHz has to be far under what is in the band.
        let left: Vec<f32> = p.chunks_exact(2).map(|s| s[0]).collect();
        let size = left.len().next_power_of_two();
        let mut spectrum: Vec<Complex32> = left.iter().map(|&s| Complex32::new(s, 0.0)).collect();
        spectrum.resize(size, Complex32::new(0.0, 0.0));
        FftPlanner::new().plan_fft_forward(size).process(&mut spectrum);
        let hz = |bin: usize| bin as f32 * RATE as f32 / size as f32;
        let energy = |from: f32, to: f32| -> f32 {
            spectrum[..size / 2].iter().enumerate().filter(|(k, _)| hz(*k) >= from && hz(*k) < to).map(|(_, c)| c.norm_sqr()).sum()
        };
        let band = energy(QUIET.low_hz, QUIET.high_hz);
        let below = energy(20.0, 15_000.0);
        let db = 10.0 * (below / band).log10();
        assert!(db < -70.0, "below 15 kHz: {db:.1} dB");
        // Nothing to play at a rate that cannot carry it.
        assert!(quiet_pattern(32_000).is_empty());
    }

    #[test]
    fn the_quiet_sweeps_are_told_apart_and_found_whenever_they_arrive() {
        let only_left = analyze_quiet(&quiet_recording(RATE, 1.0, 0.0, 0.1, 0.0, false, 1), RATE).unwrap();
        assert!(only_left.right < only_left.left * 0.1, "{only_left:?}");
        for late in [0.0, 0.2, 0.45] {
            let left = analyze_quiet(&quiet_recording(RATE, 1.0, 0.5, late, 0.005, false, 7), RATE).unwrap();
            assert_eq!(decide_with(left, QUIET_SURE_DB), Side::Left, "late {late}: {left:?}");
            let right = analyze_quiet(&quiet_recording(RATE, 0.3, 0.9, late, 0.005, false, 9), RATE).unwrap();
            assert_eq!(decide_with(right, QUIET_SURE_DB), Side::Right, "late {late}: {right:?}");
        }
        assert_eq!(analyze_quiet(&quiet_recording(RATE, 0.0, 0.0, 0.2, 0.005, false, 13), RATE), None);
    }

    #[test]
    fn the_quiet_sweeps_hold_up_in_a_room_with_echoes_and_noise() {
        // 6 dB nearer to the left, heard at a tenth of what was played, with noise over the whole band louder than the sound itself.
        for seed in 0..8 {
            let rec = quiet_recording(RATE, 0.1, 0.05, 0.05 + seed as f32 * 0.05, 0.02, true, 40 + seed);
            let heard = analyze_quiet(&rec, RATE).unwrap();
            assert_eq!(decide_with(heard, QUIET_SURE_DB), Side::Left, "seed {seed}: {heard:?}");
        }
    }

    #[test]
    fn the_quiet_sweeps_read_at_44_1_khz_and_through_a_microphone_that_hears_less_up_high() {
        let rate = 44_100;
        let rec = quiet_recording(rate, 0.4, 0.9, 0.1, 0.002, true, 3);
        assert_eq!(decide_with(analyze_quiet(&rec, rate).unwrap(), QUIET_SURE_DB), Side::Right);
        // A microphone that is 3 dB down at 19 kHz and more above it: both sides sweep the same pitches, so both lose the same.
        for corner in [19_000.0, 18_000.0] {
            let rec = rolled_off(&quiet_recording(RATE, 1.0, 0.4, 0.1, 0.002, true, 5), RATE, corner);
            let got = analyze_quiet(&rec, RATE).map(|h| decide_with(h, QUIET_SURE_DB));
            assert!(matches!(got, Some(Side::Left) | None), "corner {corner}: {got:?}");
        }
    }

    #[test]
    fn a_rate_too_low_for_the_quiet_sweeps_hears_nothing_of_them() {
        // The same samples said to be at 32 kHz: the quiet sweeps cannot be in there.
        let rec = quiet_recording(RATE, 1.0, 0.5, 0.1, 0.002, false, 21);
        assert_eq!(analyze_quiet(&rec, 32_000), None);
    }

    #[test]
    fn each_kind_is_read_as_itself() {
        // The audible sweeps leave nothing that the quiet ones would find, and the other way round.
        let mut planner = FftPlanner::new();
        assert!(analyze_for(&QUIET, &recording(1.0, 0.5, 0.1, 0.01, true, 2), RATE, &mut planner).is_none());
        assert!(analyze_for(&AUDIBLE, &quiet_recording(RATE, 1.0, 0.5, 0.1, 0.002, true, 2), RATE, &mut planner).is_none());
    }

    /// How it holds up: in a room with echoes, with more and more noise, how often it finds the side that is 3 dB nearer.
    /// `cargo test -p tandem-core --release --lib how_it_holds_up -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn how_it_holds_up() {
        let mut line = String::from("sweep, 3 dB:");
        for noise in [0.05f32, 0.2, 0.5, 1.0, 2.0] {
            let mut right = 0;
            let tries = 40;
            for seed in 0..tries {
                let near_left = seed % 2 == 0;
                let (l, r) = if near_left { (0.1, 0.071) } else { (0.071, 0.1) };
                let got = analyze(&recording(l, r, 0.03 + (seed % 10) as f32 * 0.04, noise, true, 100 + seed), RATE).map(decide);
                if got == Some(if near_left { Side::Left } else { Side::Right }) {
                    right += 1;
                }
            }
            line += &format!("  noise {noise}: {right}/{tries}");
        }
        println!("{line}");
        // The quiet one, 6 dB nearer, at the stricter margin; and how often it moved the screen the wrong way.
        let mut line = String::from("quiet, 6 dB:");
        for noise in [0.005f32, 0.02, 0.05, 0.1, 0.2] {
            let (mut right, mut wrong) = (0, 0);
            let tries = 40;
            for seed in 0..tries {
                let near_left = seed % 2 == 0;
                let (l, r) = if near_left { (0.1, 0.05) } else { (0.05, 0.1) };
                let rec = quiet_recording(RATE, l, r, 0.03 + (seed % 10) as f32 * 0.04, noise, true, 100 + seed);
                match analyze_quiet(&rec, RATE).map(|h| decide_with(h, QUIET_SURE_DB)) {
                    Some(Side::Left) if near_left => right += 1,
                    Some(Side::Right) if !near_left => right += 1,
                    Some(Side::Left) | Some(Side::Right) => wrong += 1,
                    _ => {}
                }
            }
            line += &format!("  noise {noise}: {right}/{tries} ({wrong} wrong)");
        }
        println!("{line}");
    }

    /// For measuring in a real room: with `TANDEM_SIDE_WRITE=<file>` it writes the quiet pattern (stereo, 32-bit floats, 48 kHz) to play;
    /// with `TANDEM_SIDE_READ=<file>` it reads a recording of one channel at 48 kHz in the same format and says what it heard.
    /// `TANDEM_SIDE_READ=rec.f32 cargo test -p tandem-core --release --lib a_real_room -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn a_real_room() {
        if let Ok(path) = std::env::var("TANDEM_SIDE_WRITE") {
            let bytes: Vec<u8> = quiet_pattern(RATE).iter().flat_map(|s| s.to_le_bytes()).collect();
            std::fs::write(&path, bytes).unwrap();
            println!("wrote {path}");
        }
        if let Ok(path) = std::env::var("TANDEM_SIDE_READ") {
            let bytes = std::fs::read(&path).unwrap();
            let samples: Vec<f32> = bytes.chunks_exact(4).map(|b| f32::from_le_bytes([b[0], b[1], b[2], b[3]])).collect();
            let mut planner = FftPlanner::new();
            for (name, signal) in [("audible", &AUDIBLE), ("quiet", &QUIET)] {
                match analyze_for(signal, &samples, RATE, &mut planner) {
                    Some((h, stands_out)) => println!(
                        "{name}: left {:.5} right {:.5}, {:+.1} dB, {:.1} dB above the room, {:?}",
                        h.left,
                        h.right,
                        20.0 * (h.left / h.right).log10(),
                        20.0 * stands_out.log10(),
                        decide_with(h, QUIET_SURE_DB)
                    ),
                    None => println!("{name}: not heard"),
                }
            }
        }
    }
}

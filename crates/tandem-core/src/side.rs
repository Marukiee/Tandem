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

use std::f32::consts::PI;
use std::sync::Arc;

use rustfft::num_complex::Complex32;
use rustfft::{Fft, FftPlanner};

/// A moment of silence before the sound, for the sound card to open.
const LEAD_S: f32 = 0.12;
const SWEEP_S: f32 = 0.2;
const LOW_HZ: f32 = 400.0;
const HIGH_HZ: f32 = 6000.0;
/// Between two sweeps, for the echoes of a room to die down enough.
const GAP_S: f32 = 0.15;
/// The sound comes this many times, so one spoilt by a noise in the room does not decide it.
const REPEATS: usize = 3;
const AMPLITUDE: f32 = 0.45;

/// How long the device that listens records, from the moment it said it listens: the sound, the time it takes to arrive, and the echoes.
pub const LISTEN_MS: u32 = 2000;

/// A difference smaller than this (in decibels) does not say which side is nearer.
pub const SURE_DB: f32 = 1.5;

/// The sweep of one side (0 left, 1 right), once: up on the left, down on the right. An exponential sweep gives every octave the same
/// time, so the low and the high end both come through a small speaker.
fn sweep(side: usize, rate: f32) -> Vec<f32> {
    let n = (SWEEP_S * rate) as usize;
    let k = (HIGH_HZ / LOW_HZ).ln();
    let samples: Vec<f32> = (0..n)
        .map(|i| {
            let t = i as f32 / rate;
            (2.0 * PI * LOW_HZ * SWEEP_S / k * ((t / SWEEP_S * k).exp() - 1.0)).sin()
        })
        .collect();
    let mut samples = shape(samples, rate, 0.01);
    if side == 1 {
        samples.reverse();
    }
    samples
}

/// A soft start and end, or the speaker clicks, and a click is heard by both sides; and the loudness of the whole to `AMPLITUDE`.
fn shape(mut samples: Vec<f32>, rate: f32, fade_s: f32) -> Vec<f32> {
    let n = samples.len();
    let fade = ((fade_s * rate) as usize).min(n / 2).max(1);
    for i in 0..fade {
        let ramp = 0.5 - 0.5 * (PI * i as f32 / fade as f32).cos();
        samples[i] *= ramp;
        samples[n - 1 - i] *= ramp;
    }
    let peak = samples.iter().fold(0.0f32, |m, s| m.max(s.abs())).max(1e-6);
    samples.iter_mut().for_each(|s| *s *= AMPLITUDE / peak);
    samples
}

/// How long the pattern lasts, in seconds.
pub fn duration() -> f32 {
    LEAD_S + REPEATS as f32 * (SWEEP_S + GAP_S)
}

/// The pattern as stereo samples, left and right after each other, at `rate` samples a second.
pub fn pattern(rate: u32) -> Vec<f32> {
    pattern_at(rate, 1.0)
}

/// The same, at `loudness` (0 to 1) of the full level. A first try is soft, so a quiet room is not startled; when the device did not hear
/// it well, the second try is at full loudness.
pub fn pattern_at(rate: u32, loudness: f32) -> Vec<f32> {
    let rate_f = rate as f32;
    let gain = loudness.clamp(0.05, 1.0);
    let (left, right) = (sweep(0, rate_f), sweep(1, rate_f));
    let frames = (duration() * rate_f) as usize;
    let mut out = vec![0.0f32; frames * 2];
    let step = ((SWEEP_S + GAP_S) * rate_f) as usize;
    let lead = (LEAD_S * rate_f) as usize;
    for repeat in 0..REPEATS {
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
    let floor = 1e-9;
    let db = 20.0 * ((heard.left + floor) / (heard.right + floor)).log10();
    if db >= SURE_DB {
        Side::Left
    } else if db <= -SURE_DB {
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
    let rate_f = rate as f32;
    if samples.len() < (duration() * rate_f) as usize / 2 {
        return None;
    }
    let mut planner = FftPlanner::new();
    let left_env = envelope(samples, &sweep(0, rate_f), &mut planner);
    let right_env = envelope(samples, &sweep(1, rate_f), &mut planner);
    // The sweeps come three times; the middle of the three is the loudness, so one spoilt by a noise in the room does not decide it. Two
    // moments that are closer than most of a step are one sweep and its echo.
    let apart = ((SWEEP_S + GAP_S) * 0.8 * rate_f) as usize;
    let left = median(peaks(&left_env, REPEATS, apart));
    let right = median(peaks(&right_env, REPEATS, apart));
    // What the room gives at any moment, to tell a sound from no sound.
    let room = median(left_env.iter().chain(right_env.iter()).step_by(7).copied().collect());
    if left.max(right) < room * 6.0 {
        return None;
    }
    Some(Heard { left, right })
}

#[cfg(test)]
mod tests {
    use super::*;

    const RATE: u32 = 48_000;

    /// What a microphone hears: each side at its own loudness, late by `late` seconds, with the echoes of a room and noise. The two
    /// speakers stand in different places, so their echoes come at other moments, which makes some pitches louder from one than the other.
    fn recording(left_gain: f32, right_gain: f32, late: f32, noise: f32, room: bool, seed: u32) -> Vec<f32> {
        let stereo = pattern(RATE);
        let pad = (late * RATE as f32) as usize;
        let tail = (LISTEN_MS as usize * RATE as usize / 1000).saturating_sub(pad + stereo.len() / 2);
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
                    let d = (delay_ms / 1000.0 * RATE as f32) as usize;
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
    }
}

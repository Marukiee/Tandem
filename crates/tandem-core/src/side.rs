//! On which side of a computer another device stands, found by ear. The computer plays a short pattern from its left and its right
//! speaker; the other device records it with its microphone and says how loud each side was. The side that sounded louder is the side
//! it stands on. Used to put a second screen on the right side, and to place a computer next to this one for the shared mouse.
//!
//! The left and the right speaker play a different sound (a sweep up and a sweep down, or other notes), and the recording is compared
//! with each of them (a matched filter: for a sound that is known in advance, nothing finds it better in noise). So it does not matter when
//! the recording started or in which order the sounds came, and a side that was too soft to hear is still known to be the soft one. The
//! apps only play and record; the sounds and the reading of them are here, so every system does it the same way.

use std::f32::consts::PI;
use std::sync::Arc;

use rustfft::num_complex::Complex32;
use rustfft::{Fft, FftPlanner};

/// The sounds to choose from. Which one is played is up to the person; all are read the same way.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Default)]
pub enum Sound {
    /// A quick sweep up from low to high on the left and down on the right, like a speaker that tunes itself to a room. The surest.
    #[default]
    Sweep,
    /// Four bell notes, going up on the left and down on the right.
    Chime,
    /// Three short beeps, going up on the left and down on the right.
    Beep,
}

impl Sound {
    pub fn from_code(code: u8) -> Sound {
        match code {
            1 => Sound::Chime,
            2 => Sound::Beep,
            _ => Sound::Sweep,
        }
    }

    pub fn code(self) -> u8 {
        match self {
            Sound::Sweep => 0,
            Sound::Chime => 1,
            Sound::Beep => 2,
        }
    }
}

/// Silence before the first sound, so a recorder that starts a little late misses nothing.
const LEAD_S: f32 = 0.7;
const GAP_S: f32 = 0.25;
/// Each side sounds this many times, so one spoilt by a noise in the room does not decide it.
const REPEATS: usize = 3;
const AMPLITUDE: f32 = 0.45;

/// How long the device that listens records, from the moment it is asked: the whole pattern and a margin for a late start.
pub const LISTEN_MS: u32 = 6000;

/// A difference smaller than this (in decibels) does not say which side is nearer.
pub const SURE_DB: f32 = 1.5;

/// The sound of one side (0 left, 1 right), once.
fn unit(sound: Sound, side: usize, rate: f32) -> Vec<f32> {
    match sound {
        Sound::Sweep => {
            // An exponential sweep: every octave gets the same time, so the low and the high end both come through a small speaker.
            let (low, high, seconds) = (400.0f32, 6000.0f32, 0.35f32);
            let n = (seconds * rate) as usize;
            let k = (high / low).ln();
            let samples: Vec<f32> = (0..n)
                .map(|i| {
                    let t = i as f32 / rate;
                    let phase = 2.0 * PI * low * seconds / k * ((t / seconds * k).exp() - 1.0);
                    phase.sin()
                })
                .collect();
            let mut samples = shape(samples, rate, 0.012);
            if side == 1 {
                samples.reverse();
            }
            samples
        }
        Sound::Chime => {
            // The same four notes on both sides, up on the left and down on the right. The same notes, because a room makes some pitches
            // louder than others where the device stands: different notes left and right would make that the difference, not the side.
            let mut notes = [784.0f32, 987.8, 1174.7, 1568.0];
            if side == 1 {
                notes.reverse();
            }
            notes_of(&notes, 0.11, 0.28, true, rate)
        }
        Sound::Beep => {
            // Three short beeps going up on the left and down on the right, for the same reason as the chime.
            let mut notes = [1800.0f32, 2400.0, 3000.0];
            if side == 1 {
                notes.reverse();
            }
            notes_of(&notes, 0.1, 0.0, false, rate)
        }
    }
}

/// The partials of a small bell: how far above the note, how loud, and how much quicker they die away.
const BELL: [(f32, f32, f32); 5] = [(1.0, 1.0, 0.0), (2.0, 0.6, 2.0), (3.0, 0.45, 4.0), (4.2, 0.35, 6.0), (5.4, 0.25, 8.0)];

/// Notes one after the other, `step_s` apart, each ringing on for `ring_s` after the last starts. A bell dies away and has a little of the
/// octave above; a beep is a plain tone that stops.
fn notes_of(notes: &[f32], step_s: f32, ring_s: f32, bell: bool, rate: f32) -> Vec<f32> {
    let n = (((notes.len() - 1) as f32 * step_s + step_s.max(ring_s)) * rate) as usize;
    let mut out = vec![0.0f32; n];
    let note_len = (step_s * rate) as usize;
    for (index, freq) in notes.iter().enumerate() {
        let start = (index as f32 * step_s * rate) as usize;
        let len = if bell { n - start } else { note_len.min(n - start) };
        let fade = (0.008 * rate) as usize;
        for i in 0..len {
            let t = i as f32 / rate;
            let level = if bell {
                (-t * 9.0).exp() * (t * 400.0).min(1.0)
            } else {
                // A beep of its own with soft edges, so the three do not click into each other.
                let edge = i.min(len - 1 - i) as f32 / fade as f32;
                edge.min(1.0)
            };
            // A bell has partials far above its note, which spreads it over many pitches: a room that swallows one pitch where the
            // device stands does not swallow them all.
            let tone = if bell {
                BELL.iter().map(|&(ratio, gain, damp)| gain * (-t * damp).exp() * (2.0 * PI * freq * ratio * t).sin()).sum::<f32>()
            } else {
                (2.0 * PI * freq * t).sin()
            };
            out[start + i] += level * tone;
        }
    }
    shape(out, rate, 0.004)
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

/// The pattern as stereo samples, left and right after each other, at `rate` samples a second: left, right, three times.
pub fn pattern(sound: Sound, rate: u32) -> Vec<f32> {
    let rate_f = rate as f32;
    let (left, right) = (unit(sound, 0, rate_f), unit(sound, 1, rate_f));
    let gap = (GAP_S * rate_f) as usize;
    let lead = (LEAD_S * rate_f) as usize;
    let frames = lead + REPEATS * (left.len() + right.len() + 2 * gap);
    let mut out = vec![0.0f32; frames * 2];
    let mut at = lead;
    for _ in 0..REPEATS {
        for (i, s) in left.iter().enumerate() {
            out[(at + i) * 2] = *s;
        }
        at += left.len() + gap;
        for (i, s) in right.iter().enumerate() {
            out[(at + i) * 2 + 1] = *s;
        }
        at += right.len() + gap;
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

/// Reads a recording (one channel, `rate` samples a second) for the pattern of `sound`. Nothing when it was not heard at all.
pub fn analyze(sound: Sound, samples: &[f32], rate: u32) -> Option<Heard> {
    let rate_f = rate as f32;
    if samples.len() < rate as usize {
        return None;
    }
    let mut planner = FftPlanner::new();
    let left_env = envelope(samples, &unit(sound, 0, rate_f), &mut planner);
    let right_env = envelope(samples, &unit(sound, 1, rate_f), &mut planner);
    // Each sound comes three times, more than half a second apart; the middle of the three is its loudness, so one spoilt by a noise in
    // the room does not decide it.
    let apart = (0.5 * rate_f) as usize;
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

    /// What a microphone hears: each side of the pattern at its own loudness, late by `late` seconds, with the echoes of a room and noise.
    fn recording(sound: Sound, left_gain: f32, right_gain: f32, late: f32, noise: f32, room: bool, seed: u32) -> Vec<f32> {
        let stereo = pattern(sound, RATE);
        let pad = (late * RATE as f32) as usize;
        let tail = RATE as usize / 2;
        let channel = |c: usize, gain: f32| -> Vec<f32> {
            let mut v = vec![0.0f32; pad];
            v.extend(stereo.chunks_exact(2).map(|s| s[c] * gain));
            v.extend(std::iter::repeat_n(0.0, tail));
            v
        };
        let (left, right) = (channel(0, left_gain), channel(1, right_gain));
        let mut out: Vec<f32> = left.iter().zip(&right).map(|(l, r)| l + r).collect();
        if room {
            // Walls a few metres away: copies of the sound, later and softer. The two speakers stand in different places, so their
            // copies come at other moments, which makes some pitches louder from one speaker than from the other.
            let echoes = [
                (&left, [(9.0f32, 0.6f32), (23.0, 0.45), (41.0, 0.3), (77.0, 0.2)]),
                (&right, [(11.5f32, 0.55f32), (19.0, 0.5), (47.0, 0.3), (69.0, 0.25)]),
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

    const ALL: [Sound; 3] = [Sound::Sweep, Sound::Chime, Sound::Beep];

    #[test]
    fn the_pattern_is_stereo_and_never_sounds_on_both_sides_at_once() {
        for sound in ALL {
            let p = pattern(sound, RATE);
            assert_eq!(p.len() % 2, 0);
            assert!(p.chunks_exact(2).all(|s| s[0] == 0.0 || s[1] == 0.0), "{sound:?}");
            assert!(p.iter().all(|s| s.abs() <= AMPLITUDE + 1e-4), "{sound:?}");
        }
    }

    #[test]
    fn every_sound_finds_the_near_side_whenever_the_recording_starts() {
        for sound in ALL {
            for late in [0.0, 0.37, 1.2] {
                let left = analyze(sound, &recording(sound, 1.0, 0.5, late, 0.02, false, 7), RATE).unwrap();
                assert_eq!(decide(left), Side::Left, "{sound:?} late {late}: {left:?}");
                let right = analyze(sound, &recording(sound, 0.3, 0.9, late, 0.02, false, 9), RATE).unwrap();
                assert_eq!(decide(right), Side::Right, "{sound:?} late {late}: {right:?}");
            }
        }
    }

    #[test]
    fn the_loudness_found_follows_the_loudness_played() {
        let heard = analyze(Sound::Sweep, &recording(Sound::Sweep, 1.0, 0.5, 0.2, 0.01, false, 3), RATE).unwrap();
        assert!((heard.left / heard.right - 2.0).abs() < 0.2, "{heard:?}");
    }

    #[test]
    fn a_side_too_soft_to_hear_counts_as_the_soft_one() {
        for sound in ALL {
            let heard = analyze(sound, &recording(sound, 0.0, 0.8, 0.2, 0.01, false, 5), RATE).unwrap();
            assert_eq!(decide(heard), Side::Right, "{sound:?}: {heard:?}");
        }
    }

    #[test]
    fn about_as_loud_on_both_sides_is_unsure_and_silence_is_nothing() {
        for sound in ALL {
            let heard = analyze(sound, &recording(sound, 0.7, 0.68, 0.2, 0.01, false, 11), RATE).unwrap();
            assert_eq!(decide(heard), Side::Unsure, "{sound:?}: {heard:?}");
            assert_eq!(analyze(sound, &recording(sound, 0.0, 0.0, 0.2, 0.02, false, 13), RATE), None, "{sound:?}");
        }
    }

    #[test]
    fn a_room_with_echoes_and_noise_still_gives_the_side() {
        for sound in ALL {
            let heard = analyze(sound, &recording(sound, 0.2, 0.1, 0.5, 0.1, true, 17), RATE).unwrap();
            assert_eq!(decide(heard), Side::Left, "{sound:?}: {heard:?}");
        }
    }

    #[test]
    fn another_sample_rate_reads_the_same() {
        let rate = 44_100;
        let stereo = pattern(Sound::Chime, rate);
        let mono: Vec<f32> = stereo.chunks_exact(2).map(|s| s[0] * 0.4 + s[1] * 0.9).collect();
        assert_eq!(decide(analyze(Sound::Chime, &mono, rate).unwrap()), Side::Right);
    }

    /// Which sound holds up best: in a room with echoes, with more and more noise, how often each finds the side that is 3 dB nearer.
    /// `cargo test -p tandem-core --release --lib which_sound_holds_up_best -- --ignored --nocapture`
    #[test]
    #[ignore]
    fn which_sound_holds_up_best() {
        for (db, soft) in [(3, 0.071f32), (6, 0.05)] {
        for sound in ALL {
            let mut line = format!("{db} dB {sound:?}:");
            for noise in [0.05f32, 0.2, 0.5, 1.0, 2.0] {
                let mut right = 0;
                let tries = 20;
                for seed in 0..tries {
                    let near_left = seed % 2 == 0;
                    let (l, r) = if near_left { (0.1, soft) } else { (soft, 0.1) };
                    let got = analyze(sound, &recording(sound, l, r, 0.1 + seed as f32 * 0.07, noise, true, 100 + seed), RATE).map(decide);
                    if got == Some(if near_left { Side::Left } else { Side::Right }) {
                        right += 1;
                    }
                }
                line += &format!("  noise {noise}: {right}/{tries}");
            }
            println!("{line}");
        }
        }
    }
}

//! Which H.264 decoder of GStreamer shows a second screen best on this computer. Laptops differ: one has VA-API with H.264 (Intel, AMD
//! on most distributions), another only the decoder of the processor, an ARM one a V4L2 decoder, and a decoder that works can still be
//! slow to hand a picture on (the ones built on GStreamer's own H.264 base hold pictures back to put them in order, which cost 50 ms a
//! picture on the graphics card of a laptop that was measured). So each one that is there is tried with a short stream made here, sent
//! in the pace of a screen through the same parser and framing as the real thing, and the one that hands a picture on soonest is kept. A
//! decoder of the graphics chip wins when it is nearly as fast, since it spares the processor and the battery. The answer is kept in a
//! file and found again until GStreamer or its decoders change.

use std::io::{BufRead, BufReader, Write};
use std::process::{Command, Stdio};
use std::sync::{mpsc, OnceLock};
use std::time::{Duration, Instant};

use openh264::encoder::Encoder;
use openh264::formats::{RgbSliceU8, YUVBuffer};

const WIDTH: usize = 1280;
const HEIGHT: usize = 720;
const FRAMES: usize = 40;
/// The first pictures include the start of the pipeline, which is not what is measured.
const WARM_UP: usize = 8;
const PACE: Duration = Duration::from_millis(33);
/// How much slower a decoder of the graphics chip may be than the fastest and still be chosen.
const HARDWARE_MARGIN_MS: f64 = 3.0;

/// The decoder to use, with its settings, as it goes in a pipeline; nothing when GStreamer has none for H.264.
pub fn choice() -> Option<String> {
    static CHOICE: OnceLock<Option<String>> = OnceLock::new();
    CHOICE.get_or_init(choose).clone()
}

/// Finds the answer in the background at start, so the first second screen does not wait for it.
pub fn warm_up() {
    std::thread::Builder::new()
        .name("tandem-decoder-probe".into())
        .spawn(|| {
            let _ = choice();
        })
        .ok();
}

/// The decoders there are, best kind first. The graphics card of a laptop with two is kept apart (see `choose`).
fn candidates() -> (Vec<String>, Vec<String>) {
    let threads = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(2).clamp(1, 4);
    let mut found = Vec::new();
    for hardware in ["vah264dec", "vaapih264dec", "v4l2slh264dec", "v4l2h264dec"] {
        if crate::live::gst_inspect(hardware).is_some() {
            found.push(hardware.to_string());
        }
    }
    // Threads per slice cost no delay; threads per picture hold a picture back for each thread.
    match crate::live::gst_inspect("avdec_h264") {
        Some(about) if about.contains("thread-type") => found.push(format!("avdec_h264 thread-type=slice max-threads={threads}")),
        Some(_) => found.push("avdec_h264 max-threads=1".into()),
        None => {}
    }
    if crate::live::gst_inspect("openh264dec").is_some() {
        found.push("openh264dec".into());
    }
    let last = ["nvh264dec", "nvh264sldec"].into_iter().filter(|d| crate::live::gst_inspect(d).is_some()).map(str::to_string).collect();
    (found, last)
}

fn is_hardware(decoder: &str) -> bool {
    !(decoder.starts_with("avdec_h264") || decoder.starts_with("openh264dec"))
}

fn choose() -> Option<String> {
    let (found, last) = candidates();
    let key = format!("{} | {} | {}", gst_version(), found.join(", "), last.join(", "));
    if let Some(kept) = read_kept(&key) {
        log::info!("second screen decoder: {kept} (found before)");
        return Some(kept);
    }
    let Some(stream) = test_stream() else {
        log::info!("second screen decoder: no test stream could be made, taking {:?}", found.first());
        return found.into_iter().next().or_else(|| last.into_iter().next());
    };
    let mut measured: Vec<(String, f64)> = Vec::new();
    for decoder in &found {
        match measure(decoder, &stream) {
            Some(ms) => {
                log::info!("second screen decoder {decoder}: {ms:.1} ms a picture");
                measured.push((decoder.clone(), ms));
            }
            None => log::info!("second screen decoder {decoder}: does not work here"),
        }
    }
    // The graphics card of a laptop with two is woken for it and costs battery: only when nothing else works.
    if measured.is_empty() {
        for decoder in &last {
            if let Some(ms) = measure(decoder, &stream) {
                log::info!("second screen decoder {decoder}: {ms:.1} ms a picture");
                measured.push((decoder.clone(), ms));
            }
        }
    }
    let best = measured.iter().map(|(_, ms)| *ms).fold(f64::INFINITY, f64::min);
    let chosen = measured
        .iter()
        .find(|(d, ms)| is_hardware(d) && *ms <= best + HARDWARE_MARGIN_MS)
        .or_else(|| measured.iter().find(|(_, ms)| *ms <= best))
        .map(|(d, _)| d.clone());
    match &chosen {
        Some(decoder) => {
            log::info!("second screen decoder: {decoder}");
            keep(&key, decoder);
        }
        None => log::warn!("second screen decoder: none of {found:?} {last:?} decodes H.264 here"),
    }
    chosen
}

/// A short stream like a screen sends: a still background with a square that moves, one access unit per picture.
fn test_stream() -> Option<Vec<Vec<u8>>> {
    let mut encoder = Encoder::new().ok()?;
    let mut stream = Vec::with_capacity(FRAMES);
    for n in 0..FRAMES {
        let mut rgb = vec![0u8; WIDTH * HEIGHT * 3];
        for y in 0..HEIGHT {
            for x in 0..WIDTH {
                let at = (y * WIDTH + x) * 3;
                let inside = x >= 40 + n * 20 && x < 140 + n * 20 && (200..300).contains(&y);
                let value = if inside { [250, 250, 250] } else { [(x * 255 / WIDTH) as u8, (y * 255 / HEIGHT) as u8, 90] };
                rgb[at..at + 3].copy_from_slice(&value);
            }
        }
        let yuv = YUVBuffer::from_rgb_source(RgbSliceU8::new(&rgb, (WIDTH, HEIGHT)));
        let bytes = encoder.encode(&yuv).ok()?.to_vec();
        if bytes.is_empty() {
            return None;
        }
        stream.push(bytes);
    }
    Some(stream)
}

/// Sends the stream through the decoder in the pace of a screen and returns the time from writing a picture to its coming out, the
/// median over the pictures after the first few. Nothing when the decoder does not get through most of them.
fn measure(decoder: &str, stream: &[Vec<u8>]) -> Option<f64> {
    let pipeline = format!("fdsrc fd=0 ! h264parse ! {decoder} ! videoconvert ! fakesink silent=false sync=false");
    let mut command = Command::new("gst-launch-1.0");
    tandem_winsys::system_env(&mut command);
    command.arg("-v").args(pipeline.split_whitespace()).stdin(Stdio::piped()).stdout(Stdio::piped()).stderr(Stdio::null());
    let mut child = command.spawn().ok()?;
    let mut input = child.stdin.take()?;
    let output = child.stdout.take()?;
    let (tx, rx) = mpsc::channel::<Instant>();
    let reader = std::thread::spawn(move || {
        for line in BufReader::new(output).lines().map_while(Result::ok) {
            // Each picture that reaches the end is told by the sink as its last message.
            if line.contains("fakesink0: last-message = chain") && tx.send(Instant::now()).is_err() {
                break;
            }
        }
    });
    let mut sent = Vec::with_capacity(stream.len());
    for (n, unit) in stream.iter().enumerate() {
        let mut bytes = crate::live::framed(unit, n == 0).to_vec();
        bytes.extend_from_slice(&crate::live::AU_END);
        if input.write_all(&bytes).and_then(|_| input.flush()).is_err() {
            break;
        }
        sent.push(Instant::now());
        std::thread::sleep(PACE);
    }
    let mut came = Vec::with_capacity(sent.len());
    let deadline = Instant::now() + Duration::from_millis(1500);
    while came.len() < sent.len() {
        let left = deadline.saturating_duration_since(Instant::now());
        match rx.recv_timeout(left) {
            Ok(at) => came.push(at),
            Err(_) => break,
        }
    }
    drop(input);
    let _ = child.kill();
    let _ = child.wait();
    let _ = reader.join();
    if came.len() < sent.len() * 3 / 4 {
        return None;
    }
    let mut times: Vec<f64> = came.iter().zip(&sent).skip(WARM_UP).map(|(c, s)| c.saturating_duration_since(*s).as_secs_f64() * 1000.0).collect();
    if times.is_empty() {
        return None;
    }
    times.sort_by(|a, b| a.total_cmp(b));
    Some(times[times.len() / 2])
}

fn gst_version() -> String {
    let mut command = Command::new("gst-launch-1.0");
    tandem_winsys::system_env(&mut command);
    command
        .arg("--version")
        .stderr(Stdio::null())
        .output()
        .ok()
        .and_then(|o| String::from_utf8_lossy(&o.stdout).lines().next().map(str::to_string))
        .unwrap_or_default()
}

fn kept_file() -> Option<std::path::PathBuf> {
    let base = std::env::var_os("XDG_CACHE_HOME").map(std::path::PathBuf::from).or_else(|| std::env::var_os("HOME").map(|h| std::path::Path::new(&h).join(".cache")))?;
    Some(base.join("tandem").join("decoder.txt"))
}

/// The decoder found before, when it was found for the same GStreamer and the same decoders.
fn read_kept(key: &str) -> Option<String> {
    let text = std::fs::read_to_string(kept_file()?).ok()?;
    let (kept_key, decoder) = text.split_once('\n')?;
    (kept_key == key && !decoder.trim().is_empty()).then(|| decoder.trim().to_string())
}

fn keep(key: &str, decoder: &str) {
    let Some(file) = kept_file() else { return };
    if let Some(dir) = file.parent() {
        let _ = std::fs::create_dir_all(dir);
    }
    let _ = std::fs::write(file, format!("{key}\n{decoder}\n"));
}

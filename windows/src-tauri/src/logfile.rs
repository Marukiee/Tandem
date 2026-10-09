//! A log file next to the data of the app, so that what went wrong on a PC that cannot be looked at can still be read.
//! The core talks through the `log` facade; on Windows nothing listens to it unless it is this.

use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use log::{LevelFilter, Log, Metadata, Record};
use tauri::{AppHandle, Manager};

struct FileLog {
    file: Mutex<File>,
    level: LevelFilter,
}

impl Log for FileLog {
    fn enabled(&self, metadata: &Metadata) -> bool {
        // The bus library of Linux tells every call it makes and every property it could not read (a harmless thing): that buried the
        // lines that matter in a few hours. It is heard again when the level is asked for with `TANDEM_LOG`.
        let chatty = metadata.target().starts_with("zbus") || metadata.target() == "tracing::span";
        if chatty && self.level < LevelFilter::Debug {
            return metadata.level() <= log::Level::Error;
        }
        metadata.level() <= self.level
    }

    fn log(&self, record: &Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        let line = format!("{} {:5} {}: {}\n", stamp(SystemTime::now()), record.level(), record.target(), record.args());
        if let Ok(mut file) = self.file.lock() {
            let _ = file.write_all(line.as_bytes());
        }
    }

    fn flush(&self) {
        if let Ok(mut file) = self.file.lock() {
            let _ = file.flush();
        }
    }
}

/// The folder with the log file.
pub fn folder(app: &AppHandle) -> Option<PathBuf> {
    app.path().app_log_dir().ok()
}

/// Starts logging to `tandem.log`. The file of the run before is kept as `tandem.old.log`, and a panic ends up in the
/// log too, since a window without a console has nowhere else to say it.
pub fn init(app: &AppHandle) {
    let Some(dir) = folder(app) else { return };
    let _ = std::fs::create_dir_all(&dir);
    let path = dir.join("tandem.log");
    let _ = std::fs::rename(&path, dir.join("tandem.old.log"));
    let Ok(file) = OpenOptions::new().create(true).append(true).open(&path) else { return };
    let level = match std::env::var("TANDEM_LOG").as_deref() {
        Ok("debug") => LevelFilter::Debug,
        Ok("trace") => LevelFilter::Trace,
        _ => LevelFilter::Info,
    };
    if log::set_boxed_logger(Box::new(FileLog { file: Mutex::new(file), level })).is_err() {
        return;
    }
    log::set_max_level(level);
    std::panic::set_hook(Box::new(|info| {
        log::error!("panic: {info}");
        log::logger().flush();
    }));
    log::info!("Tandem {} starts, logging to {}", app.package_info().version, path.display());
}

/// "2026-10-03 13:13:24.273Z": the time in UTC, to the millisecond.
fn stamp(time: SystemTime) -> String {
    let since = time.duration_since(UNIX_EPOCH).unwrap_or_default();
    let (days, rest) = (since.as_secs() / 86_400, since.as_secs() % 86_400);
    let (year, month, day) = civil(days as i64);
    format!(
        "{year:04}-{month:02}-{day:02} {:02}:{:02}:{:02}.{:03}Z",
        rest / 3600,
        rest % 3600 / 60,
        rest % 60,
        since.subsec_millis()
    )
}

/// The calendar date of a day counted from 1970-01-01 (Howard Hinnant's algorithm).
fn civil(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let month = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    let year = yoe + era * 400 + i64::from(month <= 2);
    (year, month, day)
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::*;

    #[test]
    fn days_become_dates() {
        assert_eq!(civil(0), (1970, 1, 1));
        assert_eq!(civil(20_453), (2025, 12, 31));
        assert_eq!(civil(19_782), (2024, 2, 29));
        // A leap day.
        assert_eq!(civil(11_016), (2000, 2, 29));
    }

    #[test]
    fn a_moment_becomes_a_line() {
        let moment = UNIX_EPOCH + Duration::from_millis(1_791_032_736_287);
        assert_eq!(stamp(moment), "2026-10-03 13:05:36.287Z");
    }
}

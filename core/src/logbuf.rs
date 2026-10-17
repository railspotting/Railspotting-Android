//! Logging: to Android's log (logcat), to a short in-memory list the app shows, and to a file in
//! the app's storage that survives restarts and updates (`logs/receiver.log`, rotated at
//! [MAX_FILE] to `receiver.log.1`). Android's own log keeps only minutes on a busy phone.
//! Nothing secret is logged: keys and ID tokens never reach a log line.

use std::collections::VecDeque;
use std::fs::{File, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

pub const KEEP: usize = 200;
pub const TAG: &str = "RailSpotting";
/// The log file's size before it rotates; with the one older file, at most twice this.
pub const MAX_FILE: u64 = 1024 * 1024;

struct LogFile {
    path: PathBuf,
    file: File,
    size: u64,
}

struct Logger {
    lines: Mutex<VecDeque<String>>,
    file: Mutex<Option<LogFile>>,
    level: log::LevelFilter,
}

static LOGGER: OnceLock<Logger> = OnceLock::new();

/// Install the logger once; later calls do nothing.
pub fn init(debug: bool) {
    let level = if debug { log::LevelFilter::Debug } else { log::LevelFilter::Info };
    let logger = LOGGER.get_or_init(|| Logger { lines: Mutex::new(VecDeque::new()), file: Mutex::new(None), level });
    if log::set_logger(logger).is_ok() {
        log::set_max_level(level);
    }
}

/// Also write the log to `dir/receiver.log` from now on.
pub fn to_file(dir: &Path) {
    let Some(logger) = LOGGER.get() else { return };
    if let Err(e) = std::fs::create_dir_all(dir) {
        log::warn!("no log file: {e}");
        return;
    }
    let path = dir.join("receiver.log");
    match OpenOptions::new().create(true).append(true).open(&path) {
        Ok(file) => {
            let size = file.metadata().map(|m| m.len()).unwrap_or(0);
            *logger.file.lock().unwrap_or_else(|p| p.into_inner()) = Some(LogFile { path, file, size });
        }
        Err(e) => log::warn!("no log file: {e}"),
    }
}

/// `2026-10-14 12:01:18Z` for Unix seconds (UTC, so the file needs no time zone database).
pub fn utc_stamp(t: f64) -> String {
    let secs = t.floor() as i64;
    let (days, rem) = (secs.div_euclid(86_400), secs.rem_euclid(86_400));
    // civil date from days since 1970 (Howard Hinnant's algorithm)
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + if m <= 2 { 1 } else { 0 };
    format!("{y:04}-{m:02}-{d:02} {:02}:{:02}:{:02}Z", rem / 3600, rem % 3600 / 60, rem % 60)
}

fn write_file(lf: &mut LogFile, line: &str) {
    if lf.size > MAX_FILE {
        let old = lf.path.with_extension("log.1");
        let _ = std::fs::rename(&lf.path, &old);
        match File::create(&lf.path) {
            Ok(f) => {
                lf.file = f;
                lf.size = 0;
            }
            Err(_) => return,
        }
    }
    if writeln!(lf.file, "{line}").is_ok() {
        lf.size += line.len() as u64 + 1;
    }
}

/// The newest lines, oldest first.
pub fn lines() -> Vec<String> {
    LOGGER.get().map(|l| l.lines.lock().unwrap_or_else(|p| p.into_inner()).iter().cloned().collect())
        .unwrap_or_default()
}

impl log::Log for Logger {
    /// Debug lines from RailSpotting's own crates only; the HTTP and TLS libraries stay at info.
    fn enabled(&self, m: &log::Metadata) -> bool {
        let max = if m.target().starts_with("railspotting") { self.level } else { self.level.min(log::LevelFilter::Info) };
        m.level() <= max
    }

    fn log(&self, r: &log::Record) {
        if !self.enabled(r.metadata()) {
            return;
        }
        let text = format!("{}", r.args());
        write_system(r.level(), &text);
        let line = format!("{} {} {text}", utc_stamp(railspotting_common::now()), r.level());
        if let Some(lf) = self.file.lock().unwrap_or_else(|p| p.into_inner()).as_mut() {
            write_file(lf, &line);
        }
        let mut lines = self.lines.lock().unwrap_or_else(|p| p.into_inner());
        lines.push_back(line);
        while lines.len() > KEEP {
            lines.pop_front();
        }
    }

    fn flush(&self) {}
}

#[cfg(target_os = "android")]
fn write_system(level: log::Level, text: &str) {
    use std::ffi::{c_char, c_int, CString};
    #[link(name = "log")]
    extern "C" {
        fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }
    let prio = match level {
        log::Level::Error => 6,
        log::Level::Warn => 5,
        log::Level::Info => 4,
        log::Level::Debug => 3,
        log::Level::Trace => 2,
    };
    let (Ok(tag), Ok(msg)) = (CString::new(TAG), CString::new(text.replace('\0', " "))) else { return };
    // SAFETY: both pointers are valid NUL-terminated strings for the length of the call.
    unsafe {
        __android_log_write(prio, tag.as_ptr(), msg.as_ptr());
    }
}

#[cfg(not(target_os = "android"))]
fn write_system(level: log::Level, text: &str) {
    eprintln!("{level} {text}");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stamps_are_utc() {
        assert_eq!(utc_stamp(0.0), "1970-01-01 00:00:00Z");
        assert_eq!(utc_stamp(1_791_925_219.18), "2026-10-13 21:00:19Z");
        assert_eq!(utc_stamp(951_782_400.0), "2000-02-29 00:00:00Z");
    }

    #[test]
    fn the_file_rotates() {
        let dir = std::env::temp_dir().join(format!("rs-log-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("receiver.log");
        let mut lf = LogFile { file: File::create(&path).unwrap(), path: path.clone(), size: MAX_FILE + 1 };
        write_file(&mut lf, "after the rotation");
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "after the rotation\n");
        assert!(dir.join("receiver.log.1").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }
}

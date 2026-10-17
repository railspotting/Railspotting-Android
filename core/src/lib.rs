//! The RailSpotting receiver core for the Android app.
//!
//! The app (Kotlin) handles Google sign-in, the Keystore, location, the foreground service, USB
//! permission, audio output and the screens. This library is the receiver: the fixed receiver's
//! own SDR processing, outbox and signing code (the `receiver` submodule's crates), plus the
//! mobile contract (v1.7).
//! The app reaches it through a small JNI surface (`jni_api`) that passes JSON strings.

// ApiError carries the reply body, as in the proto crate.
#![allow(clippy::result_large_err)]

pub mod agent;
pub mod link;
pub mod location;
pub mod logbuf;
pub mod nearby;
pub mod radio;

#[cfg(target_os = "android")]
mod jni_api;

use agent::{Agent, Options, Phone};
use link::SkewClock;
use location::Fix;
use serde_json::{json, Value};
use radio::Mode;
use std::path::PathBuf;
use std::sync::{Arc, LazyLock, Mutex, MutexGuard};

/// The dongle as the app last described it, kept across receiver restarts.
#[derive(Debug, Clone, Copy)]
struct Kit {
    fd: Option<i64>,
    mode: Mode,
    /// Tuner gain in dB; None is automatic.
    gain: Option<f64>,
    live: bool,
}

/// The one receiver this app process runs, and its dongle.
pub struct Core {
    pub skew: Arc<SkewClock>,
    agent: Mutex<Option<Arc<Agent>>>,
    kit: Mutex<Kit>,
    nearby: Mutex<Option<nearby::Watcher>>,
}

pub static CORE: LazyLock<Core> = LazyLock::new(|| Core {
    skew: SkewClock::new(),
    agent: Mutex::new(None),
    kit: Mutex::new(Kit { fd: None, mode: Mode::Voice, gain: Some(radio::PHONE_GAIN_DB), live: false }),
    nearby: Mutex::new(None),
});

/// What `start` needs from the app.
pub struct StartArgs {
    pub server_url: String,
    pub state_dir: PathBuf,
    pub seed: Vec<u8>,
    pub device_id: String,
    pub app_version: String,
    pub share: bool,
}

impl Core {
    fn slot(&self) -> MutexGuard<'_, Option<Arc<Agent>>> {
        self.agent.lock().unwrap_or_else(|p| p.into_inner())
    }

    pub fn agent(&self) -> Option<Arc<Agent>> {
        self.slot().clone()
    }

    fn kit(&self) -> Kit {
        *self.kit.lock().unwrap_or_else(|p| p.into_inner())
    }

    fn set_kit(&self, f: impl FnOnce(&mut Kit)) {
        f(&mut self.kit.lock().unwrap_or_else(|p| p.into_inner()))
    }

    /// The app opened the dongle; `fd` is its USB file descriptor, which stays the app's.
    pub fn dongle_attached(&self, fd: i64) {
        self.set_kit(|k| k.fd = Some(fd));
        if let Some(a) = self.agent() {
            a.radio_attached(fd);
        }
    }

    pub fn dongle_detached(&self) {
        self.set_kit(|k| k.fd = None);
        if let Some(a) = self.agent() {
            a.radio().detach();
        }
    }

    pub fn set_mode(&self, mode: Mode) {
        self.set_kit(|k| k.mode = mode);
        if let Some(a) = self.agent() {
            a.set_mode(mode);
        }
    }

    pub fn set_gain(&self, gain: Option<f64>) {
        self.set_kit(|k| k.gain = gain);
        if let Some(a) = self.agent() {
            a.set_gain(gain);
        }
    }

    pub fn set_live(&self, on: bool) {
        self.set_kit(|k| k.live = on);
        if let Some(a) = self.agent() {
            a.radio().live.set_enabled(on);
        }
    }

    /// The live listen audio rate, in Hz.
    pub fn audio_rate(&self) -> f64 {
        self.agent().map_or(railspotting_dsp::voice::AUDIO_RATE, |a| a.radio().live.audio_rate())
    }

    /// Live listen audio: mono samples at `audio_rate`, waiting up to `timeout` for some.
    pub fn audio(&self, max: usize, timeout: std::time::Duration) -> Vec<f32> {
        match self.agent() {
            Some(a) => a.radio().live.take(max, timeout),
            None => {
                std::thread::sleep(timeout);
                vec![]
            }
        }
    }

    /// New EOT alerts from other receivers within `radius_km` of the phone (the public live
    /// map's feed). Blocks for the fetches: call it off the main thread.
    pub fn nearby_eot(&self, lat: f64, lon: f64, radius_km: f64) -> Value {
        let mut w = self.nearby.lock().unwrap_or_else(|p| p.into_inner());
        let watcher = w.get_or_insert_with(|| nearby::Watcher::new(nearby::site_fetch(nearby::SITE)));
        match watcher.check(lat, lon, radius_km, (self.skew.clock())()) {
            Ok(alerts) => json!({"ok": true, "alerts": alerts}),
            Err(e) => json!({"ok": false, "error": e}),
        }
    }

    /// The crossings near the phone for the car screen (the public live map's feed), train
    /// activity first. Blocks for the fetches.
    pub fn nearby_crossings(&self, lat: f64, lon: f64, radius_km: f64) -> Value {
        let mut w = self.nearby.lock().unwrap_or_else(|p| p.into_inner());
        let watcher = w.get_or_insert_with(|| nearby::Watcher::new(nearby::site_fetch(nearby::SITE)));
        match watcher.crossings(lat, lon, radius_km, 20, (self.skew.clock())()) {
            Ok(c) => json!({"ok": true, "crossings": c}),
            Err(e) => json!({"ok": false, "error": e}),
        }
    }

    /// The channel list for where the phone is (`fix`, when the app has one). Blocks for the
    /// headend calls.
    pub fn refresh_plan(&self, fix: &Value) -> Value {
        match self.agent() {
            Some(a) => a.refresh_plan(Fix::from_json(fix)),
            None => json!({"ok": false, "error": "not_running", "message": "the receiver is not running"}),
        }
    }

    /// Start the receiver (stopping one already running).
    pub fn start(&self, a: StartArgs) -> Result<(), String> {
        self.stop();
        let agent = Agent::new(Options {
            server_url: a.server_url,
            state_dir: a.state_dir,
            seed: a.seed,
            device_id: a.device_id,
            app_version: a.app_version,
            share: a.share,
            radio: radio::rtl_opener(),
            mode: self.kit().mode,
            gain: self.kit().gain,
            skew: Some(self.skew.clone()),
        })?;
        agent.start();
        let kit = self.kit();
        agent.radio().live.set_enabled(kit.live);
        if let Some(fd) = kit.fd {
            agent.radio_attached(fd);
        }
        *self.slot() = Some(agent);
        Ok(())
    }

    pub fn stop(&self) {
        let agent = self.slot().take();
        if let Some(a) = agent {
            a.stop();
        }
    }

    pub fn status(&self) -> Value {
        match self.agent() {
            Some(a) => a.status(),
            None => json!({"running": false}),
        }
    }

    pub fn set_phone(&self, state: &Value) {
        if let Some(a) = self.agent() {
            let share = a.mobile_state().get("share").and_then(Value::as_bool).unwrap_or(true);
            a.set_phone(Phone::from_json(state, share));
        }
    }

    /// A fix from the app; one the contract would refuse is dropped.
    pub fn add_fix(&self, fix: &Value) {
        let (Some(a), Some(f)) = (self.agent(), Fix::from_json(fix)) else { return };
        a.add_fix(f);
    }
}

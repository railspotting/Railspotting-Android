//! The mobile receiver while it runs: heartbeats, the event and report outbox, GPS fixes, the
//! RTL-SDR dongle and headend commands.
//!
//! It runs only while the app's foreground service does. The radio side is the fixed receiver's
//! own code (`SdrHandle`, `SdrSource`, the EOT decoder), in voice or EOT mode (`radio`). What
//! differs is the contract v1.7 part: the heartbeat's `mobile` block, /location, a fix on each
//! event, and `403 revoked`.

use crate::link::{self, SkewClock};
use crate::location::{batch_body, Fix, Rules, Track};
use railspotting_common::{load_cached_config, plan_channel, save_cached_config, Clock};
use railspotting_proto::api::{ApiClient, ApiError, Backoff};
use crate::radio::{self, BurstWatch, Mode, Radio};
use railspotting_scanner::monitor::Hooks;
use railspotting_scanner::util::{round_even, truthy};
use railspotting_scanner::{CommandHost, CommandRunner, ScannerError, ScannerMonitor};
use railspotting_store::outbox::{Outbox, DEFAULT_CAP};
use railspotting_store::Signal;
use serde_json::{json, Map, Value};
use std::collections::{HashSet, VecDeque};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex, MutexGuard, RwLock, Weak};
use std::thread::JoinHandle;

pub const DEFAULT_HEARTBEAT_S: f64 = 120.0;
const TICK_S: f64 = 1.0;
const RECENT_EVENTS: usize = 50;
/// The headend refuses a fix older than an hour, also on an event (contract v1.7 section 2).
const EVENT_FIX_SEND_MAX_AGE_S: f64 = 3600.0 - 120.0;

/// A named thread body.
type Loop = (&'static str, fn(&Agent));

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

pub struct Options {
    pub server_url: String,
    /// App-private folder for the outbox and the cached config.
    pub state_dir: PathBuf,
    pub seed: Vec<u8>,
    pub device_id: String,
    pub app_version: String,
    pub share: bool,
    /// Opens the dongle: librtlsdr in the app, a simulated dongle in tests.
    pub radio: radio::Opener,
    pub mode: Mode,
    /// Tuner gain in dB; None is automatic.
    pub gain: Option<f64>,
    /// The corrected clock, shared with linking; a new one when None.
    pub skew: Option<Arc<SkewClock>>,
}

/// What the app tells the core about the phone.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct Phone {
    pub share: bool,
    pub foreground: Option<bool>,
    pub battery_pct: Option<i64>,
    pub charging: Option<bool>,
}

impl Phone {
    pub fn from_json(v: &Value, share_default: bool) -> Phone {
        Phone {
            share: v.get("share").and_then(Value::as_bool).unwrap_or(share_default),
            foreground: v.get("foreground").and_then(Value::as_bool),
            battery_pct: v.get("battery_pct").and_then(Value::as_i64).filter(|p| (0..=100).contains(p)),
            charging: v.get("charging").and_then(Value::as_bool),
        }
    }
}

#[derive(Default)]
struct Net {
    online: bool,
    last_ok: Option<f64>,
    last_error: Option<String>,
    /// Set when the headend said this device is gone: `revoked` (another phone took the
    /// receiver over) or `unknown_device`.
    signed_out: Option<String>,
    uploaded: u64,
}

pub struct Agent {
    me: Weak<Agent>,
    clock: Clock,
    skew: Arc<SkewClock>,
    started: f64,
    state_dir: PathBuf,
    app_version: String,
    api: ApiClient,
    outbox: Outbox,
    config: RwLock<Value>,
    etag: Mutex<Option<String>>,
    /// A phone has no scanner: the monitor is never started, so the heartbeat reports none
    /// and the headend's scanner commands answer "no scanner connected", as on a fixed
    /// receiver without one.
    monitor: Arc<ScannerMonitor>,
    radio: Radio,
    phone: Mutex<Phone>,
    track: Mutex<Track>,
    listening: Mutex<bool>,
    net: Mutex<Net>,
    recent: Mutex<VecDeque<Value>>,
    bursts: Mutex<BurstWatch>,
    last_event_ts: Mutex<Value>,
    batch_limit: Mutex<Option<i64>>,
    commands: Mutex<VecDeque<Value>>,
    command_wake: Signal,
    heartbeat_wake: Signal,
    /// Set while stopping: the phone no longer counts as listening.
    stopping: std::sync::atomic::AtomicBool,
    stop: Signal,
    threads: Mutex<Vec<JoinHandle<()>>>,
}

impl Agent {
    pub fn new(opts: Options) -> Result<Arc<Agent>, String> {
        let skew = opts.skew.clone().unwrap_or_default();
        let clock = skew.clock();
        std::fs::create_dir_all(&opts.state_dir).map_err(|e| format!("state folder: {e}"))?;
        let key = link::key_from_seed(&opts.seed)?;
        let api = link::client(&opts.server_url, &key, Some(opts.device_id.clone()), clock.clone())
            .map_err(|e| e.to_string())?;
        let outbox = Outbox::with(opts.state_dir.join("outbox.db"), DEFAULT_CAP, clock.clone())
            .map_err(|e| format!("outbox: {e:?}"))?;
        let mut config = load_cached_config(&opts.state_dir);
        let etag = config.as_object_mut().and_then(|o| o.shift_remove("_etag")).and_then(|v| v.as_str().map(String::from));
        let started = clock();
        Ok(Arc::new_cyclic(|me: &Weak<Agent>| {
            let m1 = me.clone();
            let emit: railspotting_dsp::Emit = Arc::new(move |ev: Map<String, Value>| {
                if let Some(a) = m1.upgrade() {
                    a.emit(ev);
                }
            });
            let monitor = Arc::new(ScannerMonitor::new("none", 57_600, Hooks { clock: clock.clone(), ..Hooks::default() }));
            let radio = Radio::new(opts.radio.clone(), emit, opts.state_dir.clone(), clock.clone(), opts.mode, opts.gain);
            Agent {
                me: me.clone(),
                clock: clock.clone(),
                skew,
                started,
                state_dir: opts.state_dir.clone(),
                app_version: opts.app_version.clone(),
                api,
                outbox,
                config: RwLock::new(config),
                etag: Mutex::new(etag),
                monitor,
                radio,
                phone: Mutex::new(Phone { share: opts.share, ..Phone::default() }),
                track: Mutex::new(Track::new(started)),
                listening: Mutex::new(false),
                net: Mutex::new(Net::default()),
                recent: Mutex::new(VecDeque::new()),
                bursts: Mutex::new(BurstWatch::default()),
                last_event_ts: Mutex::new(Value::Null),
                batch_limit: Mutex::new(None),
                commands: Mutex::new(VecDeque::new()),
                command_wake: Signal::default(),
                heartbeat_wake: Signal::default(),
                stopping: std::sync::atomic::AtomicBool::new(false),
                stop: Signal::default(),
                threads: Mutex::new(vec![]),
            }
        }))
    }

    pub fn now(&self) -> f64 {
        (self.clock)()
    }

    pub fn config(&self) -> Value {
        self.config.read().unwrap_or_else(|p| p.into_inner()).clone()
    }

    pub fn radio(&self) -> &Radio {
        &self.radio
    }

    /// The dongle was plugged in and opened by the app.
    pub fn radio_attached(&self, fd: i64) {
        self.radio.attach(fd, &self.config());
    }

    pub fn set_gain(&self, gain: Option<f64>) {
        self.radio.set_gain(gain, &self.config());
    }

    pub fn set_mode(&self, mode: Mode) {
        self.radio.set_mode(mode, &self.config());
        // the headend should hear about the new kit at once
        self.heartbeat_wake.set();
    }

    pub fn outbox(&self) -> &Outbox {
        &self.outbox
    }

    pub fn api(&self) -> &ApiClient {
        &self.api
    }

    // lifecycle --------------------------------------------------------------------------------

    pub fn start(&self) {
        let Some(me) = self.me.upgrade() else { return };
        log::info!("mobile receiver {} starting (app {})", self.api.device_id().unwrap_or_default(), self.app_version);
        let mut threads = lock(&self.threads);
        let loops: [Loop; 4] = [
            ("heartbeat", Agent::heartbeat_loop),
            ("uploader", Agent::uploader_loop),
            ("location", Agent::location_loop),
            ("commands", Agent::command_loop),
        ];
        for (name, f) in loops {
            let a = me.clone();
            match std::thread::Builder::new().name(name.into()).spawn(move || f(&a)) {
                Ok(h) => threads.push(h),
                Err(e) => log::error!("could not start the {name} thread: {e}"),
            }
        }
    }

    /// Stop every loop and let go of the dongle. Queued events get a few seconds to upload
    /// (events that arrive after the phone stops listening don't change the map), then one last
    /// heartbeat says the phone is no longer listening, so it stops covering crossings at once
    /// rather than when it goes offline. What is still queued stays for the next start.
    pub fn stop(&self) {
        if self.stop.is_set() && lock(&self.threads).is_empty() {
            return;
        }
        if self.signed_out().is_none() && !self.stop.is_set() {
            let deadline = self.now() + 3.0;
            while self.now() < deadline && self.outbox.depth().unwrap_or(0) > 0 && self.net_online() {
                if !matches!(self.upload_once(), Ok(true)) {
                    break;
                }
            }
            self.stopping.store(true, std::sync::atomic::Ordering::SeqCst);
            if self.net_online() {
                if let Err(e) = self.api.heartbeat(&self.heartbeat_body()) {
                    log::info!("last heartbeat not sent: {e}");
                }
            }
        }
        self.stopping.store(true, std::sync::atomic::Ordering::SeqCst);
        self.stop.set();
        self.command_wake.set();
        self.heartbeat_wake.set();
        self.outbox.wakeup.set();
        self.radio.stop();
        let threads: Vec<JoinHandle<()>> = lock(&self.threads).drain(..).collect();
        for t in threads {
            let _ = t.join();
        }
        log::info!("mobile receiver stopped");
    }

    pub fn stopped(&self) -> bool {
        self.stop.is_set()
    }

    // what the app sets ------------------------------------------------------------------------

    pub fn set_phone(&self, p: Phone) {
        let share_changed = {
            let mut cur = lock(&self.phone);
            let changed = cur.share != p.share;
            *cur = p;
            changed
        };
        if share_changed {
            self.heartbeat_wake.set();
        }
    }

    /// A fix from the fused location provider.
    pub fn add_fix(&self, fix: Fix) {
        let rules = Rules::from_config(&self.config());
        let listening = self.listening();
        lock(&self.track).offer(fix, rules, listening, self.now());
    }

    /// The dongle is connected and streaming.
    pub fn listening(&self) -> bool {
        !self.stopping.load(std::sync::atomic::Ordering::SeqCst) && !self.stop.is_set() && self.radio.connected()
    }

    pub fn signed_out(&self) -> Option<String> {
        lock(&self.net).signed_out.clone()
    }

    fn net_online(&self) -> bool {
        lock(&self.net).online
    }

    // events -----------------------------------------------------------------------------------

    /// An event from the dongle, as the fixed receiver's agent takes it, plus where it was heard.
    pub fn emit(&self, mut event: Map<String, Value>) {
        // SDR events carry no label: the plan has it, as the fixed receiver looks it up
        if !event.get("_label").is_some_and(|l| truthy(Some(l))) {
            let cfg = self.config();
            let label = event.get("freq").and_then(Value::as_f64).and_then(|f| plan_channel(&cfg, f))
                .and_then(|c| c.get("label")).cloned().unwrap_or(Value::Null);
            event.insert("_label".into(), label);
        }
        // interference across the band: kept out of live listen and the activity list (the
        // events still go to the headend as heard)
        let id = event.get("event_id").and_then(Value::as_str).unwrap_or("").to_string();
        let voice_open = event.get("kind").and_then(Value::as_str) == Some("voice")
            && event.get("state").and_then(Value::as_str) == Some("open");
        let newly = if voice_open {
            let ts = event.get("start_ts").and_then(Value::as_f64).unwrap_or(0.0);
            let freq = event.get("freq").and_then(Value::as_f64).unwrap_or(0.0);
            lock(&self.bursts).opened(ts, freq, &id)
        } else {
            vec![]
        };
        self.radio.live.on_event(&event);
        if !newly.is_empty() {
            self.radio.live.mark_noise(&newly);
            for e in lock(&self.recent).iter_mut() {
                if e.get("event_id").and_then(Value::as_str).is_some_and(|i| newly.iter().any(|n| n == i)) {
                    e["burst"] = json!(true);
                }
            }
        }
        let burst = lock(&self.bursts).is_burst(&id);
        let mut clean: Map<String, Value> = event.iter().filter(|(k, _)| !k.starts_with('_'))
            .map(|(k, v)| (k.clone(), v.clone())).collect();
        if let Some(fix) = lock(&self.track).fix_for_event(self.now()) {
            clean.insert("location".into(), fix.to_json());
        }
        // the headend refuses telemetry with more corrected bits than it takes: send the burst
        // without it (it still shows and alerts here)
        let mut upload = clean.clone();
        if railspotting_dsp::eot::fit_for_headend(&mut upload, railspotting_dsp::eot::corrected_max(&self.config())) {
            log::info!("EOT packet with 2 corrected bits kept on this phone; the headend does not take them yet");
        }
        if let Err(e) = self.outbox.add_event(&Value::Object(upload)) {
            log::error!("outbox write failed: {e:?}");
        }
        {
            let mut shown = clean.clone();
            shown.remove("location");
            shown.insert("label".into(), event.get("_label").cloned().unwrap_or(Value::Null));
            if burst {
                shown.insert("burst".into(), json!(true));
            }
            if let Some(Value::Object(tel)) = clean.get("telemetry") {
                shown.insert("decoded".into(), json!(railspotting_dsp::eot::describe(tel)));
            }
            let mut r = lock(&self.recent);
            // the closed version replaces the open one
            r.retain(|e| e.get("event_id") != shown.get("event_id"));
            r.push_back(Value::Object(shown));
            while r.len() > RECENT_EVENTS {
                r.pop_front();
            }
        }
        let ts = clean.get("end_ts").filter(|v| truthy(Some(v))).or_else(|| clean.get("start_ts")).cloned()
            .unwrap_or(Value::Null);
        *lock(&self.last_event_ts) = ts;
    }

    pub fn report(&self, rtype: &str, data: Value) {
        let rep = json!({"type": rtype, "ts": round_even(self.now(), 1), "data": data});
        if let Err(e) = self.outbox.add_report(&rep) {
            log::error!("outbox write failed: {e:?}");
        }
    }

    // headend answers --------------------------------------------------------------------------

    fn ok(&self) {
        let mut n = lock(&self.net);
        n.online = true;
        n.last_ok = Some(self.now());
        n.last_error = None;
    }

    pub fn handle_api_error(&self, e: &ApiError) {
        self.skew.absorb(&self.api);
        let gone = (e.status == Some(403) && e.error == "revoked") || (e.status == Some(401) && e.error == "unknown_device");
        {
            let mut n = lock(&self.net);
            if e.network() {
                n.online = false;
            }
            n.last_error = Some(e.to_string());
            if gone && n.signed_out.is_none() {
                n.signed_out = Some(e.error.clone());
            }
        }
        if gone {
            log::warn!("the headend no longer knows this phone ({}); stopping. Sign in again to take the receiver back.",
                       e.error);
            self.stop.set();
            self.command_wake.set();
            self.heartbeat_wake.set();
            self.outbox.wakeup.set();
        }
    }

    // config -----------------------------------------------------------------------------------

    pub fn apply_config(&self, cfg: Value, etag: Option<String>) {
        let old = self.config().get("config_version").cloned();
        *self.config.write().unwrap_or_else(|p| p.into_inner()) = cfg.clone();
        *lock(&self.etag) = etag.clone();
        if let Err(e) = save_cached_config(&self.state_dir, &cfg, etag.as_deref()) {
            log::warn!("could not save the channel plan: {e}");
        }
        self.radio.apply_plan(&cfg);
        if old.as_ref() != cfg.get("config_version") {
            let n = cfg.get("channels").and_then(Value::as_array).map_or(0, Vec::len);
            log::info!("channel plan version {} applied ({n} channels)",
                       cfg.get("config_version").map(|v| v.to_string()).unwrap_or_default());
        }
    }

    pub fn fetch_config(&self) -> Result<(), ApiError> {
        let etag = lock(&self.etag).clone();
        let (cfg, etag) = self.api.get_config(etag.as_deref())?;
        if let Some(cfg) = cfg {
            self.apply_config(cfg, etag);
        }
        Ok(())
    }

    // heartbeat --------------------------------------------------------------------------------

    /// As a fixed receiver's: the scanner (none on a phone) and the dongle, when attached.
    pub fn capabilities(&self) -> Value {
        let mut receivers = vec![json!({
            "type": "scanner", "vendor": null, "product": null, "firmware": null, "connected": false,
            "role": null, "bands_mhz": null, "channels": null,
            "simultaneous": false, "squelch_free": false, "audio": false,
        })];
        if let Some(c) = self.radio.capability() {
            receivers.push(Value::Object(c));
        }
        let eot = self.radio.covers("telemetry", Some("EOT"));
        json!({
            "receivers": receivers,
            "features": {
                "simultaneous_voice": self.radio.covers("voice", None), "continuous_eot": eot,
                "continuous_hot": false, "transcription": false,
                "eot_decode": eot && self.radio.mode() == Mode::Eot, "detector_reports": false,
            },
        })
    }

    pub fn mobile_state(&self) -> Value {
        let p = lock(&self.phone).clone();
        let mut m = Map::new();
        m.insert("listening".into(), json!(self.listening()));
        m.insert("share".into(), json!(p.share));
        if let Some(f) = p.foreground {
            m.insert("foreground".into(), json!(f));
        }
        if let Some(b) = p.battery_pct {
            m.insert("battery_pct".into(), json!(b));
        }
        if let Some(c) = p.charging {
            m.insert("charging".into(), json!(c));
        }
        Value::Object(m)
    }

    pub fn heartbeat_body(&self) -> Value {
        let now = self.now();
        json!({
            "ts": round_even(now, 1), "uptime_s": (now - self.started).max(0.0) as i64,
            "agent_version": format!("android-{}", self.app_version), "scanner": self.monitor.heartbeat_info(),
            "outbox_depth": self.outbox.depth().unwrap_or(0), "dropped_events": self.outbox.dropped().unwrap_or(0),
            "last_event_ts": lock(&self.last_event_ts).clone(),
            "clock_synced": self.api.clock_synced().unwrap_or(true),
            "capabilities": self.capabilities(),
            "mobile": self.mobile_state(),
        })
    }

    pub fn heartbeat_once(&self) -> Result<Value, ApiError> {
        let body = self.heartbeat_body();
        let resp = self.api.heartbeat(&body)?;
        self.ok();
        self.skew.absorb(&self.api);
        let _ = self.outbox.ack_dropped(body["dropped_events"].as_i64().unwrap_or(0));
        if let Some(v) = resp.get("config_version").filter(|v| truthy(Some(v))) {
            if Some(v) != self.config().get("config_version") {
                self.fetch_config()?;
            }
        }
        if resp.get("commands_pending").and_then(Value::as_f64).unwrap_or(0.0) > 0.0 {
            let cmds = self.api.get_commands()?;
            lock(&self.commands).extend(cmds);
            self.command_wake.set();
        }
        Ok(resp)
    }

    fn heartbeat_every(&self) -> f64 {
        self.config().get("mobile").and_then(|m| m.get("heartbeat_interval_s")).and_then(Value::as_f64)
            .filter(|s| *s >= 10.0).unwrap_or(DEFAULT_HEARTBEAT_S)
    }

    fn heartbeat_loop(&self) {
        if let Err(e) = self.fetch_config() {
            self.handle_api_error(&e);
            log::warn!("config fetch failed: {e}");
        }
        let mut backoff = Backoff::default();
        while !self.stop.is_set() {
            self.heartbeat_wake.clear();
            let wait = match self.heartbeat_once() {
                Ok(_) => {
                    backoff.reset();
                    self.heartbeat_every()
                }
                Err(e) => {
                    self.handle_api_error(&e);
                    log::warn!("heartbeat failed: {e}");
                    backoff.next_delay(e.retry_after).min(self.heartbeat_every())
                }
            };
            self.heartbeat_wake.wait(wait);
        }
    }

    // uploads ----------------------------------------------------------------------------------

    /// One batch of events and up to ten reports, as the fixed receiver uploads them.
    pub fn upload_once(&self) -> Result<bool, ApiError> {
        let cfg = self.config();
        let limit = lock(&self.batch_limit).unwrap_or_else(|| {
            cfg.get("upload").and_then(|u| u.get("max_batch")).and_then(Value::as_i64).unwrap_or(200)
        });
        let batch = self.outbox.next_events(limit).unwrap_or_else(|e| {
            log::error!("outbox read failed: {e:?}");
            vec![]
        });
        if !batch.is_empty() {
            let now = self.now();
            let events: Vec<Value> = batch.iter().map(|(_, e)| without_stale_fix(e, now)).collect();
            let resp = match self.api.post_events(&events) {
                Ok(r) => r,
                Err(e) if e.status == Some(400) || e.status == Some(413) => {
                    if batch.len() == 1 {
                        log::warn!("headend refused event {} ({}); dropped",
                                   batch[0].1.get("event_id").and_then(Value::as_str).unwrap_or("?"), e.error);
                        let _ = self.outbox.delete(batch[0].0);
                        return Ok(true);
                    }
                    *lock(&self.batch_limit) = Some(((batch.len() / 2) as i64).max(1));
                    return Ok(true);
                }
                Err(e) => return Err(e),
            };
            self.ok();
            *lock(&self.batch_limit) = None;
            let ids = |k: &str| -> Vec<String> {
                let mut seen = HashSet::new();
                resp.get(k).and_then(Value::as_array).into_iter().flatten().filter_map(Value::as_str)
                    .filter(|s| seen.insert(s.to_string())).map(String::from).collect()
            };
            let (accepted, duplicates) = (ids("accepted"), ids("duplicates"));
            let acked: Vec<String> = accepted.iter().chain(&duplicates).cloned().collect();
            if let Err(e) = self.outbox.ack_events(&batch, &acked) {
                log::error!("outbox ack failed: {e:?}");
            }
            lock(&self.net).uploaded += accepted.len() as u64;
        }
        let reports = self.outbox.next_reports(10).unwrap_or_default();
        for (rid, rep) in &reports {
            if let Err(e) = self.api.post_report(rep) {
                if e.status == Some(400) || e.status == Some(413) {
                    log::warn!("headend refused a {} report ({}); dropped",
                               rep.get("type").and_then(Value::as_str).unwrap_or("?"), e.error);
                } else {
                    return Err(e);
                }
            }
            let _ = self.outbox.delete(*rid);
        }
        Ok(!batch.is_empty() || !reports.is_empty())
    }

    fn uploader_loop(&self) {
        let mut backoff = Backoff::default();
        while !self.stop.is_set() {
            match self.upload_once() {
                Ok(sent) => {
                    backoff.reset();
                    if !sent {
                        let flush = self.config().get("upload").and_then(|u| u.get("flush_interval_s"))
                            .and_then(Value::as_f64).unwrap_or(2.0);
                        self.outbox.wakeup.wait(flush);
                        self.outbox.wakeup.clear();
                    }
                }
                Err(e) => {
                    self.handle_api_error(&e);
                    let d = backoff.next_delay(e.retry_after);
                    log::warn!("upload failed: {e}; retry in {d:.0} s ({} queued)", self.outbox.depth().unwrap_or(0));
                    self.stop.wait(d);
                }
            }
        }
    }

    // location ---------------------------------------------------------------------------------

    /// Track listening changes, and send fixes when a batch is due. True when a call was made.
    pub fn location_tick(&self) -> bool {
        let listening = self.listening();
        let changed = {
            let mut l = lock(&self.listening);
            let changed = *l != listening;
            *l = listening;
            changed
        };
        if changed {
            log::info!("{}", if listening { "listening: the dongle is streaming" } else { "not listening" });
            if !listening {
                lock(&self.track).stop_listening();
            }
            // the headend should know at once whether this phone covers crossings
            self.heartbeat_wake.set();
        }
        if !listening {
            return false;
        }
        let now = self.now();
        let Some(batch) = lock(&self.track).take_batch(now) else { return false };
        match self.api.request("POST", "/location", Some(&batch_body(&batch)), true, &[], "", true)
            .and_then(|r| r.json()) {
            Ok(r) => {
                self.ok();
                lock(&self.track).sent_ok(&batch);
                let used = r.get("used").and_then(Value::as_i64).unwrap_or(0);
                log::debug!("sent {} fixes ({used} used)", batch.len());
                // the headend moved the phone, or its plan changed: the channels and nearby
                // detectors for here now, not at the next heartbeat
                if self.config_changed(&r, used) {
                    self.replanned();
                }
            }
            Err(e) if e.status == Some(400) => lock(&self.track).refused(&batch),
            Err(e) => {
                self.handle_api_error(&e);
                log::warn!("location failed: {e}");
                lock(&self.track).failed(batch, now, e.retry_after);
            }
        }
        true
    }

    /// A /location answer says the config changed: its `config_version` (v1.9, on every answer)
    /// differs from ours, or, from a v1.7 headend, it moved the phone.
    fn config_changed(&self, answer: &Value, used: i64) -> bool {
        match answer.get("config_version").filter(|v| !v.is_null()) {
            Some(v) => self.config().get("config_version") != Some(v),
            None => used > 0,
        }
    }

    /// The headend placed the phone somewhere new: take the plan for there.
    fn replanned(&self) {
        let before = self.config().get("config_version").cloned();
        if let Err(e) = self.fetch_config() {
            self.handle_api_error(&e);
            log::warn!("config fetch after a move failed: {e}");
            return;
        }
        if self.config().get("config_version") != before.as_ref() {
            log::info!("moved: {}", plan_text(&self.plan_info()));
        }
    }

    /// The headend re-plans the phone by its position on request, also while not listening
    /// (`/config` `mobile.plan_by_location`, proposed for contract v1.9).
    pub fn plan_by_location(&self) -> bool {
        self.config().get("mobile").and_then(|m| m.get("plan_by_location")).and_then(Value::as_bool) == Some(true)
    }

    /// The channel plan in use, for the app: the headend's `plan` block when it sends one.
    pub fn plan_info(&self) -> Value {
        let cfg = self.config();
        let channels = cfg.get("channels").and_then(Value::as_array).map_or(0, Vec::len);
        let mut info = cfg.get("plan").filter(|p| p.is_object()).cloned().unwrap_or_else(|| json!({}));
        info["channels"] = json!(channels);
        info["config_version"] = cfg.get("config_version").cloned().unwrap_or(Value::Null);
        info["by_location"] = json!(self.plan_by_location());
        info
    }

    /// The owner asked for the channel list for where the phone is. The fix goes to the headend
    /// when the contract allows it (while listening, or with plan_by_location), then the plan
    /// is fetched afresh.
    pub fn refresh_plan(&self, fix: Option<Fix>) -> Value {
        let mut sent = false;
        let mut moved = false;
        let mut note = Value::Null;
        if let Some(f) = fix {
            let by_location = self.plan_by_location();
            if self.listening() || by_location {
                let mut body = batch_body(std::slice::from_ref(&f));
                if by_location {
                    body["refresh"] = json!(true);
                }
                match self.api.request("POST", "/location", Some(&body), true, &[], "", true).and_then(|r| r.json()) {
                    Ok(r) => {
                        self.ok();
                        sent = true;
                        moved = r.get("used").and_then(Value::as_i64).unwrap_or(0) > 0;
                    }
                    Err(e) => {
                        self.handle_api_error(&e);
                        log::warn!("refresh: location not sent: {e}");
                        // v1.9 takes one refresh per 30 s
                        if e.status == Some(429) {
                            note = json!(format!("Refreshed less than {:.0} s ago: showing the list as it is",
                                                 e.retry_after.unwrap_or(30.0)));
                        }
                    }
                }
            }
            let rules = Rules::from_config(&self.config());
            lock(&self.track).offer(f, rules, false, self.now());
        }
        // the plan as it is now, not a cached one
        *lock(&self.etag) = None;
        if let Err(e) = self.fetch_config() {
            self.handle_api_error(&e);
            return json!({"ok": false, "error": e.error, "message": e.message});
        }
        let plan = self.plan_info();
        log::info!("channel list refreshed: {}", plan_text(&plan));
        json!({"ok": true, "sent_fix": sent, "moved": moved, "plan": plan, "note": note})
    }

    fn location_loop(&self) {
        while !self.stop.is_set() {
            self.location_tick();
            self.stop.wait(TICK_S);
        }
    }

    // commands ---------------------------------------------------------------------------------

    fn command_loop(&self) {
        while !self.stop.is_set() {
            let next = lock(&self.commands).pop_front();
            let Some(cmd) = next else {
                self.command_wake.wait(TICK_S);
                self.command_wake.clear();
                continue;
            };
            let result = CommandRunner::run(self, &cmd);
            let id = match cmd.get("id") {
                Some(Value::String(s)) => s.clone(),
                Some(v) => v.to_string(),
                None => continue,
            };
            for attempt in 0..5 {
                match self.api.post_command_result(&id, &result) {
                    Ok(_) => break,
                    Err(e) => {
                        self.handle_api_error(&e);
                        log::warn!("could not send result of {id}: {e}");
                        if self.stop.wait(5.0 * (attempt + 1) as f64) {
                            break;
                        }
                    }
                }
            }
        }
    }

    // status for the app -----------------------------------------------------------------------

    pub fn status(&self) -> Value {
        // The radio first, with no lock of ours held: its thread holds the dongle's lock while it
        // hands an event to `emit`, which takes `track`. Holding `track` here while asking the
        // radio deadlocked the two (the app froze on a black screen).
        let radio = self.radio.status();
        let listening = self.listening();
        let plan = self.plan_info();
        let now = self.now();
        let (online, last_ok_age, last_error, signed_out, uploaded) = {
            let net = lock(&self.net);
            (net.online && net.last_ok.is_some_and(|t| now - t <= self.heartbeat_every() * 2.0 + 30.0),
             net.last_ok.map(|t| (now - t).max(0.0).round()), net.last_error.clone(), net.signed_out.clone(),
             net.uploaded)
        };
        let (fix_age, sent, pending) = {
            let track = lock(&self.track);
            (track.latest().map(|f| (now - f.ts).max(0.0).round()), track.sent, track.pending())
        };
        let share = lock(&self.phone).share;
        let recent: Vec<Value> = lock(&self.recent).iter().rev().cloned().collect();
        json!({
            "running": !self.stop.is_set(),
            "device_id": self.api.device_id(),
            "online": online,
            "last_ok_age_s": last_ok_age,
            "last_error": last_error,
            "signed_out": signed_out,
            "listening": listening,
            "radio": radio,
            "plan": plan,
            "share": share,
            "fix_age_s": fix_age,
            "fixes_sent": sent,
            "fixes_pending": pending,
            "outbox_depth": self.outbox.depth().unwrap_or(0),
            "events_uploaded": uploaded,
            "recent_events": recent,
            "clock_correction_s": self.skew.correction().round(),
        })
    }
}

/// "NS Greenville, 9 channels" for the log.
fn plan_text(plan: &Value) -> String {
    let n = plan.get("channels").and_then(Value::as_u64).unwrap_or(0);
    match plan.get("name").and_then(Value::as_str) {
        Some(name) => format!("{name}, {n} channels"),
        None => format!("{n} channels"),
    }
}

/// An event as it goes out: its fix is left off when the headend would refuse it for age
/// (contract v1.7 section 2; an event backlog from a long time offline).
fn without_stale_fix(ev: &Value, now: f64) -> Value {
    let stale = ev.get("location").and_then(|l| l.get("ts")).and_then(Value::as_f64)
        .is_some_and(|ts| now - ts > EVENT_FIX_SEND_MAX_AGE_S);
    if !stale {
        return ev.clone();
    }
    let mut e = ev.clone();
    if let Some(o) = e.as_object_mut() {
        o.remove("location");
    }
    e
}

impl CommandHost for Agent {
    fn monitor(&self) -> &ScannerMonitor {
        &self.monitor
    }

    fn state_dir(&self) -> PathBuf {
        self.state_dir.clone()
    }

    fn config(&self) -> Value {
        Agent::config(self)
    }

    /// A phone moves, so its reading of a fixed reference station says nothing about its
    /// antenna: the check is not offered.
    fn radio_check(&self, _send: bool) -> Result<Value, ScannerError> {
        Ok(json!({"ok": false, "error": "a mobile receiver has no radio check"}))
    }

    fn report(&self, rtype: &str, data: Value) {
        Agent::report(self, rtype, data)
    }
}

/// Remove everything a signed-out phone kept: the outbox (its events belong to the old device),
/// the cached plan and the scanner state.
pub fn forget(state_dir: &Path) {
    if state_dir.exists() {
        if let Err(e) = std::fs::remove_dir_all(state_dir) {
            log::warn!("could not clear the receiver's folder: {e}");
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn stale_fixes_are_left_off_events() {
        let now = 1_791_034_500.0;
        let ev = json!({"event_id": "x", "location": {"lat": 1.0, "lon": 2.0, "accuracy_m": 5.0, "ts": now - 3500.0}});
        assert!(without_stale_fix(&ev, now).get("location").is_none());
        let ev = json!({"event_id": "x", "location": {"lat": 1.0, "lon": 2.0, "accuracy_m": 5.0, "ts": now - 600.0}});
        assert!(without_stale_fix(&ev, now).get("location").is_some());
        assert_eq!(without_stale_fix(&json!({"event_id": "y"}), now), json!({"event_id": "y"}));
    }

    #[test]
    fn phone_state_from_the_app() {
        let p = Phone::from_json(&json!({"share": false, "battery_pct": 64, "charging": true, "foreground": false}), true);
        assert_eq!(p, Phone { share: false, foreground: Some(false), battery_pct: Some(64), charging: Some(true) });
        let p = Phone::from_json(&json!({"battery_pct": 140}), true);
        assert_eq!(p, Phone { share: true, ..Phone::default() });
    }
}

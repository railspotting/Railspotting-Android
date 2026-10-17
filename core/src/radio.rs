//! The phone's RTL-SDR dongle: one at most, in one of two modes the owner picks.
//!
//! - **Voice**: the VHF road channels (the fixed receiver's `vhf` role), with live listen.
//! - **EOT**: the end-of-train channel, with packets decoded on the phone (the `eot` role).
//!
//! The processing is the fixed receiver's own (`SdrHandle` and `SdrSource` from the receiver
//! crates): the same windows, detectors, events and EOT decoder. Android gives the app a USB file
//! descriptor for the dongle the owner allowed; the app's patched librtlsdr opens it.

use railspotting_common::Clock;
use railspotting_dsp::voice::AudioTap;
use railspotting_dsp::{Complex32, Emit, SdrSource};
use railspotting_engine::device::{Device, DeviceFactory, SdrHandle};
use serde_json::{json, Map, Value};
use std::collections::{HashSet, VecDeque};
use std::path::PathBuf;
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::time::Duration;

/// Opens the dongle behind a USB file descriptor: librtlsdr in the app, a simulated dongle in
/// tests.
pub type Opener = Arc<dyn Fn(i64) -> Result<Arc<dyn Device>, String> + Send + Sync>;

/// The real dongle through the app's librtlsdr.
pub fn rtl_opener() -> Opener {
    Arc::new(|fd| railspotting_engine::rtl::RtlDevice::open_sys_dev(fd, "usb").map(|d| Arc::new(d) as Arc<dyn Device>))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Mode {
    Voice,
    Eot,
}

impl Mode {
    pub fn parse(s: &str) -> Option<Mode> {
        match s {
            "voice" => Some(Mode::Voice),
            "eot" => Some(Mode::Eot),
            _ => None,
        }
    }

    pub fn name(self) -> &'static str {
        match self {
            Mode::Voice => "voice",
            Mode::Eot => "eot",
        }
    }

    /// The fixed receiver's role for this mode.
    pub fn role(self) -> &'static str {
        match self {
            Mode::Voice => "vhf",
            Mode::Eot => "eot",
        }
    }
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

// interference -------------------------------------------------------------------------------

/// Channels opening within this long of each other count as opened together (the receiver's
/// `SIMULTANEOUS_S`).
pub const TOGETHER_S: f64 = railspotting_dsp::sdr::SIMULTANEOUS_S;
/// This many road channels opening together is interference across the dongle's band, not
/// calls: a call is on one channel. Seen on a phone (October 2026): all nine channels opening
/// within 50 ms, a few dB above the noise.
pub const BURST_CHANNELS: usize = 3;

/// Spots voice events that opened together with others on many channels. The events still go
/// to the headend as heard (reporting is the receiver's); the app only keeps them out of live
/// listen, the activity list and alerts.
#[derive(Default)]
pub struct BurstWatch {
    opens: VecDeque<(f64, f64, String)>,
    burst: HashSet<String>,
}

impl BurstWatch {
    /// A voice event opened. Returns the events that turn out to be part of a burst with it
    /// (including ones flagged before, so the caller can update what it shows).
    pub fn opened(&mut self, ts: f64, freq: f64, id: &str) -> Vec<String> {
        self.opens.retain(|(t, _, _)| ts - t <= 60.0);
        self.opens.push_back((ts, freq, id.to_string()));
        let together: Vec<&(f64, f64, String)> = self.opens.iter().filter(|(t, _, _)| (t - ts).abs() <= TOGETHER_S).collect();
        let mut freqs: Vec<f64> = together.iter().map(|(_, f, _)| *f).collect();
        freqs.sort_by(f64::total_cmp);
        freqs.dedup_by(|a, b| (*a - *b).abs() < 0.0005);
        if freqs.len() < BURST_CHANNELS {
            return vec![];
        }
        let ids: Vec<String> = together.iter().map(|(_, _, i)| i.clone()).collect();
        self.burst.extend(ids.iter().cloned());
        // remember a few hundred at most
        if self.burst.len() > 500 {
            let keep: HashSet<String> = self.opens.iter().map(|(_, _, i)| i.clone()).filter(|i| self.burst.contains(i)).collect();
            self.burst = keep;
        }
        ids
    }

    pub fn is_burst(&self, id: &str) -> bool {
        self.burst.contains(id)
    }
}

// live listen ----------------------------------------------------------------------------------

/// Audio kept waiting for the app to play it; older audio is dropped (the player fell behind).
const MAX_QUEUED_S: f64 = 2.0;

#[derive(Debug, Clone)]
struct Call {
    event_id: String,
    freq: f64,
    label: Value,
    since: f64,
}

#[derive(Default)]
struct LiveState {
    enabled: bool,
    rate: f64,
    center_hz: f64,
    /// Voice calls on the air now, oldest first.
    open: Vec<Call>,
    /// The call being played, and its audio tap.
    playing: Option<(Call, AudioTap)>,
    /// Calls that are interference (opened together with many channels): never played.
    noise: HashSet<String>,
    out: VecDeque<f32>,
    /// The audio rate of what is in `out` (16 kHz at the usual 2.4 MS/s).
    out_rate: f64,
}

/// Live listen: plays the voice call on the air, following traffic on its own. A call plays to
/// its end; then the newest call still on the air takes over. Between calls there is silence
/// (only open events are played, so the detector is the squelch).
#[derive(Default)]
pub struct Live {
    st: Mutex<LiveState>,
    cv: Condvar,
}

impl Live {
    pub fn set_enabled(&self, on: bool) {
        let mut st = lock(&self.st);
        st.enabled = on;
        st.out.clear();
        Self::choose(&mut st);
    }

    pub fn enabled(&self) -> bool {
        lock(&self.st).enabled
    }

    /// The dongle's rate and centre (from its configure and retune calls).
    fn tuned(&self, rate: Option<f64>, center_hz: f64) {
        let mut st = lock(&self.st);
        if let Some(r) = rate {
            st.rate = r;
        }
        st.center_hz = center_hz;
        // offsets changed: start the tap again on the new centre
        if let Some((call, _)) = st.playing.take() {
            st.playing = Self::tap_for(&st, call);
        }
    }

    fn tap_for(st: &LiveState, call: Call) -> Option<(Call, AudioTap)> {
        if !st.enabled || st.rate <= 0.0 {
            return None;
        }
        let offset = call.freq * 1e6 - st.center_hz;
        // the channel must be inside the dongle's band
        if offset.abs() > st.rate / 2.0 - 15_000.0 {
            return None;
        }
        Some((call, AudioTap::with_max(st.rate, offset, 3600.0)))
    }

    fn choose(st: &mut LiveState) {
        st.open.retain(|c| !st.noise.contains(&c.event_id));
        let keep = st.playing.as_ref().is_some_and(|(c, _)| st.enabled && st.open.iter().any(|o| o.event_id == c.event_id));
        if keep {
            return;
        }
        st.playing = None;
        st.out.clear();
        if let Some(call) = st.open.last().cloned() {
            st.playing = Self::tap_for(st, call);
        }
    }

    /// These calls turned out to be interference: stop playing them, and never start.
    pub fn mark_noise(&self, ids: &[String]) {
        let mut st = lock(&self.st);
        st.noise.extend(ids.iter().cloned());
        if st.noise.len() > 500 {
            let open: HashSet<String> = st.open.iter().map(|c| c.event_id.clone()).collect();
            st.noise.retain(|i| open.contains(i));
        }
        Self::choose(&mut st);
    }

    /// A voice event opened or closed (the scanner of what to play).
    pub fn on_event(&self, ev: &Map<String, Value>) {
        if ev.get("kind").and_then(Value::as_str) != Some("voice") {
            return;
        }
        let Some(id) = ev.get("event_id").and_then(Value::as_str) else { return };
        let mut st = lock(&self.st);
        match ev.get("state").and_then(Value::as_str) {
            Some("open") => {
                if !st.open.iter().any(|c| c.event_id == id) && !st.noise.contains(id) {
                    st.open.push(Call {
                        event_id: id.to_string(),
                        freq: ev.get("freq").and_then(Value::as_f64).unwrap_or(0.0),
                        label: ev.get("_label").cloned().unwrap_or(Value::Null),
                        since: ev.get("start_ts").and_then(Value::as_f64).unwrap_or(0.0),
                    });
                }
            }
            Some("closed") => st.open.retain(|c| c.event_id != id),
            _ => return,
        }
        Self::choose(&mut st);
    }

    /// Samples from the dongle.
    fn feed(&self, block: &[Complex32]) {
        let mut st = lock(&self.st);
        let Some((_, tap)) = st.playing.as_mut() else { return };
        tap.feed(block);
        let audio = tap.drain();
        let rate = tap.rate;
        if rate != st.out_rate {
            st.out.clear();
            st.out_rate = rate;
        }
        st.out.extend(audio);
        let max = (MAX_QUEUED_S * rate) as usize;
        let extra = st.out.len().saturating_sub(max);
        st.out.drain(..extra);
        drop(st);
        self.cv.notify_all();
    }

    /// The rate of the audio `take` returns, in Hz.
    pub fn audio_rate(&self) -> f64 {
        let r = lock(&self.st).out_rate;
        if r > 0.0 { r } else { railspotting_dsp::voice::AUDIO_RATE }
    }

    /// Up to `max` samples of audio (at `audio_rate`), waiting up to `timeout` for some.
    pub fn take(&self, max: usize, timeout: Duration) -> Vec<f32> {
        let st = lock(&self.st);
        let (mut st, _) = self.cv.wait_timeout_while(st, timeout, |s| s.out.is_empty()).unwrap_or_else(|p| p.into_inner());
        let n = st.out.len().min(max);
        st.out.drain(..n).collect()
    }

    /// What is playing now, for the app.
    pub fn now_playing(&self) -> Value {
        let st = lock(&self.st);
        match &st.playing {
            Some((c, _)) => json!({"freq": c.freq, "label": c.label, "since": c.since, "event_id": c.event_id}),
            None => Value::Null,
        }
    }

    /// The dongle is gone or changed mode: nothing is on the air.
    fn reset(&self) {
        let mut st = lock(&self.st);
        st.open.clear();
        st.playing = None;
        st.out.clear();
    }
}

/// The dongle with live listen listening in: it sees the tuning and every block of samples.
struct Tapped {
    inner: Arc<dyn Device>,
    live: Arc<Live>,
}

impl Device for Tapped {
    fn info(&self) -> Map<String, Value> {
        self.inner.info()
    }

    fn configure(&self, rate: f64, center_hz: f64, gain: &Value) -> Result<(), String> {
        self.live.tuned(Some(rate), center_hz);
        self.inner.configure(rate, center_hz, gain)
    }

    fn set_center(&self, center_hz: f64) {
        self.live.tuned(None, center_hz);
        self.inner.set_center(center_hz)
    }

    fn read_samples_async(&self, callback: &mut dyn FnMut(Vec<Complex32>), num_samples: usize) -> Result<(), String> {
        let live = self.live.clone();
        self.inner.read_samples_async(&mut |s| {
            live.feed(&s);
            callback(s)
        }, num_samples)
    }

    fn cancel_read_async(&self) {
        self.inner.cancel_read_async()
    }

    fn close(&self) {
        self.inner.close()
    }
}

// the dongle -----------------------------------------------------------------------------------

struct State {
    mode: Mode,
    /// Tuner gain in dB; None is the tuner's automatic gain.
    gain: Option<f64>,
    fd: Option<i64>,
    handle: Option<Arc<SdrHandle>>,
}

/// The phone's default tuner gain. Fixed receivers use 20.7 dB with roof antennas; a phone's
/// small antenna needs more, or weak EOT bursts arrive with bit errors.
pub const PHONE_GAIN_DB: f64 = 40.2;

/// EOT burst recordings kept in the phone's storage (`bursts/`), newest first.
pub const EOT_BURSTS_KEPT: usize = 25;

pub struct Radio {
    opener: Opener,
    emit: Emit,
    state_dir: PathBuf,
    clock: Clock,
    st: Mutex<State>,
    pub live: Arc<Live>,
}

impl Radio {
    pub fn new(opener: Opener, emit: Emit, state_dir: PathBuf, clock: Clock, mode: Mode, gain: Option<f64>) -> Radio {
        Radio { opener, emit, state_dir, clock, st: Mutex::new(State { mode, gain, fd: None, handle: None }),
                live: Arc::new(Live::default()) }
    }

    pub fn mode(&self) -> Mode {
        lock(&self.st).mode
    }

    pub fn attached(&self) -> bool {
        lock(&self.st).fd.is_some()
    }

    /// The dongle was plugged in and the app opened it.
    pub fn attach(&self, fd: i64, cfg: &Value) {
        let mut st = lock(&self.st);
        st.fd = Some(fd);
        self.rebuild(&mut st, cfg);
    }

    pub fn detach(&self) {
        let mut st = lock(&self.st);
        st.fd = None;
        Self::drop_handle(&mut st);
        self.live.reset();
    }

    /// Switch between voice and EOT. The dongle reopens in the new role.
    pub fn set_mode(&self, mode: Mode, cfg: &Value) {
        let mut st = lock(&self.st);
        if st.mode == mode {
            return;
        }
        log::info!("radio mode: {}", mode.name());
        st.mode = mode;
        self.live.reset();
        if st.fd.is_some() {
            self.rebuild(&mut st, cfg);
        }
    }

    /// A new tuner gain (None: automatic). The dongle reopens with it.
    pub fn set_gain(&self, gain: Option<f64>, cfg: &Value) {
        let mut st = lock(&self.st);
        if st.gain == gain {
            return;
        }
        log::info!("radio gain: {}", gain_name(gain));
        st.gain = gain;
        if st.fd.is_some() {
            self.rebuild(&mut st, cfg);
        }
    }

    /// A new channel plan from the headend.
    pub fn apply_plan(&self, cfg: &Value) {
        if let Some(h) = self.handle() {
            let chans = cfg.get("channels").and_then(Value::as_array).cloned().unwrap_or_default();
            let reference = cfg.get("reference").filter(|r| !r.is_null()).cloned();
            h.with(|s| s.set_plan(&chans, reference.as_ref()));
        }
    }

    pub fn stop(&self) {
        let mut st = lock(&self.st);
        Self::drop_handle(&mut st);
        self.live.reset();
    }

    fn handle(&self) -> Option<Arc<SdrHandle>> {
        lock(&self.st).handle.clone()
    }

    fn drop_handle(st: &mut State) {
        if let Some(h) = st.handle.take() {
            h.stop();
            h.join();
        }
    }

    fn rebuild(&self, st: &mut State, cfg: &Value) {
        Self::drop_handle(st);
        let Some(fd) = st.fd else { return };
        let dev_cfg = json!({"role": st.mode.role(), "serial": "usb", "name": match st.mode {
            Mode::Voice => "Voice SDR",
            Mode::Eot => "EOT SDR",
        }, "gain": st.gain.map_or(json!("auto"), |g| json!(g))});
        // EOT bursts are kept on the phone (the last 25, about 9 MB) so a missed decode can be
        // replayed; voice bursts are not (storage)
        let sdr_settings = json!({"margin_db": 6.0, "min_windows": 2, "record_bursts": st.mode == Mode::Eot,
                                  "record_keep": EOT_BURSTS_KEPT});
        let mut src = SdrSource::new(&dev_cfg, &sdr_settings, &self.state_dir, self.emit.clone(), None, self.clock.clone());
        let chans = cfg.get("channels").and_then(Value::as_array).cloned().unwrap_or_default();
        let reference = cfg.get("reference").filter(|r| !r.is_null()).cloned();
        let plan = src.set_plan(&chans, reference.as_ref());
        log::info!("{}: {} channels in {:?} MHz", src.name, plan.covered.len(), plan.window);
        let (opener, live) = (self.opener.clone(), self.live.clone());
        let factory: DeviceFactory = Arc::new(move |_serial: &str| {
            let dev = opener(fd)?;
            Ok(Arc::new(Tapped { inner: dev, live: live.clone() }) as Arc<dyn Device>)
        });
        let h = SdrHandle::new(src, Some(factory));
        h.start();
        st.handle = Some(h);
    }

    /// Streaming from the dongle.
    pub fn connected(&self) -> bool {
        self.handle().is_some_and(|h| h.with(|s| s.connected))
    }

    pub fn covers(&self, kind: &str, word: Option<&str>) -> bool {
        self.handle().is_some_and(|h| h.covers(kind, word))
    }

    /// The heartbeat's receivers entry, when a dongle is attached.
    pub fn capability(&self) -> Option<Map<String, Value>> {
        self.handle().map(|h| h.with(|s| s.capability()))
    }

    pub fn status(&self) -> Value {
        let (mode, gain, attached) = {
            let st = lock(&self.st);
            (st.mode, st.gain, st.fd.is_some())
        };
        let mut v = json!({"mode": mode.name(), "gain": gain_name(gain), "attached": attached, "connected": false,
                           "live": self.live.enabled(), "now_playing": self.live.now_playing()});
        if let Some(h) = self.handle() {
            h.with(|s| {
                v["connected"] = json!(s.connected);
                v["window_mhz"] = json!(s.plan.window);
                v["channels"] = json!(s.detectors.iter().map(|d| json!({
                    "freq": d.freq, "label": d.label, "kind": d.kind, "receiving": d.receiving(),
                })).collect::<Vec<_>>());
                v["tuner"] = s.info.get("tuner").cloned().unwrap_or(Value::Null);
                v["error"] = json!(s.last_error);
                v["last_eot"] = s.last_eot.clone().unwrap_or(Value::Null);
            });
        }
        v
    }
}

/// "auto", or the gain in dB as text ("40.2").
pub fn gain_name(gain: Option<f64>) -> String {
    gain.map_or_else(|| "auto".to_string(), |g| format!("{g}"))
}

/// The app's gain setting: "auto", or dB from 0 to 50. Anything else is the phone default.
pub fn parse_gain(s: &str) -> Option<f64> {
    match s.trim() {
        "auto" => None,
        t => Some(t.parse::<f64>().ok().filter(|g| (0.0..=50.0).contains(g)).unwrap_or(PHONE_GAIN_DB)),
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn gain_setting() {
        assert_eq!(super::parse_gain("auto"), None);
        assert_eq!(super::parse_gain("40.2"), Some(40.2));
        assert_eq!(super::parse_gain(" 20.7 "), Some(20.7));
        assert_eq!(super::parse_gain("80"), Some(super::PHONE_GAIN_DB));
        assert_eq!(super::parse_gain("loud"), Some(super::PHONE_GAIN_DB));
        assert_eq!(super::gain_name(None), "auto");
        assert_eq!(super::gain_name(Some(40.2)), "40.2");
    }

    use super::*;

    fn ev(id: &str, state: &str, freq: f64) -> Map<String, Value> {
        json!({"event_id": id, "state": state, "kind": "voice", "freq": freq, "start_ts": 1.0, "_label": "NS road"})
            .as_object().cloned().unwrap()
    }

    #[test]
    fn modes() {
        assert_eq!(Mode::parse("voice"), Some(Mode::Voice));
        assert_eq!(Mode::parse("eot").map(Mode::role), Some("eot"));
        assert_eq!(Mode::Voice.role(), "vhf");
        assert_eq!(Mode::parse("hot"), None);
    }

    #[test]
    fn live_listen_follows_the_traffic() {
        let live = Live::default();
        live.tuned(Some(2_400_000.0), 160.5e6);
        // off: nothing plays
        live.on_event(&ev("a", "open", 160.95));
        assert!(live.now_playing().is_null());
        live.set_enabled(true);
        assert_eq!(live.now_playing()["event_id"], "a");
        // a second call does not cut the first off
        live.on_event(&ev("b", "open", 160.32));
        assert_eq!(live.now_playing()["event_id"], "a");
        // when the first ends, the one still on the air plays
        live.on_event(&ev("a", "closed", 160.95));
        assert_eq!(live.now_playing()["event_id"], "b");
        live.on_event(&ev("b", "closed", 160.32));
        assert!(live.now_playing().is_null());
        // a channel outside the dongle's band cannot play
        live.on_event(&ev("c", "open", 452.9375));
        assert!(live.now_playing().is_null());
        // telemetry is never played
        let mut t = ev("d", "open", 160.6);
        t.insert("kind".into(), json!("telemetry"));
        live.on_event(&t);
        assert!(live.now_playing().is_null());
    }

    #[test]
    fn channel_wide_bursts_are_spotted() {
        let mut w = BurstWatch::default();
        // calls on two channels a second apart: not a burst
        assert!(w.opened(100.0, 160.95, "a").is_empty());
        assert!(w.opened(100.5, 161.25, "b").is_empty());
        // the phone's interference: many channels within 50 ms
        assert!(w.opened(200.00, 160.95, "c").is_empty());
        assert!(w.opened(200.02, 161.37, "d").is_empty());
        let mut got = w.opened(200.04, 160.245, "e");
        got.sort();
        assert_eq!(got, vec!["c", "d", "e"]);
        assert!(w.is_burst("c") && !w.is_burst("a"));
        // a fourth joining the burst is one too
        assert!(w.opened(200.05, 161.1, "f").contains(&"f".to_string()));
        // one channel opening twice is not two channels
        let mut w = BurstWatch::default();
        w.opened(300.0, 160.95, "x");
        assert!(w.opened(300.1, 160.95, "y").is_empty());
    }

    #[test]
    fn live_listen_skips_interference() {
        let live = Live::default();
        live.tuned(Some(2_400_000.0), 160.8e6);
        live.set_enabled(true);
        live.on_event(&ev("c", "open", 160.95));
        assert_eq!(live.now_playing()["event_id"], "c");
        // then it turns out to be a burst: it stops, and the others never start
        live.mark_noise(&["c".into(), "d".into()]);
        assert!(live.now_playing().is_null());
        live.on_event(&ev("d", "open", 161.37));
        assert!(live.now_playing().is_null());
        // a real call afterwards plays
        live.on_event(&ev("g", "open", 160.95));
        assert_eq!(live.now_playing()["event_id"], "g");
    }

    #[test]
    fn live_audio_comes_from_the_call() {
        let live = Live::default();
        let rate = 1_024_000.0;
        live.tuned(Some(rate), 160.5e6);
        live.set_enabled(true);
        live.on_event(&ev("a", "open", 160.6));
        // a carrier at 160.6 MHz, 100 kHz above centre
        let block: Vec<Complex32> = (0..102_400)
            .map(|i| Complex32::from_polar(1.0, (2.0 * std::f64::consts::PI * 100_000.0 * i as f64 / rate) as f32))
            .collect();
        live.feed(&block);
        let audio = live.take(100_000, Duration::from_millis(10));
        assert!(audio.len() > 1000, "{}", audio.len());
        // the queue never holds more than a couple of seconds
        for _ in 0..40 {
            live.feed(&block);
        }
        assert!(live.take(1_000_000, Duration::ZERO).len() <= (MAX_QUEUED_S * live.audio_rate()) as usize + 1);
        // 1.024 MS/s does not divide down to 16 kHz exactly: the app is told the real rate
        assert!((live.audio_rate() - 17_066.7).abs() < 1.0, "{}", live.audio_rate());
        // nothing waits once the call ends
        live.on_event(&ev("a", "closed", 160.6));
        live.feed(&block);
        assert!(live.take(1000, Duration::from_millis(10)).is_empty());
    }
}

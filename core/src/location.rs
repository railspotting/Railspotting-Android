//! GPS fixes (contract v1.7 section 2): which fixes to send, when, and how fast.
//!
//! The rules:
//! - Fixes go out only while listening. A fix is queued once the phone has moved
//!   `location_min_move_m` since the last queued fix, or `location_interval_s` has passed.
//! - At most 20 fixes per call, oldest first, and no fix older than an hour.
//! - At most one call per 10 s sustained, with bursts of 5 (a token bucket). A 429 waits for its
//!   Retry-After.
//! - To spare the battery, queued fixes are held up to `BATCH_S` and sent together. A first fix,
//!   or one that has waited out the interval, goes at once.

use serde_json::{json, Map, Value};
use std::collections::VecDeque;

pub const MAX_PER_CALL: usize = 20;
/// The headend refuses fixes older than an hour; leave a margin for the trip.
pub const MAX_AGE_S: f64 = 3600.0 - 60.0;
/// The headend refuses fixes more than 60 s ahead.
pub const MAX_AHEAD_S: f64 = 60.0;
/// Fixes waiting longer than this are dropped, oldest first (a long time offline).
pub const MAX_PENDING: usize = 200;
/// Queued fixes are held this long to go out together.
pub const BATCH_S: f64 = 30.0;
/// A fix this recent is put on the events heard with it.
pub const EVENT_FIX_MAX_AGE_S: f64 = 120.0;
pub const RATE_PER_S: f64 = 0.1;
pub const BURST: f64 = 5.0;

/// One fix, as the contract's `LocationFix`.
#[derive(Debug, Clone, PartialEq)]
pub struct Fix {
    pub lat: f64,
    pub lon: f64,
    pub accuracy_m: f64,
    pub ts: f64,
    pub speed_mps: Option<f64>,
    pub bearing: Option<f64>,
}

impl Fix {
    /// A fix from the app, or None when it is outside the contract's ranges. A speed or bearing
    /// out of range is left off rather than losing the fix.
    pub fn from_json(v: &Value) -> Option<Fix> {
        let num = |k: &str| v.get(k).and_then(Value::as_f64).filter(|x| x.is_finite());
        let fix = Fix {
            lat: num("lat")?,
            lon: num("lon")?,
            accuracy_m: num("accuracy_m")?,
            ts: num("ts")?,
            speed_mps: num("speed_mps").filter(|s| (0.0..=200.0).contains(s)),
            bearing: num("bearing").filter(|b| (0.0..360.0).contains(b)),
        };
        let ok = (-90.0..=90.0).contains(&fix.lat) && (-180.0..=180.0).contains(&fix.lon)
            && (0.0..=10_000.0).contains(&fix.accuracy_m) && fix.ts > 1e9;
        ok.then_some(fix)
    }

    pub fn to_json(&self) -> Value {
        let mut m = Map::new();
        m.insert("lat".into(), json!(round(self.lat, 6)));
        m.insert("lon".into(), json!(round(self.lon, 6)));
        m.insert("accuracy_m".into(), json!(round(self.accuracy_m, 1)));
        m.insert("ts".into(), json!(round(self.ts, 1)));
        if let Some(s) = self.speed_mps {
            m.insert("speed_mps".into(), json!(round(s, 1)));
        }
        if let Some(b) = self.bearing {
            m.insert("bearing".into(), json!(round(b, 0) % 360.0));
        }
        Value::Object(m)
    }
}

fn round(x: f64, places: i32) -> f64 {
    let p = 10f64.powi(places);
    (x * p).round() / p
}

/// Great-circle distance in metres.
pub fn distance_m(a: &Fix, b: &Fix) -> f64 {
    const R: f64 = 6_371_008.8;
    let (la1, la2) = (a.lat.to_radians(), b.lat.to_radians());
    let dla = la2 - la1;
    let dlo = (b.lon - a.lon).to_radians();
    let h = (dla / 2.0).sin().powi(2) + la1.cos() * la2.cos() * (dlo / 2.0).sin().powi(2);
    2.0 * R * h.sqrt().min(1.0).asin()
}

/// `location_min_move_m` and `location_interval_s` from the config's `mobile` block.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct Rules {
    pub min_move_m: f64,
    pub interval_s: f64,
}

impl Default for Rules {
    fn default() -> Self {
        Rules { min_move_m: 100.0, interval_s: 120.0 }
    }
}

impl Rules {
    pub fn from_config(cfg: &Value) -> Rules {
        let m = cfg.get("mobile");
        let get = |k: &str, d: f64| m.and_then(|m| m.get(k)).and_then(Value::as_f64).filter(|x| *x > 0.0).unwrap_or(d);
        let d = Rules::default();
        Rules { min_move_m: get("location_min_move_m", d.min_move_m), interval_s: get("location_interval_s", d.interval_s) }
    }
}

/// The per-device rate limit for /location, as the headend applies it.
#[derive(Debug, Clone)]
pub struct Bucket {
    tokens: f64,
    at: f64,
}

impl Bucket {
    pub fn new(now: f64) -> Bucket {
        Bucket { tokens: BURST, at: now }
    }

    fn refill(&mut self, now: f64) {
        if now > self.at {
            self.tokens = (self.tokens + (now - self.at) * RATE_PER_S).min(BURST);
        }
        self.at = now;
    }

    pub fn ready(&mut self, now: f64) -> bool {
        self.refill(now);
        self.tokens >= 1.0
    }

    pub fn take(&mut self, now: f64) -> bool {
        self.refill(now);
        if self.tokens >= 1.0 {
            self.tokens -= 1.0;
            true
        } else {
            false
        }
    }
}

/// Fixes on their way to the headend.
#[derive(Debug, Clone)]
pub struct Track {
    pending: VecDeque<Fix>,
    /// The last fix queued, which the move and interval rules measure from.
    last_queued: Option<Fix>,
    /// The newest fix the phone gave, sent or not (for events).
    latest: Option<Fix>,
    bucket: Bucket,
    not_before: f64,
    /// When the oldest pending fix was queued (local clock).
    held_since: Option<f64>,
    /// Queued fixes that skip the batching hold (the first, and interval keep-alives).
    urgent: bool,
    pub sent: u64,
    pub last_sent_ts: Option<f64>,
}

impl Track {
    pub fn new(now: f64) -> Track {
        Track { pending: VecDeque::new(), last_queued: None, latest: None, bucket: Bucket::new(now), not_before: 0.0,
                held_since: None, urgent: false, sent: 0, last_sent_ts: None }
    }

    pub fn latest(&self) -> Option<&Fix> {
        self.latest.as_ref()
    }

    pub fn pending(&self) -> usize {
        self.pending.len()
    }

    /// The fix to put on an event heard at `now`: the newest one, when it is recent.
    pub fn fix_for_event(&self, now: f64) -> Option<&Fix> {
        self.latest.as_ref().filter(|f| (now - f.ts).abs() <= EVENT_FIX_MAX_AGE_S)
    }

    /// A new fix from the phone. Returns true when it was queued to send.
    pub fn offer(&mut self, fix: Fix, rules: Rules, listening: bool, now: f64) -> bool {
        if self.latest.as_ref().is_none_or(|l| fix.ts >= l.ts) {
            self.latest = Some(fix.clone());
        }
        if !listening {
            return false;
        }
        let queue = match &self.last_queued {
            None => {
                self.urgent = true;
                true
            }
            Some(last) if fix.ts - last.ts >= rules.interval_s => {
                self.urgent = true;
                true
            }
            Some(last) => fix.ts > last.ts && distance_m(last, &fix) >= rules.min_move_m,
        };
        if queue {
            self.last_queued = Some(fix.clone());
            self.pending.push_back(fix);
            while self.pending.len() > MAX_PENDING {
                self.pending.pop_front();
            }
            self.held_since.get_or_insert(now);
        }
        queue
    }

    /// Listening stopped: nothing more goes out, and the next fix starts afresh.
    pub fn stop_listening(&mut self) {
        self.pending.clear();
        self.last_queued = None;
        self.held_since = None;
        self.urgent = false;
    }

    /// The next batch to send, if one is due. It takes a rate-limit token; give the batch back
    /// with `failed` when the call does not go through.
    pub fn take_batch(&mut self, now: f64) -> Option<Vec<Fix>> {
        self.pending.retain(|f| now - f.ts <= MAX_AGE_S && f.ts - now <= MAX_AHEAD_S);
        if self.pending.is_empty() {
            self.held_since = None;
            self.urgent = false;
            return None;
        }
        if now < self.not_before {
            return None;
        }
        let held = now - self.held_since.unwrap_or(now);
        if !self.urgent && held < BATCH_S && self.pending.len() < MAX_PER_CALL {
            return None;
        }
        if !self.bucket.take(now) {
            return None;
        }
        let n = self.pending.len().min(MAX_PER_CALL);
        let batch: Vec<Fix> = self.pending.drain(..n).collect();
        self.urgent = false;
        // fixes left over from a full batch have waited already
        if self.pending.is_empty() {
            self.held_since = None;
        }
        Some(batch)
    }

    pub fn sent_ok(&mut self, batch: &[Fix]) {
        self.sent += batch.len() as u64;
        if let Some(f) = batch.last() {
            self.last_sent_ts = Some(f.ts);
        }
    }

    /// Put a batch back at the front after a failed call. `retry_after` (a 429's) holds
    /// further calls that long.
    pub fn failed(&mut self, batch: Vec<Fix>, now: f64, retry_after: Option<f64>) {
        for f in batch.into_iter().rev() {
            self.pending.push_front(f);
        }
        while self.pending.len() > MAX_PENDING {
            self.pending.pop_front();
        }
        self.held_since.get_or_insert(now);
        // it has waited its turn already: retry as soon as the limits allow
        self.urgent = true;
        if let Some(r) = retry_after.filter(|r| *r > 0.0) {
            self.not_before = now + r;
        }
    }

    /// Drop a batch the headend refused (400): retrying would never succeed.
    pub fn refused(&mut self, batch: &[Fix]) {
        log::warn!("headend refused {} location fixes; dropped", batch.len());
    }
}

/// The body of POST /location.
pub fn batch_body(batch: &[Fix]) -> Value {
    json!({"fixes": batch.iter().map(Fix::to_json).collect::<Vec<_>>()})
}

#[cfg(test)]
mod tests {
    use super::*;

    const T0: f64 = 1_791_034_500.0;

    fn fix(lat: f64, lon: f64, ts: f64) -> Fix {
        Fix { lat, lon, accuracy_m: 10.0, ts, speed_mps: None, bearing: None }
    }

    /// About 111 m of latitude per 0.001 degree.
    const STEP: f64 = 0.001;

    #[test]
    fn parses_and_checks_ranges() {
        let f = Fix::from_json(&json!({"lat": 34.85, "lon": -82.39, "accuracy_m": 12, "ts": T0,
                                       "speed_mps": 18.2, "bearing": 92})).unwrap();
        assert_eq!(f.speed_mps, Some(18.2));
        assert_eq!(f.to_json(), json!({"lat": 34.85, "lon": -82.39, "accuracy_m": 12.0, "ts": T0,
                                       "speed_mps": 18.2, "bearing": 92.0}));
        for bad in [json!({"lat": 91, "lon": 0, "accuracy_m": 5, "ts": T0}),
                    json!({"lat": 0, "lon": 181, "accuracy_m": 5, "ts": T0}),
                    json!({"lat": 0, "lon": 0, "accuracy_m": 10_001, "ts": T0}),
                    json!({"lat": 0, "lon": 0, "accuracy_m": -1, "ts": T0}),
                    json!({"lat": 0, "lon": 0, "accuracy_m": 5}),
                    json!({"lat": 0, "lon": 0, "accuracy_m": 5, "ts": 12})] {
            assert_eq!(Fix::from_json(&bad), None, "{bad}");
        }
        // a bad speed or bearing is left off, the fix is kept
        let f = Fix::from_json(&json!({"lat": 1, "lon": 2, "accuracy_m": 3, "ts": T0, "speed_mps": 900, "bearing": 360})).unwrap();
        assert_eq!((f.speed_mps, f.bearing), (None, None));
        assert!(!f.to_json().as_object().unwrap().contains_key("bearing"));
    }

    #[test]
    fn distance_is_about_right() {
        let d = distance_m(&fix(34.0, -82.0, T0), &fix(34.0 + STEP, -82.0, T0));
        assert!((d - 111.2).abs() < 0.5, "{d}");
        assert_eq!(distance_m(&fix(34.0, -82.0, T0), &fix(34.0, -82.0, T0)), 0.0);
    }

    #[test]
    fn rules_come_from_config() {
        assert_eq!(Rules::from_config(&json!({})), Rules::default());
        let r = Rules::from_config(&json!({"mobile": {"location_min_move_m": 50, "location_interval_s": 60}}));
        assert_eq!(r, Rules { min_move_m: 50.0, interval_s: 60.0 });
        assert_eq!(Rules::from_config(&json!({"mobile": {"location_min_move_m": 0}})), Rules::default());
    }

    #[test]
    fn queues_on_move_or_interval_only_while_listening() {
        let r = Rules::default();
        let mut t = Track::new(T0);
        // not listening: kept as the latest fix, never queued
        assert!(!t.offer(fix(34.0, -82.0, T0), r, false, T0));
        assert_eq!(t.pending(), 0);
        assert!(t.latest().is_some());
        // the first fix while listening goes
        assert!(t.offer(fix(34.0, -82.0, T0 + 1.0), r, true, T0 + 1.0));
        // a small move, soon after: no
        assert!(!t.offer(fix(34.0 + STEP / 2.0, -82.0, T0 + 10.0), r, true, T0 + 10.0));
        // 100 m or more: yes
        assert!(t.offer(fix(34.0 + STEP, -82.0, T0 + 20.0), r, true, T0 + 20.0));
        // standing still: only once the interval has passed since the last queued fix
        assert!(!t.offer(fix(34.0 + STEP, -82.0, T0 + 139.0), r, true, T0 + 139.0));
        assert!(t.offer(fix(34.0 + STEP, -82.0, T0 + 140.0), r, true, T0 + 140.0));
        assert_eq!(t.pending(), 3);
        // listening stops: the queue is dropped, and the next fix starts afresh
        t.stop_listening();
        assert_eq!(t.pending(), 0);
        assert!(!t.offer(fix(34.1, -82.0, T0 + 150.0), r, false, T0 + 150.0));
        assert!(t.offer(fix(34.1, -82.0, T0 + 151.0), r, true, T0 + 151.0));
    }

    #[test]
    fn batches_are_held_then_sent_oldest_first() {
        let r = Rules::default();
        let mut t = Track::new(T0);
        t.offer(fix(34.0, -82.0, T0), r, true, T0);
        // the first fix goes at once
        assert_eq!(t.take_batch(T0).unwrap().len(), 1);
        // moves are held for BATCH_S, then go together, oldest first
        for i in 1..=3 {
            let ts = T0 + i as f64 * 5.0;
            assert!(t.offer(fix(34.0 + STEP * i as f64, -82.0, ts), r, true, ts));
        }
        assert!(t.take_batch(T0 + 20.0).is_none());
        let b = t.take_batch(T0 + 5.0 + BATCH_S).unwrap();
        assert_eq!(b.iter().map(|f| f.ts).collect::<Vec<_>>(), vec![T0 + 5.0, T0 + 10.0, T0 + 15.0]);
        // an interval keep-alive is not held
        t.offer(fix(34.003, -82.0, T0 + 200.0), r, true, T0 + 200.0);
        assert_eq!(t.take_batch(T0 + 200.0).unwrap().len(), 1);
    }

    #[test]
    fn at_most_twenty_per_call() {
        let r = Rules { min_move_m: 1.0, interval_s: 120.0 };
        let mut t = Track::new(T0);
        for i in 0..45 {
            t.offer(fix(34.0 + STEP * i as f64, -82.0, T0 + i as f64), r, true, T0 + i as f64);
        }
        let now = T0 + 45.0;
        assert_eq!(t.take_batch(now).unwrap().len(), 20);
        assert_eq!(t.take_batch(now).unwrap().len(), 20);
        assert_eq!(t.take_batch(now).unwrap().len(), 5);
        assert!(t.take_batch(now).is_none());
    }

    #[test]
    fn rate_limit_is_one_per_ten_seconds_with_bursts_of_five() {
        let mut b = Bucket::new(T0);
        for _ in 0..5 {
            assert!(b.take(T0));
        }
        assert!(!b.take(T0));
        assert!(!b.take(T0 + 9.0));
        assert!(b.take(T0 + 10.0));
        assert!(!b.take(T0 + 10.0));
        // it never saves up more than the burst
        assert!(b.ready(T0 + 1000.0));
        for _ in 0..5 {
            assert!(b.take(T0 + 1000.0));
        }
        assert!(!b.take(T0 + 1000.0));
    }

    #[test]
    fn retry_after_and_failures_keep_the_fixes() {
        let r = Rules::default();
        let mut t = Track::new(T0);
        t.offer(fix(34.0, -82.0, T0), r, true, T0);
        let b = t.take_batch(T0).unwrap();
        t.failed(b, T0, Some(30.0));
        assert_eq!(t.pending(), 1);
        assert!(t.take_batch(T0 + 29.0).is_none());
        let b = t.take_batch(T0 + 31.0).unwrap();
        assert_eq!(b[0].ts, T0);
        t.sent_ok(&b);
        assert_eq!((t.sent, t.last_sent_ts), (1, Some(T0)));
    }

    #[test]
    fn old_fixes_are_dropped_before_sending() {
        let r = Rules::default();
        let mut t = Track::new(T0);
        t.offer(fix(34.0, -82.0, T0), r, true, T0);
        t.offer(fix(34.0, -82.0, T0 + 3000.0), r, true, T0 + 3000.0);
        let b = t.take_batch(T0 + 3600.0).unwrap();
        assert_eq!(b.iter().map(|f| f.ts).collect::<Vec<_>>(), vec![T0 + 3000.0]);
    }

    #[test]
    fn events_get_a_recent_fix_only() {
        let mut t = Track::new(T0);
        assert!(t.fix_for_event(T0).is_none());
        t.offer(fix(34.0, -82.0, T0), Rules::default(), false, T0);
        assert!(t.fix_for_event(T0 + 60.0).is_some());
        assert!(t.fix_for_event(T0 + 121.0).is_none());
        // an older fix arriving late does not replace a newer one
        t.offer(fix(35.0, -82.0, T0 - 50.0), Rules::default(), false, T0);
        assert_eq!(t.latest().unwrap().lat, 34.0);
    }
}

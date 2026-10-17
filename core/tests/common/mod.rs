//! A fake headend for the mobile contract (v1.7), modelled on the headend's
//! internal/server/mobile_test.go: it checks signatures as internal/sig does, single-use sign-in
//! nonces, one mobile receiver per account that moves to the newest key (the old one answers
//! `403 revoked`), fix ranges and ages, and the /location rate limit.
//!
//! An ID token here is "token:<account>:<nonce>"; the real headend checks Google's.

#![allow(dead_code)]

use railspotting_proto::keys;
use railspotting_dsp::sim::{SimDevice, World};
use railspotting_engine::device::{Device, SimDev};
use railspotting_mobile::radio;
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet, VecDeque};
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::time::Duration;

pub const EOT: f64 = 457.9375;
pub const ROAD: f64 = 160.95;
pub const ROAD_2: f64 = 161.25;
pub const ROAD_3: f64 = 160.65;

#[derive(Default)]
pub struct State {
    /// Seconds added to the server's clock (a phone whose clock is off).
    pub server_skew: f64,
    pub nonces: HashSet<String>,
    /// device_id -> (public key, account, revoked)
    pub devices: HashMap<String, (Vec<u8>, String, bool)>,
    /// account -> its mobile receiver's current device
    pub accounts: HashMap<String, String>,
    pub request_nonces: HashSet<String>,
    pub heartbeats: Vec<Value>,
    pub events: Vec<Value>,
    pub fixes: Vec<Value>,
    pub location_calls: Vec<f64>,
    pub reports: Vec<Value>,
    pub commands: Vec<Value>,
    pub results: HashMap<String, Value>,
    pub down: bool,
    pub fail_next: VecDeque<(String, u16, String, Option<u64>)>,
    pub tokens: f64,
    pub tokens_at: f64,
    pub config_version: String,
    pub heartbeat_s: f64,
    /// Where the headend has the phone, and how many times it moved it (the version's tail).
    pub placed: Option<(f64, f64)>,
    pub location_id: u64,
    /// The proposed v1.9: plans by location, and refresh fixes while not listening.
    pub plan_by_location: bool,
    pub refreshes: u32,
    pub last_refresh: Option<f64>,
}

/// Charlotte is north of this latitude in the fake: its own channel plan.
pub const CHARLOTTE_LAT: f64 = 35.0;
pub const CLT_ROAD: f64 = 160.215;

fn km(a: (f64, f64), b: (f64, f64)) -> f64 {
    let (dlat, dlon) = ((a.0 - b.0).to_radians(), (a.1 - b.1).to_radians());
    let h = (dlat / 2.0).sin().powi(2) + a.0.to_radians().cos() * b.0.to_radians().cos() * (dlon / 2.0).sin().powi(2);
    2.0 * 6371.0 * h.sqrt().asin()
}

impl State {
    fn version(&self) -> String {
        format!("{}.{}", self.config_version, self.location_id)
    }

    fn charlotte(&self) -> bool {
        self.placed.is_some_and(|p| p.0 >= CHARLOTTE_LAT)
    }
}

pub struct Fake {
    pub state: Arc<Mutex<State>>,
    port: u16,
    stop: Arc<AtomicBool>,
}

fn now() -> f64 {
    railspotting_common::now()
}

impl Fake {
    pub fn start() -> Fake {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let state = Arc::new(Mutex::new(State { tokens: 5.0, tokens_at: now(), config_version: "1".into(),
                                                heartbeat_s: 5.0, ..State::default() }));
        let stop = Arc::new(AtomicBool::new(false));
        let (st, sp) = (state.clone(), stop.clone());
        std::thread::spawn(move || {
            for conn in listener.incoming() {
                if sp.load(Ordering::SeqCst) {
                    break;
                }
                let Ok(stream) = conn else { continue };
                let st = st.clone();
                std::thread::spawn(move || serve(stream, &st));
            }
        });
        Fake { state, port, stop }
    }

    pub fn url(&self) -> String {
        format!("http://127.0.0.1:{}", self.port)
    }

    pub fn st(&self) -> MutexGuard<'_, State> {
        self.state.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// The next request to `path` fails with this status and error.
    pub fn fail_next(&self, path: &str, status: u16, error: &str, retry_after: Option<u64>) {
        self.st().fail_next.push_back((path.into(), status, error.into(), retry_after));
    }
}

impl Drop for Fake {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        let _ = TcpStream::connect(("127.0.0.1", self.port));
    }
}

struct Req {
    method: String,
    path: String,
    headers: HashMap<String, String>,
    body: Vec<u8>,
}

fn read(stream: &TcpStream) -> Option<Req> {
    let mut r = BufReader::new(stream);
    let mut line = String::new();
    r.read_line(&mut line).ok()?;
    let mut parts = line.split_whitespace();
    let method = parts.next()?.to_string();
    let path = parts.next()?.to_string();
    let mut headers = HashMap::new();
    loop {
        let mut h = String::new();
        r.read_line(&mut h).ok()?;
        let h = h.trim_end();
        if h.is_empty() {
            break;
        }
        if let Some((k, v)) = h.split_once(':') {
            headers.insert(k.trim().to_ascii_lowercase(), v.trim().to_string());
        }
    }
    let n: usize = headers.get("content-length").and_then(|v| v.parse().ok()).unwrap_or(0);
    let mut body = vec![0; n];
    r.read_exact(&mut body).ok()?;
    Some(Req { method, path, headers, body })
}

fn serve(mut stream: TcpStream, st: &Arc<Mutex<State>>) {
    let _ = stream.set_read_timeout(Some(Duration::from_secs(5)));
    let Some(req) = read(&stream) else { return };
    let reply = {
        let mut s = st.lock().unwrap_or_else(|p| p.into_inner());
        if s.down {
            return; // dropped: the phone sees a network error
        }
        handle(&mut s, &req)
    };
    let (status, body, extra, skew) = reply;
    let body = body.map(|b| b.to_string()).unwrap_or_default();
    let mut out = format!("HTTP/1.1 {status} X\r\nContent-Length: {}\r\nContent-Type: application/json\r\nConnection: close\r\n\
                           X-RS-Server-Time: {}\r\n", body.len(), (now() + skew) as i64);
    for (k, v) in extra {
        out.push_str(&format!("{k}: {v}\r\n"));
    }
    out.push_str("\r\n");
    out.push_str(&body);
    let _ = stream.write_all(out.as_bytes());
}

type Reply = (u16, Option<Value>, Vec<(String, String)>, f64);

fn err(s: &State, status: u16, error: &str) -> Reply {
    (status, Some(json!({"error": error, "message": error})), vec![], s.server_skew)
}

fn ok(s: &State, status: u16, body: Value) -> Reply {
    (status, Some(body), vec![], s.server_skew)
}

/// Signature checks as internal/sig and the headend's keySigned: timestamp within 300 s, a fresh
/// 32-hex nonce, and the signature over the canonical string.
fn check_sig(s: &mut State, req: &Req, public: &[u8]) -> Result<(), Reply> {
    let h = |k: &str| req.headers.get(k).cloned().unwrap_or_default();
    let ts: i64 = h("x-rs-timestamp").parse().map_err(|_| err(s, 401, "bad_signature"))?;
    if (ts as f64 - (now() + s.server_skew)).abs() > 300.0 {
        return Err(err(s, 401, "clock_skew"));
    }
    let nonce = h("x-rs-nonce");
    if nonce.len() != 32 || !nonce.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()) {
        return Err(err(s, 401, "bad_signature"));
    }
    let canon = keys::canonical_string(&req.method, &req.path, ts, &nonce, &req.body);
    if !keys::verify(public, &canon, &h("x-rs-signature")) {
        return Err(err(s, 401, "bad_signature"));
    }
    if !s.request_nonces.insert(nonce) {
        return Err(err(s, 401, "replay"));
    }
    Ok(())
}

fn valid_fix(f: &Value, server_now: f64) -> bool {
    let n = |k: &str| f.get(k).and_then(Value::as_f64);
    let (Some(lat), Some(lon), Some(acc), Some(ts)) = (n("lat"), n("lon"), n("accuracy_m"), n("ts")) else { return false };
    (-90.0..=90.0).contains(&lat) && (-180.0..=180.0).contains(&lon) && (0.0..=10_000.0).contains(&acc)
        && ts <= server_now + 60.0 && ts >= server_now - 3600.0
}

fn handle(s: &mut State, req: &Req) -> Reply {
    let path = req.path.split('?').next().unwrap_or("").to_string();
    if let Some(i) = s.fail_next.iter().position(|(p, ..)| *p == path) {
        let (_, status, error, retry) = s.fail_next.remove(i).unwrap();
        let mut r = err(s, status, &error);
        if let Some(r_s) = retry {
            r.2.push(("Retry-After".into(), r_s.to_string()));
        }
        return r;
    }
    let body: Value = serde_json::from_slice(&req.body).unwrap_or(Value::Null);
    if path == "/api/v1/mobile/hello" {
        let nonce = keys::new_nonce() + "xyz";
        s.nonces.insert(nonce.clone());
        return ok(s, 200, json!({"google_client_id": "test-client", "nonce": nonce, "nonce_expires_in": 300,
                                  "terms_url": "https://example.test/terms", "privacy_url": "https://example.test/privacy"}));
    }
    if path == "/api/v1/mobile/link" {
        if req.headers.contains_key("x-rs-device") {
            return err(s, 400, "bad_request");
        }
        let Ok(public) = keys::b64url_decode(body["public_key"].as_str().unwrap_or("")) else { return err(s, 400, "bad_request") };
        if public.len() != 32 {
            return err(s, 400, "bad_request");
        }
        if let Err(r) = check_sig(s, req, &public) {
            return r;
        }
        let nonce = body["nonce"].as_str().unwrap_or("").to_string();
        let token = body["id_token"].as_str().unwrap_or("");
        let mut parts = token.splitn(3, ':');
        let (Some("token"), Some(account), Some(tok_nonce)) = (parts.next(), parts.next(), parts.next()) else {
            return err(s, 401, "invalid_token");
        };
        if tok_nonce != nonce || !s.nonces.remove(&nonce) {
            return err(s, 401, "invalid_token");
        }
        if s.devices.values().any(|(k, a, revoked)| *k == public && a != account && !revoked) {
            return err(s, 409, "key_in_use");
        }
        // the same key again gets its device back
        if let Some((id, _)) = s.devices.iter().find(|(_, (k, a, revoked))| *k == public && a == account && !revoked) {
            let id = id.clone();
            return ok(s, 200, link_result(&id, account, true));
        }
        let id = format!("rs-{}", &keys::new_nonce()[..12]);
        let relinked = match s.accounts.get(account).cloned() {
            Some(old) => {
                if let Some(d) = s.devices.get_mut(&old) {
                    d.2 = true;
                }
                true
            }
            None => false,
        };
        s.devices.insert(id.clone(), (public, account.to_string(), false));
        s.accounts.insert(account.to_string(), id.clone());
        return ok(s, if relinked { 200 } else { 201 }, link_result(&id, account, relinked));
    }
    // device calls
    let dev = req.headers.get("x-rs-device").cloned().unwrap_or_default();
    let Some((public, _, revoked)) = s.devices.get(&dev).cloned() else { return err(s, 401, "unknown_device") };
    if let Err(r) = check_sig(s, req, &public) {
        return r;
    }
    if revoked {
        return err(s, 403, "revoked");
    }
    let server_now = now() + s.server_skew;
    match path.as_str() {
        "/api/v1/config" => {
            if s.charlotte() {
                let mut cfg = json!({
                    "config_version": s.version(), "heartbeat_interval_s": 60,
                    "channels": [
                        {"ch": 1, "freq": CLT_ROAD, "railroad": "NS", "label": "NS Charlotte road", "kind": "voice", "proximity": false},
                        {"ch": 2, "freq": EOT, "railroad": null, "label": "EOT", "kind": "telemetry", "proximity": true},
                    ],
                    "upload": {"max_batch": 200, "flush_interval_s": 0.5},
                    "mobile": {"heartbeat_interval_s": s.heartbeat_s, "location_interval_s": 120, "location_min_move_m": 100,
                               "reach_km": 3, "share": true, "plan_by_location": s.plan_by_location},
                });
                if s.plan_by_location {
                    cfg["plan"] = json!({"id": 2, "name": "NS Charlotte", "region": "ns-clt", "source": "location"});
                }
                return ok(s, 200, cfg);
            }
            let cfg = json!({
                "config_version": s.version(), "heartbeat_interval_s": 60,
                "channels": [
                    {"ch": 1, "freq": ROAD, "railroad": "NS", "label": "NS road", "kind": "voice", "proximity": false},
                    {"ch": 4, "freq": ROAD_2, "railroad": "NS", "label": "NS dispatch", "kind": "voice", "proximity": false},
                    {"ch": 5, "freq": ROAD_3, "railroad": "NS", "label": "NS yard", "kind": "voice", "proximity": false},
                    {"ch": 2, "freq": EOT, "railroad": null, "label": "EOT", "kind": "telemetry", "proximity": true},
                    {"ch": 3, "freq": 452.9375, "railroad": null, "label": "HOT", "kind": "telemetry", "proximity": true},
                ],
                "upload": {"max_batch": 200, "flush_interval_s": 0.5},
                "mobile": {"heartbeat_interval_s": s.heartbeat_s, "location_interval_s": 120, "location_min_move_m": 100,
                           "reach_km": 3, "share": true, "plan_by_location": s.plan_by_location},
            });
            ok(s, 200, cfg)
        }
        "/api/v1/heartbeat" => {
            s.heartbeats.push(body);
            let pending = s.commands.len();
            let v = s.version();
            ok(s, 200, json!({"server_time": server_now, "config_version": v, "commands_pending": pending}))
        }
        "/api/v1/events" => {
            let evs = body["events"].as_array().cloned().unwrap_or_default();
            // contract v1.7: an event's fix is checked like any other
            if evs.iter().any(|e| e.get("location").is_some_and(|l| !valid_fix(l, server_now))) {
                return err(s, 400, "bad_request");
            }
            let ids: Vec<Value> = evs.iter().map(|e| e["event_id"].clone()).collect();
            s.events.extend(evs);
            ok(s, 200, json!({"accepted": ids, "duplicates": []}))
        }
        "/api/v1/location" => {
            // 1 per 10 s sustained, bursts of 5
            s.tokens = (s.tokens + (server_now - s.tokens_at) * 0.1).min(5.0);
            s.tokens_at = server_now;
            if s.tokens < 1.0 {
                let mut r = err(s, 429, "rate_limited");
                r.2.push(("Retry-After".into(), "10".into()));
                return r;
            }
            s.tokens -= 1.0;
            // the real handler decodes strictly: an unknown field is a 400
            let refresh = body.get("refresh").and_then(Value::as_bool) == Some(true);
            if body.get("refresh").is_some() && !s.plan_by_location {
                return err(s, 400, "bad_request");
            }
            let fixes = body["fixes"].as_array().cloned().unwrap_or_default();
            if fixes.is_empty() || fixes.len() > 20 || !fixes.iter().all(|f| valid_fix(f, server_now)) {
                return err(s, 400, "bad_request");
            }
            // v1.9: one refresh per 30 s per device
            if refresh {
                if s.last_refresh.is_some_and(|t| server_now - t < 30.0) {
                    let mut r = err(s, 429, "rate_limited");
                    r.2.push(("Retry-After".into(), "30".into()));
                    return r;
                }
                s.last_refresh = Some(server_now);
                s.refreshes += 1;
            }
            s.location_calls.push(server_now);
            let n = fixes.len();
            // the newest fix places the phone when it is 250 m or more from where it is
            let last = fixes.last().map(|f| (f["lat"].as_f64().unwrap_or(0.0), f["lon"].as_f64().unwrap_or(0.0)));
            let mut used = 0;
            if let Some(at) = last {
                if s.placed.is_none_or(|p| km(p, at) >= 0.25) {
                    s.placed = Some(at);
                    s.location_id += 1;
                    used = 1;
                }
            }
            s.fixes.extend(fixes);
            // v1.9: every answer carries the config version
            let mut answer = json!({"accepted": n, "used": used});
            if s.plan_by_location {
                answer["config_version"] = json!(s.version());
            }
            ok(s, 200, answer)
        }
        "/api/v1/reports" => {
            s.reports.push(body);
            ok(s, 200, json!({"stored": true}))
        }
        "/api/v1/commands" => {
            let cmds = std::mem::take(&mut s.commands);
            ok(s, 200, json!({"commands": cmds}))
        }
        p if p.starts_with("/api/v1/commands/") && p.ends_with("/result") => {
            let id = p.split('/').nth(4).unwrap_or("").to_string();
            s.results.insert(id, body);
            ok(s, 200, json!({"stored": true}))
        }
        _ => err(s, 404, "not_found"),
    }
}

fn link_result(device_id: &str, account: &str, relinked: bool) -> Value {
    json!({"device_id": device_id, "receiver_id": 42, "receiver_name": format!("{account}'s phone"), "kind": "mobile",
           "commissioning": "provisional", "account": {"email": format!("{account}@example.com"), "name": account},
           "relinked": relinked})
}

/// A simulated RTL-SDR dongle (the receiver's radio simulator): signals added to `world` are
/// in the samples it streams, in real time.
pub struct Dongle {
    pub world: Arc<World>,
}

impl Dongle {
    pub fn new() -> Dongle {
        Dongle { world: Arc::new(World::new(7)) }
    }

    pub fn opener(&self) -> radio::Opener {
        let w = self.world.clone();
        Arc::new(move |_fd| {
            let dev = SimDevice::new("sim-0001", w.clone(), railspotting_common::system_clock(), true);
            Ok(Arc::new(SimDev(dev)) as Arc<dyn Device>)
        })
    }
}

/// Wait up to `secs` for `f` to hold.
pub fn wait_for(secs: f64, mut f: impl FnMut() -> bool) -> bool {
    let end = now() + secs;
    while now() < end {
        if f() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(50));
    }
    f()
}

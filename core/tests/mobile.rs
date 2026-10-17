//! The mobile receiver end to end, against a fake headend (tests/common) and a simulated RTL-SDR
//! dongle from the receiver's radio simulator.

mod common;

use common::{wait_for, Dongle, Fake, EOT, ROAD};
use railspotting_mobile::agent::{Agent, Options, Phone};
use railspotting_mobile::link::{self, Device, SkewClock};
use railspotting_mobile::location::Fix;
use railspotting_mobile::radio::Mode;
use serde_json::{json, Value};
use std::sync::Arc;

fn device() -> Device {
    Device { model: "Pixel 8".into(), android_version: "15".into(), app_version: "0.1.0".into() }
}

/// Sign in as `account` with a new key: (seed, link status, link result).
fn sign_in(fake: &Fake, account: &str, skew: &Arc<SkewClock>) -> ([u8; 32], Result<(u16, Value), String>) {
    let seed = link::new_seed();
    let key = link::key_from_seed(&seed).unwrap();
    let api = link::client(&fake.url(), &key, None, skew.clock()).unwrap();
    let hello = link::hello(&api).unwrap();
    skew.absorb(&api);
    assert_eq!(hello["google_client_id"], "test-client");
    let nonce = hello["nonce"].as_str().unwrap().to_string();
    let body = link::link_body(&format!("token:{account}:{nonce}"), &nonce, &link::public_key(&seed).unwrap(), None, &device());
    (seed, link::link(&api, &body).map_err(|e| e.error))
}

/// The dongle's USB file descriptor as the app would pass it (the simulator ignores it).
const FD: i64 = 7;

fn start(fake: &Fake, seed: &[u8], device_id: &str, dongle: &Dongle, mode: Mode) -> (Arc<Agent>, tempdir::Dir) {
    let dir = tempdir::Dir::new();
    let agent = Agent::new(Options {
        server_url: fake.url(),
        state_dir: dir.path.clone(),
        seed: seed.to_vec(),
        device_id: device_id.into(),
        app_version: "0.1.0".into(),
        share: true,
        radio: dongle.opener(),
        mode,
        gain: Some(40.2),
        skew: None,
    }).unwrap();
    agent.start();
    (agent, dir)
}

fn fix(lat: f64, lon: f64) -> Fix {
    Fix { lat, lon, accuracy_m: 8.0, ts: railspotting_common::now(), speed_mps: Some(20.0), bearing: Some(90.0) }
}

fn last_mobile(fake: &Fake) -> Option<Value> {
    fake.st().heartbeats.last().map(|h| h["mobile"].clone())
}

#[test]
fn links_and_moves_between_phones() {
    let fake = Fake::start();
    let skew = SkewClock::new();
    let (seed_a, r) = sign_in(&fake, "ann", &skew);
    let (status, res) = r.unwrap();
    assert_eq!(status, 201);
    assert_eq!((res["kind"].as_str(), res["commissioning"].as_str(), res["relinked"].as_bool()),
               (Some("mobile"), Some("provisional"), Some(false)));
    let dev_a = res["device_id"].as_str().unwrap().to_string();

    // phone A runs
    let (a, _dir) = start(&fake, &seed_a, &dev_a, &Dongle::new(), Mode::Eot);
    assert!(wait_for(10.0, || a.status()["online"] == true), "{}", a.status());

    // a reused nonce is refused
    let key = link::key_from_seed(&link::new_seed()).unwrap();
    let api = link::client(&fake.url(), &key, None, skew.clock()).unwrap();
    let nonce = link::hello(&api).unwrap()["nonce"].as_str().unwrap().to_string();
    let body = link::link_body(&format!("token:ann:{nonce}"), &nonce, &railspotting_proto::keys::public_b64(&key), None, &device());
    assert!(link::link(&api, &body).is_ok());
    let again = link::link(&api, &body).unwrap_err();
    assert_eq!((again.status, again.error.as_str()), (Some(401), "invalid_token"));

    // phone B takes the account's receiver over: 200, relinked, and A is cut off
    let (_seed_b, r) = sign_in(&fake, "ann", &skew);
    let (status, res) = r.unwrap();
    assert_eq!((status, res["relinked"].as_bool()), (200, Some(true)));
    a.set_phone(Phone { share: false, ..Phone::default() }); // wakes A's heartbeat
    assert!(wait_for(10.0, || a.signed_out().is_some()), "{}", a.status());
    assert_eq!(a.signed_out().as_deref(), Some("revoked"));
    assert!(wait_for(5.0, || a.stopped()));
    a.stop();
}

#[test]
fn a_key_of_another_account_is_refused() {
    let fake = Fake::start();
    let skew = SkewClock::new();
    let (seed, r) = sign_in(&fake, "ann", &skew);
    r.unwrap();
    // bob tries to link with ann's key
    let key = link::key_from_seed(&seed).unwrap();
    let api = link::client(&fake.url(), &key, None, skew.clock()).unwrap();
    let nonce = link::hello(&api).unwrap()["nonce"].as_str().unwrap().to_string();
    let body = link::link_body(&format!("token:bob:{nonce}"), &nonce, &link::public_key(&seed).unwrap(), None, &device());
    let e = link::link(&api, &body).unwrap_err();
    assert_eq!((e.status, e.error.as_str()), (Some(409), "key_in_use"));
}

#[test]
fn the_clock_follows_the_server() {
    let fake = Fake::start();
    fake.st().server_skew = 400.0; // more than the 300 s the headend allows
    let skew = SkewClock::new();
    let (_seed, r) = sign_in(&fake, "cy", &skew);
    // hello's X-RS-Server-Time corrected the clock before the signed link
    assert_eq!(r.unwrap().0, 201);
    assert!((skew.correction() - 400.0).abs() <= 2.0, "{}", skew.correction());
}

#[test]
fn listens_reports_and_sends_fixes() {
    let fake = Fake::start();
    let (seed, r) = sign_in(&fake, "dee", &SkewClock::new());
    let dev = r.unwrap().1["device_id"].as_str().unwrap().to_string();
    let dongle = Dongle::new();
    let (a, _dir) = start(&fake, &seed, &dev, &dongle, Mode::Eot);

    // no dongle: online but not listening, and fixes stay on the phone
    assert!(wait_for(10.0, || last_mobile(&fake).is_some()));
    assert_eq!(last_mobile(&fake).unwrap()["listening"], false);
    a.add_fix(fix(34.85, -82.39));
    std::thread::sleep(std::time::Duration::from_millis(1500));
    assert!(fake.st().fixes.is_empty());
    assert_eq!(a.status()["listening"], false);

    // the dongle is plugged in: listening, at once in a heartbeat
    a.radio_attached(FD);
    assert!(wait_for(10.0, || a.status()["listening"] == true), "{}", a.status());
    assert!(wait_for(10.0, || last_mobile(&fake).is_some_and(|m| m["listening"] == true)));
    let hb = fake.st().heartbeats.last().cloned().unwrap();
    assert_eq!(hb["scanner"]["connected"], false);
    assert!(hb["agent_version"].as_str().unwrap().starts_with("android-"));
    let sdr = &hb["capabilities"]["receivers"][1];
    assert_eq!((sdr["type"].as_str(), sdr["role"].as_str(), sdr["connected"].as_bool()), (Some("sdr"), Some("eot"), Some(true)));
    assert_eq!(hb["capabilities"]["features"]["eot_decode"], true);

    // a fix now goes out
    a.add_fix(fix(34.85, -82.39));
    assert!(wait_for(5.0, || !fake.st().fixes.is_empty()));
    assert_eq!(fake.st().fixes[0]["accuracy_m"], 8.0);

    // an EOT packet: an event, decoded on the phone, with where it was heard
    let t = railspotting_common::now() + 0.5;
    dongle.world.eot_burst(EOT, 30.0, t, &[railspotting_dsp::eot::build_payload(41234, 88)]);
    assert!(wait_for(20.0, || fake.st().events.iter().any(|e| e["state"] == "closed")), "{}", a.status());
    let ev = fake.st().events.iter().find(|e| e["state"] == "closed").cloned().unwrap();
    assert_eq!((ev["freq"].as_f64(), ev["kind"].as_str(), ev["ch"].as_i64()), (Some(EOT), Some("telemetry"), Some(2)));
    assert_eq!((ev["telemetry"]["unit_id"].as_i64(), ev["telemetry"]["brake_pipe_psi"].as_i64()), (Some(41234), Some(88)), "{ev}");
    assert_eq!(ev["location"]["lat"], 34.85);
    assert!(ev.get("_source").is_none(), "private keys stay on the phone");
    // the app's activity list shows the decode
    let shown = a.status()["recent_events"][0].clone();
    assert!(shown["decoded"].as_str().unwrap().starts_with("EOT unit 41234, 88 psi"), "{shown}");

    // unplugged: not listening, said at once, and no more fixes
    a.radio().detach();
    assert!(wait_for(10.0, || last_mobile(&fake).is_some_and(|m| m["listening"] == false)));
    let sent = fake.st().fixes.len();
    a.add_fix(fix(34.86, -82.39));
    std::thread::sleep(std::time::Duration::from_millis(1500));
    assert_eq!(fake.st().fixes.len(), sent);
    a.stop();
}

#[test]
fn events_wait_offline_and_go_with_their_own_times() {
    let fake = Fake::start();
    let (seed, r) = sign_in(&fake, "eve", &SkewClock::new());
    let dev = r.unwrap().1["device_id"].as_str().unwrap().to_string();
    let dongle = Dongle::new();
    let (a, _dir) = start(&fake, &seed, &dev, &dongle, Mode::Eot);
    a.radio_attached(FD);
    assert!(wait_for(10.0, || a.listening()), "{}", a.status());

    fake.st().down = true;
    let t = railspotting_common::now() + 0.5;
    dongle.world.eot_burst(EOT, 30.0, t, &[railspotting_dsp::eot::build_payload(41234, 88)]);
    assert!(wait_for(20.0, || a.outbox().depth().unwrap_or(0) >= 2), "{}", a.status());
    assert!(fake.st().events.is_empty());
    std::thread::sleep(std::time::Duration::from_secs(2));

    fake.st().down = false;
    assert!(wait_for(40.0, || fake.st().events.iter().any(|e| e["state"] == "closed")), "{}", a.status());
    let ev = fake.st().events.iter().find(|e| e["state"] == "closed").cloned().unwrap();
    let start_ts = ev["start_ts"].as_f64().unwrap();
    assert!((start_ts - t).abs() < 3.0, "heard at {t}, sent as {start_ts}");
    assert!(wait_for(5.0, || a.outbox().depth().unwrap_or(1) == 0));
    a.stop();
}

#[test]
fn sharing_and_battery_go_in_the_heartbeat() {
    let fake = Fake::start();
    let (seed, r) = sign_in(&fake, "fay", &SkewClock::new());
    let dev = r.unwrap().1["device_id"].as_str().unwrap().to_string();
    let dongle = Dongle::new();
    let (a, _dir) = start(&fake, &seed, &dev, &dongle, Mode::Eot);
    assert!(wait_for(10.0, || last_mobile(&fake).is_some()));
    assert_eq!(last_mobile(&fake).unwrap(), json!({"listening": false, "share": true}));
    a.set_phone(Phone { share: false, foreground: Some(true), battery_pct: Some(64), charging: Some(false) });
    assert!(wait_for(10.0, || last_mobile(&fake).is_some_and(|m| m["share"] == false)));
    assert_eq!(last_mobile(&fake).unwrap(),
               json!({"listening": false, "share": false, "foreground": true, "battery_pct": 64, "charging": false}));
    a.stop();
}

#[test]
fn location_calls_respect_429() {
    let fake = Fake::start();
    let (seed, r) = sign_in(&fake, "gus", &SkewClock::new());
    let dev = r.unwrap().1["device_id"].as_str().unwrap().to_string();
    let dongle = Dongle::new();
    let (a, _dir) = start(&fake, &seed, &dev, &dongle, Mode::Eot);
    a.radio_attached(FD);
    assert!(wait_for(10.0, || a.listening()));
    fake.fail_next("/api/v1/location", 429, "rate_limited", Some(3));
    a.add_fix(fix(34.85, -82.39));
    std::thread::sleep(std::time::Duration::from_millis(2000));
    assert!(fake.st().fixes.is_empty(), "sent before Retry-After");
    assert!(wait_for(5.0, || fake.st().fixes.len() == 1));
    a.stop();
}

/// A temporary folder removed when dropped (std only, to keep the dependencies few).
mod tempdir {
    use std::path::PathBuf;

    pub struct Dir {
        pub path: PathBuf,
    }

    impl Dir {
        pub fn new() -> Dir {
            let path = std::env::temp_dir().join(format!("rs-mobile-{}", railspotting_proto::keys::new_nonce()));
            std::fs::create_dir_all(&path).unwrap();
            Dir { path }
        }
    }

    impl Drop for Dir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.path);
        }
    }
}

#[test]
fn stopping_says_so_at_once() {
    let fake = Fake::start();
    let (seed, r) = sign_in(&fake, "hal", &SkewClock::new());
    let dev = r.unwrap().1["device_id"].as_str().unwrap().to_string();
    let dongle = Dongle::new();
    let (a, _dir) = start(&fake, &seed, &dev, &dongle, Mode::Eot);
    a.radio_attached(FD);
    assert!(wait_for(10.0, || last_mobile(&fake).is_some_and(|m| m["listening"] == true)));
    a.stop();
    // the last heartbeat, sent while stopping, says the phone stopped listening
    assert_eq!(last_mobile(&fake).unwrap()["listening"], false);
    assert!(!a.listening());
}

#[test]
fn live_listen_plays_the_call_on_the_air() {
    let fake = Fake::start();
    let (seed, r) = sign_in(&fake, "ivy", &SkewClock::new());
    let dev = r.unwrap().1["device_id"].as_str().unwrap().to_string();
    let dongle = Dongle::new();
    let (a, _dir) = start(&fake, &seed, &dev, &dongle, Mode::Voice);
    a.radio().live.set_enabled(true);
    a.radio_attached(FD);
    assert!(wait_for(10.0, || a.listening()), "{}", a.status());
    let hb_sdr = || fake.st().heartbeats.last().map(|h| h["capabilities"]["receivers"][1]["role"].clone());
    assert!(wait_for(10.0, || hb_sdr() == Some(json!("vhf"))));

    // a call on the road channel: it comes to the front, and its audio plays
    let t = railspotting_common::now() + 0.3;
    dongle.world.speechlike(ROAD, 30.0, t, Some(t + 4.0), 2500.0, "voice");
    assert!(wait_for(10.0, || a.radio().live.now_playing()["freq"] == json!(ROAD)), "{}", a.status());
    assert_eq!(a.radio().live.now_playing()["label"], "NS road");
    let mut audio = 0;
    let end = railspotting_common::now() + 2.0;
    while railspotting_common::now() < end {
        audio += a.radio().live.take(16_000, std::time::Duration::from_millis(200)).len();
    }
    assert!(audio > 8_000, "{audio} samples");
    // when it ends: silence, and a voice event for the headend
    assert!(wait_for(15.0, || a.radio().live.now_playing().is_null()));
    assert!(wait_for(10.0, || fake.st().events.iter().any(|e| e["state"] == "closed" && e["kind"] == "voice")));
    a.stop();
}

#[test]
fn switching_modes_reopens_the_dongle() {
    let fake = Fake::start();
    let (seed, r) = sign_in(&fake, "jo", &SkewClock::new());
    let dev = r.unwrap().1["device_id"].as_str().unwrap().to_string();
    let dongle = Dongle::new();
    let (a, _dir) = start(&fake, &seed, &dev, &dongle, Mode::Voice);
    a.radio_attached(FD);
    assert!(wait_for(10.0, || a.listening()));
    assert_eq!(a.status()["radio"]["mode"], "voice");
    a.set_mode(Mode::Eot);
    assert!(wait_for(10.0, || a.listening() && a.status()["radio"]["mode"] == "eot"), "{}", a.status());
    // the heartbeat follows at once with the new role
    assert!(wait_for(10.0, || fake.st().heartbeats.last()
        .is_some_and(|h| h["capabilities"]["receivers"][1]["role"] == "eot")));
    let chans = a.status()["radio"]["channels"].clone();
    assert!(chans.as_array().unwrap().iter().any(|c| c["freq"] == json!(EOT)), "{chans}");
    a.stop();
}

#[test]
fn interference_across_the_band_is_not_played_but_still_reported() {
    let fake = Fake::start();
    let (seed, r) = sign_in(&fake, "kai", &SkewClock::new());
    let dev = r.unwrap().1["device_id"].as_str().unwrap().to_string();
    let dongle = Dongle::new();
    let (a, _dir) = start(&fake, &seed, &dev, &dongle, Mode::Voice);
    a.radio().live.set_enabled(true);
    a.radio_attached(FD);
    assert!(wait_for(10.0, || a.listening()), "{}", a.status());
    // what the phone saw: every road channel lit at once for a moment
    let t = railspotting_common::now() + 0.3;
    for f in [common::ROAD, common::ROAD_2, common::ROAD_3] {
        dongle.world.speechlike(f, 30.0, t, Some(t + 1.5), 2500.0, "voice");
    }
    assert!(wait_for(15.0, || fake.st().events.iter().filter(|e| e["state"] == "closed").count() >= 3), "{}", a.status());
    // reported as heard, shown as interference, never played
    let shown = a.status()["recent_events"].as_array().cloned().unwrap();
    let voice: Vec<&Value> = shown.iter().filter(|e| e["kind"] == "voice").collect();
    assert!(voice.len() >= 3 && voice.iter().all(|e| e["burst"] == true), "{shown:?}");
    assert!(a.radio().live.now_playing().is_null());
    a.stop();
}

#[test]
fn a_move_brings_the_channel_list_for_there_at_once() {
    let fake = Fake::start();
    fake.st().heartbeat_s = 3600.0; // no heartbeat to lean on
    let (seed, r) = sign_in(&fake, "lee", &SkewClock::new());
    let dev = r.unwrap().1["device_id"].as_str().unwrap().to_string();
    let dongle = Dongle::new();
    let (a, _dir) = start(&fake, &seed, &dev, &dongle, Mode::Voice);
    a.radio_attached(FD);
    assert!(wait_for(10.0, || a.listening()), "{}", a.status());
    a.add_fix(fix(34.85, -82.39));
    assert!(wait_for(5.0, || a.status()["plan"]["config_version"] == "1.1"), "{}", a.status());
    // the phone drives to Charlotte: the plan for there as soon as the fix goes (moves are
    // batched for up to location::BATCH_S), without waiting for a heartbeat
    a.add_fix(fix(35.23, -80.84));
    let by = railspotting_mobile::location::BATCH_S + 5.0;
    assert!(wait_for(by, || a.config()["channels"][0]["freq"] == json!(common::CLT_ROAD)), "{}", a.status()["plan"]);
    assert_eq!(a.status()["plan"]["channels"], 2);
    a.stop();
}

#[test]
fn refresh_follows_the_contract() {
    let fake = Fake::start();
    let (seed, r) = sign_in(&fake, "max", &SkewClock::new());
    let dev = r.unwrap().1["device_id"].as_str().unwrap().to_string();
    let dongle = Dongle::new();
    let (a, _dir) = start(&fake, &seed, &dev, &dongle, Mode::Voice);
    assert!(wait_for(10.0, || a.config()["config_version"].is_string()));
    // today's headend: not listening, so no fix goes; the plan is fetched afresh all the same
    let r = a.refresh_plan(Some(fix(35.23, -80.84)));
    assert_eq!((r["ok"].as_bool(), r["sent_fix"].as_bool()), (Some(true), Some(false)), "{r}");
    assert!(fake.st().fixes.is_empty());
    // a headend with plans by location takes a refresh fix while not listening, and re-plans
    fake.st().plan_by_location = true;
    a.refresh_plan(None);
    assert_eq!(a.status()["plan"]["by_location"], true);
    let r = a.refresh_plan(Some(fix(35.23, -80.84)));
    assert_eq!((r["sent_fix"].as_bool(), r["moved"].as_bool()), (Some(true), Some(true)), "{r}");
    assert_eq!(r["plan"]["name"], "NS Charlotte");
    assert_eq!(r["plan"]["source"], "location");
    assert_eq!(fake.st().refreshes, 1);
    // a second refresh within 30 s is limited: the list as it is, and a word on why
    let r = a.refresh_plan(Some(fix(35.24, -80.85)));
    assert_eq!((r["ok"].as_bool(), r["sent_fix"].as_bool()), (Some(true), Some(false)), "{r}");
    assert!(r["note"].as_str().unwrap().contains("less than 30 s"), "{r}");
    assert_eq!(r["plan"]["name"], "NS Charlotte");
    a.stop();
}

#[test]
fn a_changed_config_version_on_a_location_answer_brings_the_new_plan() {
    // v1.9: the plan can change without a move (an admin edits a region); the version on the
    // /location answer says so
    let fake = Fake::start();
    fake.st().heartbeat_s = 3600.0;
    fake.st().plan_by_location = true;
    let (seed, r) = sign_in(&fake, "ned", &SkewClock::new());
    let dev = r.unwrap().1["device_id"].as_str().unwrap().to_string();
    let dongle = Dongle::new();
    let (a, _dir) = start(&fake, &seed, &dev, &dongle, Mode::Voice);
    a.radio_attached(FD);
    assert!(wait_for(10.0, || a.listening()), "{}", a.status());
    let mut first = fix(34.85, -82.39);
    first.ts -= 130.0;
    a.add_fix(first);
    assert!(wait_for(5.0, || a.status()["plan"]["config_version"] == "1.1"), "{}", a.status()["plan"]);
    // the plan is edited on the headend; the phone stays put and sends its 120 s keep-alive
    fake.st().config_version = "2".into();
    a.add_fix(fix(34.85, -82.39));
    assert!(wait_for(5.0, || a.status()["plan"]["config_version"] == "2.1"), "{}", a.status()["plan"]);
    a.stop();
}

#[test]
fn status_never_waits_on_the_radio_while_events_arrive() {
    // The app asks for status every second while the radio hands events over; the two once
    // deadlocked (status held the fix track while asking the radio, the radio held its lock
    // while an event took the track), freezing the app on a black screen.
    let fake = Fake::start();
    let (seed, r) = sign_in(&fake, "erin", &SkewClock::new());
    let dev = r.unwrap().1["device_id"].as_str().unwrap().to_string();
    let dongle = Dongle::new();
    let (a, _dir) = start(&fake, &seed, &dev, &dongle, Mode::Eot);
    a.add_fix(fix(34.85, -82.39));
    a.radio_attached(FD);
    assert!(wait_for(10.0, || a.status()["listening"] == true), "{}", a.status());
    let t0 = railspotting_common::now() + 0.3;
    for i in 0..12 {
        dongle.world.eot_burst(EOT, 30.0, t0 + i as f64 * 0.4, &[railspotting_dsp::eot::build_payload(41234, 80 + i)]);
    }
    let calls = Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let stop = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let workers: Vec<_> = (0..3).map(|_| {
        let (a, calls, stop) = (a.clone(), calls.clone(), stop.clone());
        std::thread::spawn(move || {
            while !stop.load(std::sync::atomic::Ordering::SeqCst) {
                let _ = a.status();
                a.add_fix(fix(34.85, -82.39));
                calls.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            }
        })
    }).collect();
    // status keeps answering through all the bursts
    let end = railspotting_common::now() + 8.0;
    let mut last = 0;
    while railspotting_common::now() < end {
        std::thread::sleep(std::time::Duration::from_millis(1000));
        let n = calls.load(std::sync::atomic::Ordering::SeqCst);
        assert!(n > last, "status stopped answering (deadlock)");
        last = n;
    }
    stop.store(true, std::sync::atomic::Ordering::SeqCst);
    for w in workers {
        w.join().unwrap();
    }
    assert!(fake.st().events.iter().any(|e| e["state"] == "closed"));
    a.stop();
}

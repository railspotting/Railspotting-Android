//! End-of-train alerts from other receivers near the phone, from the public live map's feed
//! (`/api/public/regions` and `/api/public/state?region=`, what railspotting.com's map shows).
//!
//! While listening, the app asks every minute. The regions within the radius are fetched, and
//! any EOT alert there that is new, recent and within the radius of the phone (by its crossing,
//! else its receiver's public position) comes back once. The first look at a region only notes
//! what is there, so starting to listen does not replay the last half hour.
//!
//! The phone's position never leaves the phone: the radius is checked here. The phone's own
//! events are never in the public feed (contract v1.7, 6); the app alerts on those itself.

use crate::location::{distance_m, Fix};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use std::time::Duration;

pub const SITE: &str = "https://railspotting.com";
/// Alerts older than this are not news.
pub const MAX_AGE_S: f64 = 1800.0;
/// The region list (names, bounds) rarely changes.
pub const REGIONS_EVERY_S: f64 = 6.0 * 3600.0;

/// GETs a JSON document by path from the site.
pub type Fetch = Arc<dyn Fn(&str) -> Result<Value, String> + Send + Sync>;

/// The real site, with a short timeout.
pub fn site_fetch(base: &str) -> Fetch {
    let base = base.trim_end_matches('/').to_string();
    let agent: ureq::Agent = ureq::Agent::config_builder()
        .timeout_global(Some(Duration::from_secs(15)))
        .user_agent("railspotting-android")
        .build()
        .into();
    Arc::new(move |path: &str| {
        let mut resp = agent.get(&format!("{base}{path}")).call().map_err(|e| e.to_string())?;
        resp.body_mut().read_json::<Value>().map_err(|e| e.to_string())
    })
}

fn point(lat: f64, lon: f64) -> Fix {
    Fix { lat, lon, accuracy_m: 0.0, ts: 0.0, speed_mps: None, bearing: None }
}

/// Distance in km from a point to a region's bounds `[south, west, north, east]` (0 inside).
pub fn km_to_bounds(lat: f64, lon: f64, b: &[f64]) -> Option<f64> {
    let [s, w, n, e] = <[f64; 4]>::try_from(b).ok()?;
    let clat = lat.clamp(s.min(n), s.max(n));
    let clon = lon.clamp(w.min(e), w.max(e));
    Some(distance_m(&point(lat, lon), &point(clat, clon)) / 1000.0)
}

/// The slugs of the regions within `radius_km` of the phone.
pub fn regions_near(regions: &Value, lat: f64, lon: f64, radius_km: f64) -> Vec<String> {
    regions.get("regions").and_then(Value::as_array).into_iter().flatten().filter_map(|r| {
        let b: Vec<f64> = r.get("bounds")?.as_array()?.iter().filter_map(Value::as_f64).collect();
        let slug = r.get("slug")?.as_str()?;
        (km_to_bounds(lat, lon, &b)? <= radius_km).then(|| slug.to_string())
    }).collect()
}

fn parse_ts(s: &str) -> Option<f64> {
    // RFC 3339 in UTC as the site writes it: 2026-10-13T21:00:19.18Z
    let s = s.strip_suffix('Z')?;
    let (date, time) = s.split_once('T')?;
    let mut d = date.split('-').map(|x| x.parse::<i64>());
    let (y, m, day) = (d.next()?.ok()?, d.next()?.ok()?, d.next()?.ok()?);
    let mut t = time.split(':');
    let (h, mi) = (t.next()?.parse::<f64>().ok()?, t.next()?.parse::<f64>().ok()?);
    let sec = t.next()?.parse::<f64>().ok()?;
    // days from the civil date (Howard Hinnant's algorithm)
    let y2 = if m <= 2 { y - 1 } else { y };
    let era = y2.div_euclid(400);
    let yoe = y2 - era * 400;
    let mp = (m + 9) % 12;
    let doy = (153 * mp + 2) / 5 + day - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146_097 + doe - 719_468;
    Some(days as f64 * 86_400.0 + h * 3600.0 + mi * 60.0 + sec)
}

/// The EOT alerts in one region's state within `radius_km` of the phone and newer than
/// `MAX_AGE_S`, each with where it was and how far.
pub fn eot_alerts(state: &Value, lat: f64, lon: f64, radius_km: f64, now: f64) -> Vec<Value> {
    let crossings: HashMap<&str, (f64, f64)> = state.get("crossings").and_then(Value::as_array).into_iter().flatten()
        .filter_map(|c| Some((c.get("id")?.as_str()?, (c.get("lat")?.as_f64()?, c.get("lon")?.as_f64()?))))
        .collect();
    let receivers: HashMap<i64, (f64, f64)> = state.get("receivers").and_then(Value::as_array).into_iter().flatten()
        .filter_map(|r| Some((r.get("id")?.as_i64()?, (r.get("lat")?.as_f64()?, r.get("lon")?.as_f64()?))))
        .collect();
    let here = point(lat, lon);
    state.get("alerts").and_then(Value::as_array).into_iter().flatten().filter_map(|a| {
        let eot = a.get("telemetry").and_then(Value::as_bool) == Some(true)
            || matches!(a.get("source").and_then(Value::as_str), Some("eot") | Some("telemetry"));
        if !eot {
            return None;
        }
        let ts = a.get("ts").and_then(Value::as_str).and_then(parse_ts)?;
        if now - ts > MAX_AGE_S {
            return None;
        }
        let at = a.get("crossing_id").and_then(Value::as_str).and_then(|id| crossings.get(id))
            .or_else(|| a.get("receiver_id").and_then(Value::as_i64).and_then(|id| receivers.get(&id)))?;
        let km = distance_m(&here, &point(at.0, at.1)) / 1000.0;
        (km <= radius_km).then(|| json!({
            "id": a.get("id"), "ts": ts, "title": a.get("title"), "message": a.get("message"),
            "crossing": a.get("crossing_name"), "receiver": a.get("receiver_name"),
            "railroad": a.get("railroad"), "distance_km": (km * 10.0).round() / 10.0,
        }))
    }).collect()
}

/// The public map's crossing statuses, most urgent first, with the site's own wording.
pub const STATUSES: [(&str, &str); 8] = [
    ("nearby", "Moving train"),
    ("recent", "Train within 15 min"),
    ("earlier", "Train within 30 min"),
    ("past", "Train within 60 min"),
    ("activity", "Crew radio"),
    ("quiet", "Quiet"),
    ("offline", "No receiver online"),
    ("unmonitored", "Not monitored"),
];

fn status_rank(s: &str) -> usize {
    STATUSES.iter().position(|(k, _)| *k == s).unwrap_or(STATUSES.len())
}

/// The crossings in the states within `radius_km` of the phone: train activity first, then the
/// nearest; at most `limit`. Each once, though regions overlap.
pub fn crossings_near(states: &[Value], lat: f64, lon: f64, radius_km: f64, limit: usize) -> Vec<Value> {
    let here = point(lat, lon);
    let mut seen = HashSet::new();
    let mut out: Vec<(usize, f64, Value)> = vec![];
    for state in states {
        for c in state.get("crossings").and_then(Value::as_array).into_iter().flatten() {
            let (Some(id), Some(clat), Some(clon)) = (c.get("id").and_then(Value::as_str), c.get("lat").and_then(Value::as_f64),
                                                       c.get("lon").and_then(Value::as_f64)) else { continue };
            if !seen.insert(id.to_string()) {
                continue;
            }
            let km = distance_m(&here, &point(clat, clon)) / 1000.0;
            if km > radius_km {
                continue;
            }
            let status = c.get("status").and_then(Value::as_str).unwrap_or("quiet");
            let label = STATUSES.iter().find(|(k, _)| *k == status).map_or(status, |(_, l)| *l);
            out.push((status_rank(status), km, json!({
                "id": id, "street": c.get("street"), "city": c.get("city"), "railroad": c.get("railroad"),
                "lat": clat, "lon": clon, "status": status, "label": label, "status_at": c.get("status_at"),
                "distance_km": (km * 10.0).round() / 10.0,
            })));
        }
    }
    out.sort_by(|a, b| a.0.cmp(&b.0).then(a.1.total_cmp(&b.1)));
    out.into_iter().take(limit).map(|(_, _, v)| v).collect()
}

/// A railroad line: (railroad, subdivision).
type LineKey = (String, String);

/// Where a train report can be placed (proposed v1.14): the lines near the phone (railroad and
/// subdivision, from the crossings within `radius_km`), each with its crossings, nearest first.
pub fn places_near(states: &[Value], lat: f64, lon: f64, radius_km: f64) -> Value {
    let here = point(lat, lon);
    let mut seen = HashSet::new();
    let mut lines: Vec<(LineKey, Vec<(f64, Value)>)> = vec![];
    for state in states {
        for c in state.get("crossings").and_then(Value::as_array).into_iter().flatten() {
            let (Some(id), Some(clat), Some(clon)) = (c.get("id").and_then(Value::as_str), c.get("lat").and_then(Value::as_f64),
                                                       c.get("lon").and_then(Value::as_f64)) else { continue };
            if !seen.insert(id.to_string()) {
                continue;
            }
            let km = distance_m(&here, &point(clat, clon)) / 1000.0;
            if km > radius_km {
                continue;
            }
            let text = |k: &str| c.get(k).and_then(Value::as_str).unwrap_or("").trim().to_string();
            let key = (text("railroad"), text("subdivision"));
            let crossing = json!({"id": id, "street": text("street"), "city": text("city"), "milepost": text("milepost"),
                                  "distance_km": (km * 1000.0).round() / 1000.0});
            match lines.iter_mut().find(|(k, _)| *k == key) {
                Some((_, list)) => list.push((km, crossing)),
                None => lines.push((key, vec![(km, crossing)])),
            }
        }
    }
    for (_, list) in &mut lines {
        list.sort_by(|a, b| a.0.total_cmp(&b.0));
    }
    lines.sort_by(|a, b| a.1[0].0.total_cmp(&b.1[0].0));
    Value::Array(lines.into_iter().map(|((railroad, subdivision), list)| json!({
        "railroad": railroad, "subdivision": subdivision,
        "crossings": list.into_iter().map(|(_, c)| c).collect::<Vec<_>>(),
    })).collect())
}

/// A region's state is fetched at most this often (the car screen and the alert check share it).
pub const STATE_FRESH_S: f64 = 30.0;

/// What has been seen, between checks.
pub struct Watcher {
    fetch: Fetch,
    regions: Option<(Value, f64)>,
    states: HashMap<String, (Value, f64)>,
    primed: HashSet<String>,
    seen: HashSet<i64>,
}

impl Watcher {
    pub fn new(fetch: Fetch) -> Watcher {
        Watcher { fetch, regions: None, states: HashMap::new(), primed: HashSet::new(), seen: HashSet::new() }
    }

    fn near(&mut self, lat: f64, lon: f64, radius_km: f64, now: f64) -> Result<Vec<String>, String> {
        let stale = self.regions.as_ref().is_none_or(|(_, at)| now - at > REGIONS_EVERY_S);
        if stale {
            let r = (self.fetch)("/api/public/regions")?;
            self.regions = Some((r, now));
        }
        Ok(self.regions.as_ref().map(|(r, _)| regions_near(r, lat, lon, radius_km)).unwrap_or_default())
    }

    fn state(&mut self, slug: &str, now: f64) -> Result<Value, String> {
        if let Some((s, at)) = self.states.get(slug) {
            if now - at < STATE_FRESH_S {
                return Ok(s.clone());
            }
        }
        let s = (self.fetch)(&format!("/api/public/state?region={slug}"))?;
        self.states.insert(slug.to_string(), (s.clone(), now));
        Ok(s)
    }

    /// The crossings within `radius_km` of the phone, train activity first, then the nearest.
    pub fn crossings(&mut self, lat: f64, lon: f64, radius_km: f64, limit: usize, now: f64) -> Result<Vec<Value>, String> {
        let mut states = vec![];
        for slug in self.near(lat, lon, radius_km, now)? {
            match self.state(&slug, now) {
                Ok(s) => states.push(s),
                Err(e) => log::debug!("live map state for {slug}: {e}"),
            }
        }
        Ok(crossings_near(&states, lat, lon, radius_km, limit))
    }

    /// The lines and crossings near the phone for a train report (`places_near`).
    pub fn places(&mut self, lat: f64, lon: f64, radius_km: f64, now: f64) -> Result<Value, String> {
        let mut states = vec![];
        for slug in self.near(lat, lon, radius_km, now)? {
            match self.state(&slug, now) {
                Ok(s) => states.push(s),
                Err(e) => log::debug!("live map state for {slug}: {e}"),
            }
        }
        Ok(places_near(&states, lat, lon, radius_km))
    }

    /// New EOT alerts within `radius_km` of the phone, nearest first.
    pub fn check(&mut self, lat: f64, lon: f64, radius_km: f64, now: f64) -> Result<Vec<Value>, String> {
        let near = self.near(lat, lon, radius_km, now)?;
        let mut out = vec![];
        for slug in near {
            let state = match self.state(&slug, now) {
                Ok(s) => s,
                Err(e) => {
                    log::debug!("live map state for {slug}: {e}");
                    continue;
                }
            };
            let all = eot_alerts(&state, lat, lon, f64::INFINITY, now);
            let first = self.primed.insert(slug);
            for a in all {
                let Some(id) = a.get("id").and_then(Value::as_i64) else { continue };
                // the same alert can be in two overlapping regions
                if !self.seen.insert(id) || first {
                    continue;
                }
                if a.get("distance_km").and_then(Value::as_f64).is_some_and(|d| d <= radius_km) {
                    out.push(a);
                }
            }
        }
        out.sort_by(|a, b| a["distance_km"].as_f64().partial_cmp(&b["distance_km"].as_f64()).unwrap_or(std::cmp::Ordering::Equal));
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    const GVL: (f64, f64) = (34.85, -82.39);

    fn regions() -> Value {
        json!({"regions": [
            {"slug": "ns-gvl", "bounds": [34.7, -82.6, 35.1, -81.95]},
            {"slug": "ns-clt", "bounds": [35.05, -81.05, 35.45, -80.55]},
            {"slug": "far", "bounds": [40.0, -75.0, 40.5, -74.5]},
        ]})
    }

    fn state(alerts: Value) -> Value {
        json!({
            "crossings": [{"id": "900001A", "lat": 34.90, "lon": -82.30}, {"id": "FAR1", "lat": 35.30, "lon": -80.80}],
            "receivers": [{"id": 4061, "lat": 34.86, "lon": -82.41}],
            "alerts": alerts,
        })
    }

    fn alert(id: i64, ts: &str, crossing: Option<&str>, telemetry: bool) -> Value {
        json!({"id": id, "ts": ts, "kind": "train", "title": "Train near Maple Ave", "message": "End-of-train signal heard",
               "telemetry": telemetry, "source": if telemetry { "eot" } else { "voice" }, "crossing_id": crossing,
               "crossing_name": "Elm St", "receiver_id": 4061, "receiver_name": "Maple Ave Siding"})
    }

    #[test]
    fn times_parse_as_the_site_writes_them() {
        assert_eq!(parse_ts("1970-01-01T00:00:00Z"), Some(0.0));
        let t = parse_ts("2026-10-13T21:00:19.18Z").unwrap();
        assert!((t - 1_791_925_219.18).abs() < 1e-6, "{t}");
        assert_eq!(parse_ts("2026-10-13 21:00"), None);
    }

    #[test]
    fn regions_within_the_radius() {
        // 80 km (50 miles) from Greenville: its region, not Charlotte's (about 140 km) nor far ones
        assert_eq!(regions_near(&regions(), GVL.0, GVL.1, 80.5), vec!["ns-gvl"]);
        assert_eq!(regions_near(&regions(), GVL.0, GVL.1, 200.0), vec!["ns-gvl", "ns-clt"]);
        assert_eq!(km_to_bounds(34.9, -82.3, &[34.7, -82.6, 35.1, -81.95]), Some(0.0));
    }

    #[test]
    fn eot_alerts_near_the_phone_only() {
        let now = parse_ts("2026-10-13T21:05:00Z").unwrap();
        let s = state(json!([
            alert(1, "2026-10-13T21:00:19.18Z", Some("900001A"), true),
            alert(2, "2026-10-13T21:01:00Z", Some("900001A"), false),   // voice: not EOT
            alert(3, "2026-10-13T20:00:00Z", Some("900001A"), true),    // an hour old
            alert(4, "2026-10-13T21:02:00Z", Some("FAR1"), true),       // Charlotte
            alert(5, "2026-10-13T21:03:00Z", Some("UNKNOWN"), true),    // the receiver's position
        ]));
        let got = eot_alerts(&s, GVL.0, GVL.1, 80.5, now);
        let ids: Vec<i64> = got.iter().map(|a| a["id"].as_i64().unwrap()).collect();
        assert_eq!(ids, vec![1, 5]);
        let km = got[0]["distance_km"].as_f64().unwrap();
        assert!((9.0..12.0).contains(&km), "{km}");
        assert_eq!(got[0]["crossing"], "Elm St");
    }

    #[test]
    fn crossings_by_urgency_then_distance() {
        let s1 = json!({"crossings": [
            {"id": "A", "street": "Far Rd", "railroad": "NS", "lat": 34.95, "lon": -82.30, "status": "quiet"},
            {"id": "B", "street": "Near Rd", "railroad": "NS", "lat": 34.851, "lon": -82.391, "status": "activity"},
            {"id": "C", "street": "Train St", "railroad": "CSX", "lat": 34.90, "lon": -82.35, "status": "nearby"},
            {"id": "D", "street": "Way Off", "railroad": "NS", "lat": 36.5, "lon": -80.0, "status": "nearby"},
        ]});
        // the same crossing in an overlapping region counts once
        let s2 = json!({"crossings": [{"id": "C", "lat": 34.90, "lon": -82.35, "status": "nearby"}]});
        let got = crossings_near(&[s1, s2], GVL.0, GVL.1, 80.5, 10);
        let ids: Vec<&str> = got.iter().map(|c| c["id"].as_str().unwrap()).collect();
        assert_eq!(ids, vec!["C", "B", "A"]);
        assert_eq!(got[0]["label"], "Moving train");
        assert_eq!(got[1]["distance_km"], 0.1);
        assert_eq!(crossings_near(&[got_state()], GVL.0, GVL.1, 80.5, 1).len(), 1);
    }

    fn got_state() -> Value {
        json!({"crossings": [{"id": "X", "lat": 34.85, "lon": -82.39}, {"id": "Y", "lat": 34.86, "lon": -82.39}]})
    }

    #[test]
    fn states_are_shared_for_half_a_minute() {
        let calls = Arc::new(Mutex::new(0));
        let c2 = calls.clone();
        let fetch: Fetch = Arc::new(move |path: &str| {
            if path != "/api/public/regions" {
                *c2.lock().unwrap() += 1;
            }
            Ok(if path == "/api/public/regions" { regions() } else { state(json!([])) })
        });
        let mut w = Watcher::new(fetch);
        w.crossings(GVL.0, GVL.1, 80.5, 6, 1000.0).unwrap();
        w.check(GVL.0, GVL.1, 80.5, 1010.0).unwrap();
        assert_eq!(*calls.lock().unwrap(), 1);
        w.crossings(GVL.0, GVL.1, 80.5, 6, 1031.0).unwrap();
        assert_eq!(*calls.lock().unwrap(), 2);
    }

    #[test]
    fn each_alert_once_and_not_the_backlog() {
        let now = parse_ts("2026-10-13T21:05:00Z").unwrap();
        let feed = Arc::new(Mutex::new(state(json!([alert(1, "2026-10-13T21:00:00Z", Some("900001A"), true)]))));
        let calls = Arc::new(Mutex::new(vec![]));
        let (f2, c2) = (feed.clone(), calls.clone());
        let fetch: Fetch = Arc::new(move |path: &str| {
            c2.lock().unwrap().push(path.to_string());
            Ok(if path == "/api/public/regions" { regions() } else { f2.lock().unwrap().clone() })
        });
        let mut w = Watcher::new(fetch);
        // the first look notes what is there
        assert!(w.check(GVL.0, GVL.1, 80.5, now).unwrap().is_empty());
        // a new EOT alert comes once
        feed.lock().unwrap()["alerts"].as_array_mut().unwrap().push(alert(7, "2026-10-13T21:04:00Z", Some("900001A"), true));
        let got = w.check(GVL.0, GVL.1, 80.5, now + 60.0).unwrap();
        assert_eq!(got.len(), 1);
        assert_eq!(got[0]["id"], 7);
        assert!(w.check(GVL.0, GVL.1, 80.5, now + 120.0).unwrap().is_empty());
        // only the near region is fetched, and the region list is not fetched every time
        let calls = calls.lock().unwrap();
        assert_eq!(calls.iter().filter(|p| p.as_str() == "/api/public/regions").count(), 1);
        assert!(calls.iter().all(|p| !p.contains("ns-clt")));
    }

    #[test]
    fn report_places_by_line_nearest_first() {
        let state = json!({"crossings": [
            {"id": "900001A", "railroad": "NS", "subdivision": "Main Line", "street": "Elm St", "city": "Springfield", "milepost": "12.3", "lat": 34.852, "lon": -82.394},
            {"id": "900002B", "railroad": "NS", "subdivision": "Main Line", "street": "Oak St", "city": "Springfield", "milepost": "12.8", "lat": 34.856, "lon": -82.394},
            {"id": "900003C", "railroad": "CSX", "subdivision": "River Sub", "street": "Mill Rd", "city": "Springfield", "milepost": "4.1", "lat": 34.853, "lon": -82.398},
            {"id": "900004D", "railroad": "CSX", "subdivision": "River Sub", "street": "Far Rd", "city": "Springfield", "lat": 34.90, "lon": -82.39},
        ]});
        // half a mile from 34.851, -82.394
        let got = places_near(&[state.clone(), state], 34.851, -82.394, 0.8047);
        let lines = got.as_array().unwrap();
        assert_eq!(lines.len(), 2);
        assert_eq!((lines[0]["railroad"].as_str(), lines[0]["subdivision"].as_str()), (Some("NS"), Some("Main Line")));
        let ids: Vec<&str> = lines[0]["crossings"].as_array().unwrap().iter().map(|c| c["id"].as_str().unwrap()).collect();
        assert_eq!(ids, vec!["900001A", "900002B"], "nearest first, each once although two regions list them");
        assert_eq!(lines[1]["crossings"].as_array().unwrap().len(), 1, "the far crossing is outside half a mile");
        assert_eq!(lines[0]["crossings"][0]["milepost"], "12.3");
        assert!(lines[0]["crossings"][0]["distance_km"].as_f64().unwrap() < 0.2);
    }
}

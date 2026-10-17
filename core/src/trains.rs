//! Reporting a train by hand (proposed contract v1.13): the owner saw a train and tells the
//! headend, with the details the website's "Add detail" form takes (locomotives, cars, notes, a
//! video or photo link), optionally on a train the headend already knows near the phone.
//!
//! These calls work whether or not the phone is listening: they need only the phone's key and
//! device ID, so a signed-in owner can report at any time. The headend offers them when its
//! `/config` has `mobile.train_reports: true`.

use railspotting_proto::api::{ApiClient, ApiError};
use serde_json::{json, Map, Value};

/// The website's limits for a train note (headend `app.AddTrainNote`).
pub const MAX_LOCOMOTIVES: usize = 200;
pub const MAX_BODY: usize = 1000;
pub const MAX_LINK: usize = 600;
pub const MAX_CARS: i64 = 500;
/// How far around the phone to look for trains, and how far back.
pub const NEARBY_KM: f64 = 10.0;

/// Whether the headend takes train reports (its cached /config says so).
pub fn enabled(cfg: &Value) -> bool {
    cfg.get("mobile").and_then(|m| m.get("train_reports")).and_then(Value::as_bool) == Some(true)
}

/// GET /trains/nearby: the trains the headend knows near the phone, nearest first.
/// `{"ok": true, "trains": [...]}` or an error object.
pub fn nearby(api: &ApiClient, lat: f64, lon: f64) -> Value {
    let query = format!("lat={lat:.5}&lon={lon:.5}&radius_km={NEARBY_KM}");
    match api.request("GET", "/trains/nearby", None, true, &[], &query, true).and_then(|r| r.json()) {
        Ok(v) => json!({"ok": true, "trains": v.get("trains").cloned().unwrap_or_else(|| json!([]))}),
        Err(e) => error_json(&e),
    }
}

/// The report as the headend takes it, from what the owner entered and where the phone is.
/// Checks the website's rules first, so the owner hears about a mistake at once.
pub fn sighting_body(input: &Value, fix: &Value, now: f64) -> Result<Value, String> {
    let text = |k: &str| input.get(k).and_then(Value::as_str).map(str::trim).unwrap_or("").to_string();
    let locomotives = text("locomotives");
    let body = text("body");
    let link = text("link");
    let cars = match input.get("cars") {
        None | Some(Value::Null) => None,
        Some(Value::String(s)) if s.trim().is_empty() => None,
        Some(v) => {
            let n = v.as_i64().or_else(|| v.as_str().and_then(|s| s.trim().parse().ok()))
                .ok_or("Cars must be a whole number")?;
            if !(0..=MAX_CARS).contains(&n) {
                return Err(format!("Cars must be between 0 and {MAX_CARS}"));
            }
            Some(n)
        }
    };
    if locomotives.chars().count() > MAX_LOCOMOTIVES {
        return Err(format!("Locomotives: at most {MAX_LOCOMOTIVES} characters"));
    }
    if locomotives.contains('\n') {
        return Err("Locomotives: one line".into());
    }
    if body.chars().count() > MAX_BODY {
        return Err(format!("Notes: at most {MAX_BODY} characters"));
    }
    if link.chars().count() > MAX_LINK {
        return Err(format!("Link: at most {MAX_LINK} characters"));
    }
    if !link.is_empty() && !media_link(&link) {
        return Err("Link: a YouTube or Google Photos link".into());
    }
    if locomotives.is_empty() && body.is_empty() && link.is_empty() && cars.is_none() {
        return Err("Add locomotives, cars, a note or a link".into());
    }
    let lat = fix.get("lat").and_then(Value::as_f64).ok_or("The phone's position is not known yet")?;
    let lon = fix.get("lon").and_then(Value::as_f64).ok_or("The phone's position is not known yet")?;
    let mut o = Map::new();
    o.insert("ts".into(), json!(now));
    o.insert("lat".into(), json!(lat));
    o.insert("lon".into(), json!(lon));
    if let Some(a) = fix.get("accuracy_m").and_then(Value::as_f64) {
        o.insert("accuracy_m".into(), json!(a));
    }
    if let Some(id) = input.get("train_id").and_then(Value::as_i64).filter(|id| *id > 0) {
        o.insert("train_id".into(), json!(id));
    }
    for (k, v) in [("locomotives", locomotives), ("body", body), ("link", link)] {
        if !v.is_empty() {
            o.insert(k.into(), json!(v));
        }
    }
    if let Some(n) = cars {
        o.insert("cars".into(), json!(n));
    }
    Ok(Value::Object(o))
}

/// The links the website accepts: YouTube and Google Photos (the headend checks them fully).
fn media_link(link: &str) -> bool {
    let Some(rest) = link.strip_prefix("https://") else { return false };
    let host = rest.split(['/', '?']).next().unwrap_or("");
    ["youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "photos.app.goo.gl", "photos.google.com"]
        .contains(&host)
}

/// POST /sightings. `{"ok": true, ...the headend's answer}` or an error object.
pub fn report(api: &ApiClient, body: &Value) -> Value {
    match api.request("POST", "/sightings", Some(body), true, &[], "", true).and_then(|r| r.json()) {
        Ok(v) => {
            let mut out = json!({"ok": true});
            if let Value::Object(m) = v {
                for (k, x) in m {
                    out[k] = x;
                }
            }
            out
        }
        Err(e) => error_json(&e),
    }
}

/// An API error for the app, in words for the owner where the cause is known.
fn error_json(e: &ApiError) -> Value {
    let message = match e.status {
        Some(404) => "RailSpotting doesn't take train reports yet".to_string(),
        Some(429) => "Too many reports today; try again later".to_string(),
        Some(401) | Some(403) => "Sign in again to report trains".to_string(),
        _ if e.message.is_empty() => e.to_string(),
        _ => e.message.clone(),
    };
    json!({"ok": false, "error": e.error, "status": e.status, "message": message})
}

#[cfg(test)]
mod tests {
    use super::*;

    const AT: f64 = 1_792_000_000.0;
    fn fix() -> Value {
        json!({"lat": 34.85, "lon": -82.39, "accuracy_m": 8.0, "ts": AT})
    }

    #[test]
    fn a_report_with_a_train() {
        let b = sighting_body(&json!({"train_id": 812, "locomotives": " NS 4321, NS 1234 ", "cars": "88",
                                      "body": "Heading north", "link": ""}), &fix(), AT).unwrap();
        assert_eq!(b, json!({"ts": AT, "lat": 34.85, "lon": -82.39, "accuracy_m": 8.0, "train_id": 812,
                             "locomotives": "NS 4321, NS 1234", "body": "Heading north", "cars": 88}));
    }

    #[test]
    fn a_report_without_a_train() {
        let b = sighting_body(&json!({"body": "Long manifest", "train_id": null}), &fix(), AT).unwrap();
        assert!(b.get("train_id").is_none() && b["body"] == "Long manifest");
    }

    #[test]
    fn the_websites_rules() {
        let bad = |input: Value| sighting_body(&input, &fix(), AT).unwrap_err();
        assert_eq!(bad(json!({})), "Add locomotives, cars, a note or a link");
        assert_eq!(bad(json!({"cars": "501"})), "Cars must be between 0 and 500");
        assert_eq!(bad(json!({"cars": "lots"})), "Cars must be a whole number");
        assert!(bad(json!({"body": "x".repeat(1001)})).starts_with("Notes"));
        assert!(bad(json!({"locomotives": "a\nb"})).contains("one line"));
        assert!(bad(json!({"link": "https://example.com/video"})).contains("YouTube"));
        assert!(sighting_body(&json!({"link": "https://youtu.be/dQw4w9WgXcQ"}), &fix(), AT).is_ok());
        assert!(sighting_body(&json!({"cars": 0}), &fix(), AT).is_ok(), "zero cars is a detail");
        assert_eq!(sighting_body(&json!({"body": "x"}), &json!({}), AT).unwrap_err(), "The phone's position is not known yet");
    }

    #[test]
    fn offered_only_when_the_headend_says_so() {
        assert!(enabled(&json!({"mobile": {"train_reports": true}})));
        assert!(!enabled(&json!({"mobile": {}})));
        assert!(!enabled(&json!({})));
    }
}

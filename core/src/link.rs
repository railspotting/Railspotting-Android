//! Signing in (contract v1.7 section 1): the sign-in nonce, linking with a Google ID token, and
//! the phone's clock.
//!
//! Requests are signed by the receiver's own `proto` crate, so the canonical string and headers
//! are the same as every fixed receiver's.

use railspotting_common::{now, Clock};
use railspotting_proto::api::{ApiClient, ApiError};
use railspotting_proto::keys::{self, PrivateKey as SigningKey};
use serde_json::{json, Value};
use std::sync::{Arc, Mutex};

pub const SERVER_URL: &str = "https://api.railspotting.com";

/// The phone's clock, corrected by the server's (`X-RS-Server-Time`). Every signed request and
/// every event timestamp reads it.
#[derive(Default)]
pub struct SkewClock {
    correction: Mutex<f64>,
}

/// Corrections smaller than this are noise (the server's time is whole seconds).
const MIN_CORRECTION_S: f64 = 2.0;

impl SkewClock {
    pub fn new() -> Arc<SkewClock> {
        Arc::new(SkewClock::default())
    }

    pub fn clock(self: &Arc<Self>) -> Clock {
        let me = self.clone();
        Arc::new(move || now() + me.correction())
    }

    pub fn correction(&self) -> f64 {
        *self.correction.lock().unwrap_or_else(|p| p.into_inner())
    }

    /// Take the offset the client learned from the last answer. The client measures it against
    /// this clock, so what it holds is what is still off.
    pub fn absorb(&self, api: &ApiClient) {
        let Some(off) = api.offset() else { return };
        if off.abs() < MIN_CORRECTION_S {
            return;
        }
        let total = {
            let mut c = self.correction.lock().unwrap_or_else(|p| p.into_inner());
            *c += off;
            *c
        };
        api.set_offset(Some(0.0));
        log::warn!("phone clock is {:+.0} s from the server; using the server's time", -total);
    }
}

/// A client for one key. `device_id` is None until linked.
pub fn client(server_url: &str, key: &SigningKey, device_id: Option<String>, clock: Clock) -> Result<ApiClient, ApiError> {
    ApiClient::simple(server_url, device_id, key.clone(), clock)
}

/// A new key pair: the 32-byte seed the app keeps encrypted.
pub fn new_seed() -> [u8; 32] {
    keys::generate_private().to_bytes()
}

pub fn key_from_seed(seed: &[u8]) -> Result<SigningKey, String> {
    keys::private_from_seed(seed)
}

pub fn public_key(seed: &[u8]) -> Result<String, String> {
    Ok(keys::public_b64(&key_from_seed(seed)?))
}

/// GET /mobile/hello: unsigned. The Google client ID, a sign-in nonce, the terms and privacy
/// pages.
pub fn hello(api: &ApiClient) -> Result<Value, ApiError> {
    api.request("GET", "/mobile/hello", None, false, &[], "", false)?.json()
}

/// What the phone says about itself when linking.
#[derive(Debug, Clone, Default)]
pub struct Device {
    pub model: String,
    pub android_version: String,
    pub app_version: String,
}

fn cut(s: &str, max: usize) -> String {
    s.trim().chars().take(max).collect()
}

/// The POST /mobile/link body, with each field cut to the contract's length.
pub fn link_body(id_token: &str, nonce: &str, public_key: &str, name: Option<&str>, device: &Device) -> Value {
    let mut body = json!({
        "id_token": id_token,
        "nonce": nonce,
        "public_key": public_key,
        "device": {"model": cut(&device.model, 100), "android_version": cut(&device.android_version, 20),
                   "app_version": cut(&device.app_version, 50)},
    });
    if let Some(n) = name.map(|n| cut(n, 80)).filter(|n| !n.is_empty()) {
        body["name"] = json!(n);
    }
    body
}

/// POST /mobile/link, signed with the key being linked and without X-RS-Device. Returns the
/// status (201 new, 200 moved to this phone) and the link result. A 409 (`email_taken`,
/// `key_in_use`) comes back as an error like the others.
pub fn link(api: &ApiClient, body: &Value) -> Result<(u16, Value), ApiError> {
    let resp = api.request("POST", "/mobile/link", Some(body), true, &[], "", false)?;
    let data = resp.json()?;
    if resp.status == 409 {
        let text = |k: &str| data.get(k).and_then(Value::as_str).unwrap_or("").to_string();
        return Err(ApiError::new(Some(409), &text("error"), &text("message"), None, data.as_object().cloned()));
    }
    let ok = data.get("device_id").and_then(Value::as_str).is_some_and(|d| !d.is_empty());
    if !ok {
        return Err(ApiError::new(Some(resp.status), "bad_response", "the link answer has no device_id", None, None));
    }
    Ok((resp.status, data))
}

/// An error as the app shows it.
pub fn error_json(e: &ApiError) -> Value {
    json!({"ok": false, "status": e.status, "error": e.error, "message": e.message, "retry_after": e.retry_after})
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn link_body_cuts_long_fields() {
        let d = Device { model: "M".repeat(150), android_version: "15".into(), app_version: "0.1.0".into() };
        let b = link_body("tok", "n1", "pk", Some(&"x".repeat(100)), &d);
        assert_eq!(b["device"]["model"].as_str().unwrap().len(), 100);
        assert_eq!(b["name"].as_str().unwrap().len(), 80);
        assert_eq!(b["device"]["android_version"], "15");
        // no name: the headend picks one
        assert!(link_body("tok", "n1", "pk", Some("  "), &d).get("name").is_none());
        assert!(link_body("tok", "n1", "pk", None, &d).get("name").is_none());
    }

    #[test]
    fn seeds_make_43_character_public_keys() {
        let s = new_seed();
        let pk = public_key(&s).unwrap();
        assert_eq!(pk.len(), 43);
        assert!(!pk.contains('='));
        assert_ne!(new_seed(), s);
        assert!(public_key(&[1, 2, 3]).is_err());
    }
}

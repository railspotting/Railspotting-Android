//! The headend's signing test vectors (internal/sig/sig_test.go and vectors.go in the headend
//! repository), checked against the signing this app uses: the receiver's proto crate.
//! The key is the contract's published test key; never use it outside tests.

use railspotting_proto::keys;

const SEED_HEX: &str = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

const V11_HEARTBEAT: &str = r#"{"ts":1791034500.0,"uptime_s":3600,"agent_version":"1.1.0","capabilities":{"receivers":[{"type":"scanner","vendor":"Uniden","product":"SR30C","firmware":"Version 1.01.04","connected":true,"role":"hot","bands_mhz":[[25,54],[108,174],[406,512]],"channels":[1,2,3,4,5,6,7,8,9,10],"simultaneous":false,"squelch_free":false,"audio":false},{"type":"sdr","vendor":"Nooelec","product":"NESDR SMArt v5","chipset":"RTL2832U","tuner":"R820T/R820T2","connected":true,"role":"vhf","window_mhz":[159.808,161.808],"channels":[1,2,3,4,5,6,7,8],"simultaneous":true,"squelch_free":true,"audio":true},{"type":"sdr","vendor":"Nooelec","product":"NESDR SMArt v5","chipset":"RTL2832U","tuner":"R820T/R820T2","connected":true,"role":"eot","window_mhz":[456.6375,458.6375],"channels":[9],"simultaneous":true,"squelch_free":true,"audio":true}],"features":{"simultaneous_voice":true,"continuous_eot":true,"continuous_hot":true,"transcription":false,"eot_decode":false}}}"#;

const V11_EVENTS: &str = r#"{"events":[{"event_id":"8d1c6a52-3f0e-4b7e-9a51-2c6f0f4be1d9","state":"closed","start_ts":1791034502.12,"end_ts":1791034516.40,"freq":160.95,"ch":5,"kind":"voice","peak_rssi":431,"avg_rssi":418,"samples":702,"transcript":{"text":"Norfolk Southern detector, milepost 476.2, track 1, no defects, total axles 312, train speed 46","engine":"vosk-small-en-us-0.15","confidence":0.86,"language":"en","duration_s":14.3,"parsed":{"type":"defect_detector","railroad":"NS","milepost":476.2,"track":1,"defects":false,"axles":312,"speed_mph":46}}}]}"#;

struct Vector {
    method: &'static str,
    path: &'static str,
    ts: i64,
    nonce: &'static str,
    body: &'static str,
    body_sha: &'static str,
    sig: &'static str,
}

const VECTORS: [Vector; 4] = [
    Vector { method: "GET", path: "/api/v1/config", ts: 1790865400, nonce: "00112233445566778899aabbccddeeff", body: "",
             body_sha: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
             sig: "eqMqmtPQE3ik3_dQwctlrHSr0A3j7L1w4popeK7xXdcGPYDsC2mEg-_18eHZpGpuWJZ81BTC8QiZqnlI9qPlBA" },
    Vector { method: "POST", path: "/api/v1/heartbeat", ts: 1790865400, nonce: "ffeeddccbbaa99887766554433221100",
             body: r#"{"ts":1790865400.0,"uptime_s":60}"#,
             body_sha: "3efb53f635a012593ad4867899c86df4d1d9354c132bcce020970e0751fb242d",
             sig: "viFzHVdL6s-yValMWLSAzKZdllCvOqj4UoiOjvzhUBtOjAZYFI7jZl8uIYobh38Tsh-TZjTf9Vbjl-c6o8JvCQ" },
    Vector { method: "POST", path: "/api/v1/heartbeat", ts: 1791034520, nonce: "0f1e2d3c4b5a69788796a5b4c3d2e1f0",
             body: V11_HEARTBEAT,
             body_sha: "ddcab1e8a7f7a15b58fbac8d9215f471840f91d3ee5b83af5f9d32a14a07d1aa",
             sig: "2RMhToso08cRIOFVS-f8rMxtEUPhzRzmS6Q7ELANYgUUJbW3gfCdZa8SCGNc7O4s8M_rufDFvF1uPgExxMXKBA" },
    Vector { method: "POST", path: "/api/v1/events", ts: 1791034520, nonce: "a0b1c2d3e4f5061728394a5b6c7d8e9f",
             body: V11_EVENTS,
             body_sha: "1597f4a93646093a148bb82511bf27383eab387ede1d893a7facb8af38fb49e9",
             sig: "KfFCC438m82NlEvgkh2ql41XCcV7HsoKFJ0ArLDRT46RZ5AmY4-bqRdSy0chVxm8gFWCs4CMn1kocO2jg1JLDw" },
];

fn seed() -> Vec<u8> {
    (0..SEED_HEX.len()).step_by(2).map(|i| u8::from_str_radix(&SEED_HEX[i..i + 2], 16).unwrap()).collect()
}

#[test]
fn public_key_and_canonical_string() {
    let key = keys::private_from_seed(&seed()).unwrap();
    assert_eq!(keys::public_b64(&key), "A6EHv_POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg");
    assert_eq!(railspotting_mobile::link::public_key(&seed()).unwrap(), "A6EHv_POEL4dcN0Y50vAmWfk1jCbpQ1fHdyGZBJVMbg");
    assert_eq!(keys::canonical_string("GET", "/api/v1/config", 1790865400, "00112233445566778899aabbccddeeff", b""),
               "GET\n/api/v1/config\n1790865400\n00112233445566778899aabbccddeeff\n\
                e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
}

#[test]
fn signatures_match_the_headend() {
    let key = keys::private_from_seed(&seed()).unwrap();
    let public = keys::public_bytes(&key);
    for v in VECTORS {
        assert_eq!(keys::sha256_hex(v.body.as_bytes()), v.body_sha, "{} body hash", v.path);
        assert_eq!(keys::sign(&key, v.method, v.path, v.ts, v.nonce, v.body.as_bytes()), v.sig, "{} signature", v.path);
        let canon = keys::canonical_string(v.method, v.path, v.ts, v.nonce, v.body.as_bytes());
        assert!(keys::verify(&public, &canon, v.sig));
        let changed = keys::canonical_string(v.method, &format!("{}?x=1", v.path), v.ts, v.nonce, v.body.as_bytes());
        assert!(!keys::verify(&public, &changed, v.sig), "{} verifies with a changed path", v.path);
    }
}

#[test]
fn signed_headers_carry_the_device_only_after_linking() {
    let key = keys::private_from_seed(&seed()).unwrap();
    let names = |d: Option<&str>| -> Vec<String> {
        keys::signed_headers(&key, d, "POST", "/api/v1/mobile/link", b"{}", 1790865400, None)
            .into_iter().map(|(k, _)| k).collect()
    };
    assert_eq!(names(None), vec!["X-RS-Timestamp", "X-RS-Nonce", "X-RS-Signature"]);
    assert_eq!(names(Some("rs-0a1b2c3d4e5f"))[0], "X-RS-Device");
    let nonce = keys::new_nonce();
    assert!(nonce.len() == 32 && nonce.chars().all(|c| c.is_ascii_digit() || ('a'..='f').contains(&c)));
}

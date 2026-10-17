//! The nearby EOT watcher against the real public live map feed. Not run by default (it needs
//! the network): `cargo test --test live_site -- --ignored --nocapture`.

use railspotting_mobile::nearby::{eot_alerts, regions_near, site_fetch, SITE};

#[test]
#[ignore]
fn reads_the_public_feed() {
    let fetch = site_fetch(SITE);
    let regions = fetch("/api/public/regions").expect("regions");
    // Greenville, SC, where receivers are
    let (lat, lon) = (34.85, -82.39);
    let near = regions_near(&regions, lat, lon, 80.5);
    assert!(!near.is_empty(), "no region within 50 miles of Greenville");
    let now = railspotting_common::now();
    for slug in near {
        let state = fetch(&format!("/api/public/state?region={slug}")).expect("state");
        assert!(state.get("alerts").is_some_and(|a| a.is_array()), "{slug}: no alerts list");
        let found = eot_alerts(&state, lat, lon, 80.5, now);
        println!("{slug}: {} EOT alerts in the last 30 min within 50 mi", found.len());
        for a in found.iter().take(3) {
            println!("  {}", a);
        }
    }
}

#[test]
#[ignore]
fn nearby_crossings_from_the_public_feed() {
    let mut w = railspotting_mobile::nearby::Watcher::new(site_fetch(SITE));
    let got = w.crossings(34.85, -82.39, 40.0, 6, railspotting_common::now()).expect("crossings");
    assert!(!got.is_empty(), "no crossings near Greenville");
    for c in &got {
        println!("{} {} ({}) {} km: {}", c["id"], c["street"], c["railroad"], c["distance_km"], c["label"]);
    }
}

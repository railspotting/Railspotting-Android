#!/usr/bin/env python3
"""Write the app's open source list (app/src/main/assets/open_source.json) for the About screen.

The Rust crates come from the Android build's own dependency tree (`cargo tree`), so the list
matches what ships. The driver libraries and the Android libraries are listed here. Run it after
changing dependencies:

    python3 tools/open_source.py
"""
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "open_source.json")

DRIVER = [
    {"name": "librtlsdr", "version": "2.0.3", "license": "GPL-2.0-or-later", "url": "https://gitea.osmocom.org/sdr/rtl-sdr",
     "text": "licenses/GPL-2.0.txt",
     "note": "With one added function (rtlsdr_open_sys_dev) to open a dongle from an Android USB descriptor. "
             "The patch and build script are in this app's source (native/)."},
    {"name": "libusb", "version": "1.0.30", "license": "LGPL-2.1-or-later", "url": "https://github.com/libusb/libusb",
     "text": "licenses/LGPL-2.1.txt"},
]

ANDROID = [
    {"name": "Kotlin standard library and coroutines", "license": "Apache-2.0", "url": "https://github.com/JetBrains/kotlin"},
    {"name": "AndroidX (Core, Activity, Lifecycle)", "license": "Apache-2.0", "url": "https://developer.android.com/jetpack/androidx"},
    {"name": "Jetpack Compose and Material 3", "license": "Apache-2.0", "url": "https://developer.android.com/jetpack/compose"},
    {"name": "AndroidX Credentials", "license": "Apache-2.0", "url": "https://developer.android.com/jetpack/androidx/releases/credentials"},
    {"name": "Google Identity (googleid)", "license": "Apache-2.0", "url": "https://developer.android.com/identity/sign-in/credential-manager-siwg"},
    {"name": "AndroidX Car App Library (Android Auto)", "license": "Apache-2.0", "url": "https://developer.android.com/jetpack/androidx/releases/car-app"},
    {"name": "AndroidX WebKit", "license": "Apache-2.0", "url": "https://developer.android.com/jetpack/androidx/releases/webkit"},
    {"name": "Google Play services location", "license": "Android Software Development Kit License",
     "url": "https://developers.google.com/android/guides/overview"},
]

RECEIVER = [
    {"name": "RailSpotting receiver", "license": "MIT OR Apache-2.0", "url": "https://github.com/railspotting/Railspotting-Receiver",
     "note": "Signing, the outbox, SDR processing and the EOT decoder."},
]


def rust_crates():
    out = subprocess.run(
        ["cargo", "tree", "-e", "normal", "--target", "aarch64-linux-android", "--prefix", "none",
         "--format", "{p}|{l}|{r}"],
        cwd=os.path.join(ROOT, "core"), check=True, capture_output=True, text=True).stdout
    crates = {}
    for line in out.splitlines():
        line = line.replace(" (*)", "").replace(" (proc-macro)", "").strip()
        if not line:
            continue
        pkg, lic, repo = (line.split("|") + ["", ""])[:3]
        name, _, version = pkg.partition(" v")
        if name.startswith("railspotting-"):
            continue
        url = repo or f"https://crates.io/crates/{name}"
        # a few crates give their GitHub address as plain http
        url = url.replace("http://github.com/", "https://github.com/")
        crates[(name, version)] = {"name": name, "version": version.split(" ")[0], "license": lic or "see source",
                                   "url": url}
    items = sorted(crates.values(), key=lambda c: (c["name"], c["version"]))
    # rusqlite's bundled build compiles SQLite itself into the app
    items.append({"name": "SQLite", "version": "", "license": "Public domain", "url": "https://sqlite.org/copyright.html"})
    return sorted(items, key=lambda c: c["name"].lower())


def main():
    doc = {
        "generated_by": "tools/open_source.py",
        "sections": [
            {"title": "Radio driver", "items": DRIVER},
            {"title": "RailSpotting", "items": RECEIVER},
            {"title": "Android libraries", "items": ANDROID},
            {"title": "Rust crates", "items": rust_crates()},
        ],
    }
    with open(OUT, "w") as f:
        json.dump(doc, f, indent=1)
        f.write("\n")
    print(f"wrote {OUT}: {sum(len(s['items']) for s in doc['sections'])} entries")


if __name__ == "__main__":
    sys.exit(main())

# Guide for AI coding agents

This file is for AI agents (Codex, Claude Code and others) working in this repository. `CLAUDE.md`
imports it. Read `README.md` first: what the app is, how it is built, and why it is Kotlin with a
Rust core.

## The project

The RailSpotting Android app: a phone as a **mobile receiver** (API contract v1.7). The contract,
the OpenAPI document and the server live in the private headend repository
(`railspotting/Railspotting-Headend`: `docs/contract-v1.7.md`, `docs/android-handoff.md`,
`web/api/receiver-openapi.json`, `internal/sig`, `internal/server/mobile_test.go`).

- `app/`: Kotlin (sign-in, Keystore, location, the foreground service, the dongle's USB
  permission, live listen audio, Compose screens).
- `core/`: Rust, the receiver itself, built on the `receiver/` submodule's crates.
- `native/`: the dongle driver, libusb and a patched librtlsdr (GPL), built with the NDK. See
  `native/README.md`. Keep them separate shared libraries, loaded at run time.
- `receiver/`: git submodule, `railspotting/Railspotting-Receiver`. Change it there, through its
  own pull requests, never in place here; then move the submodule pointer in this repository.

## Commands

```
cd core && cargo test                 # the core, with a fake headend and a simulated dongle
cd core && cargo clippy --all-targets
./gradlew testDebugUnitTest           # the app's JVM tests
./gradlew lintDebug assembleDebug     # builds core/ for each ABI first (cargo-ndk)
```

## Rules

- **Don't change the contract.** Build to it. If it blocks something, raise it with the
  owner and don't work around it.
- **Don't change what is detected or reported** without being asked. The radio processing is
  the receiver's (`dsp` and `engine`). Phones and fixed receivers must stay the same.
- **Privacy:**
  - Never log the key or an ID token.
  - Never back up the key: `allowBackup` is off and nothing is in the backup rules.
  - The position and the receiver name stay off anything public.
- **Battery:** nothing runs outside the listening service. GPS is on only while listening.
- **Dependencies:** keep them few and well maintained. Say why in the README before adding one.
- **Style:** match the surrounding code.
  - Short comments that explain why.
  - No em dashes.
  - Log lines are read by owners, so keep them plain.
- **Tests:** add or update a test for every behaviour change, in `core/tests` or `app/src/test`.
- **Releases:** `tools/release.sh patch "notes"`. Fixes and small features are patch releases
  (0.3.0 to 0.3.1); a minor or major step only when the owner asks for one. A release reaches
  every phone within a day and can't be taken back: publish only what has been tested.

## Repository hygiene

- **Never commit** keys, keystores, `keystore.properties`, `local.properties`, email addresses,
  device IDs, real locations or absolute local paths.
  - Tests use made-up values (`ann@example.com`, `rs-0a1b2c3d4e5f`, the contract's published test
    key).
  - Review `git diff --cached` before every commit.
- **Commit author:** commits use the repository's configured author with the GitHub noreply
  address. Don't add co-author or sign-off lines with email addresses.
- **Branches:** short-lived feature branches off `main`, merged once CI passes.

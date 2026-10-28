# Development and releases

[README](../README.md) · [Architecture](architecture.md) · [Changelog](../CHANGELOG.md)

Run commands from the repository root unless noted.

## Requirements

- Rust 1.75+ (stable)
- JDK 17 and Android SDK 35
- Python 3.11+ for the legacy Gateway reference test suite only

## Which verification a change needs

Pick the cheapest tier that covers the behaviour instead of defaulting to the whole workspace. Rust changes are isolated by **crate** (`cargo test -p <crate>`); Android changes are JVM unit tests only — there are no instrumented or UI tests in this repository, so an Android behaviour that needs a device has no automated coverage and must be verified by hand on an installed APK.

| Change | Targeted | Module | Full |
| --- | --- | --- | --- |
| `crates/cmux-pocket-protocol/**` (wire models) | `cargo test -p cmux-pocket-protocol --locked` | same crate; it has no dependents to skip, but every other crate consumes it — see the escalation list | `cargo test --workspace --locked` |
| `crates/cmux-pocket-macos/**` (paths, config, token, launchd, loopback) | `cargo test -p cmux-pocket-macos --locked` | same crate | `cargo test --workspace --locked` |
| `crates/cmux-pocket-cmux/**` | `cargo test -p cmux-pocket-cmux --locked` | same crate | `cargo test --workspace --locked` |
| `crates/cmux-pocket-gateway/**` | `cargo test -p cmux-pocket-gateway --locked` | same crate (each test spins a real loopback gateway on port 0) | `cargo test --workspace --locked` |
| `crates/cmux-pocket-cli/**` | `cargo test -p cmux-pocket-cli --locked` | same crate | `cargo test --workspace --locked`; CLI output shape changes also need `cargo run -p cmux-pocket-cli -- doctor --offline --json` |
| `android/app/src/main/**` | `cd android && ./gradlew testDebugUnitTest --no-daemon` | as targeted | plus `./gradlew assembleDebug --no-daemon` when resources, manifest, or Gradle config change |
| `android/app/src/test/**` | `cd android && ./gradlew testDebugUnitTest --no-daemon --tests '*<Class>'` | `testDebugUnitTest` | as targeted |
| `gateway/**` (legacy Python gateway) | `python3 gateway/test_gateway.py` | that file plus `gateway/test_replay_normalize.py` and `gateway/test_agent_completion_event.py` | `python3 tests/e2e_android_gateway_test.py` (needs `websockets` and free ports 8999/9050) |
| `docs/**`, `README.md`, `DESIGN.md`, `fastlane/metadata/**` | none | — | — |
| `Cargo.toml`, `Cargo.lock`, workspace dependencies | — | — | `cargo test --workspace --locked` (a dependency change invalidates every crate's build) |
| `android/build.gradle.kts`, `android/gradle/**`, `settings.gradle.kts` | — | — | `./gradlew testDebugUnitTest --no-daemon` plus an `assembleDebug` |
| `scripts/**`, `.github/workflows/**` | `./scripts/release-notes-from-changelog.sh <tag>` where applicable | — | release-candidate path (tag build) |

**Widen the scope when any of these hold:**

- `cmux-pocket-protocol` changes: four crates depend on it, so the crate's own tests are never enough — run the workspace.
- A public type crosses a crate boundary (protocol → cmux/gateway/cli), or a persistence key, config file layout, or launchd plist shape changes: run the workspace and, for config/launchd, `doctor --offline`.
- A version bump (`Cargo.toml` workspace version, Android `versionName`): run the workspace suite plus `testDebugUnitTest`, because the release workflows reject a mismatched version after the fact.
- A targeted run fails, is skipped, or cannot be reproduced: escalate to the workspace run and report the failing command.
- Anything touching TLS/listener setup, loopback token handling, or the reconnect path — run the workspace, then exercise the app against a real Gateway; only that shows the end-to-end behaviour.

**Measured cost on this machine** (arm64 macOS, 2026-10-28): from an empty `target/`, `cargo test -p cmux-pocket-protocol --locked` takes 24 s and `cargo test --workspace --locked` takes 144 s. With a warm `target/` the same two commands take 1.0 s and 13.8 s; the workspace run executes 262 tests, of which the assertions are ~20 s on a cold run — the rest is compilation. A crate-scoped run isolates the assertions and only the compile units that crate needs, so it is the cheapest real tier; it does not avoid compiling the crates it depends on.

**CI boundary:** no workflow runs on pull requests. `.github/workflows/release.yml` (tag `v*` and manual only) is the only place `./gradlew testDebugUnitTest assembleRelease` runs, and the macOS job only builds CLI archives. Therefore nothing here is verified remotely before merge: the local tiers above are the whole gate, and a green release workflow after tagging is not evidence that the PR was verified.

## Rust Gateway and CLI

```bash
# Unit and integration tests
cargo test --workspace --locked

# Offline CLI diagnostics
cargo run -p cmux-pocket-cli -- doctor --offline --json

# Release CLI binary
cargo build --release -p cmux-pocket-cli
```

## Android app

```bash
cd android

# Unit tests
./gradlew testDebugUnitTest

# Debug APK
./gradlew assembleDebug

# Release build daemons after use
./gradlew --stop
```

## Releases

Release bodies come from [CHANGELOG.md](../CHANGELOG.md). Before tagging a `v*` release:

1. Add a `## [X.Y.Z] - YYYY-MM-DD` changelog section.
2. Match the workspace version in `Cargo.toml` and Android `versionName` to the tag.

`scripts/release-notes-from-changelog.sh` extracts the notes. The APK workflow rejects a mismatched `versionName`, and the Homebrew tap rejects a tag whose `Cargo.toml` version differs.

The Gateway formula lives only in [shiquda/homebrew-cmux-pocket](https://github.com/shiquda/homebrew-cmux-pocket). This repository keeps no formula copy. The tap polls the latest published GitHub Release. A newer release rewrites the formula to the two macOS CLI archives only after both exist; a release without them, or a release that is not newer, leaves the formula unchanged. Publishing waits until the signed APK and both archives are uploaded, so the tap does not see a half release. Homebrew can still lag that publish by the tap poll plus a local `brew update`. `cmux-pocket upgrade` reports that mismatch instead of treating the old formula as current. The published 0.3.1 formula stays a source build. The archives are for Homebrew, not a signed download-and-open install.

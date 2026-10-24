# Development and releases

[README](../README.md) · [Architecture](architecture.md) · [Changelog](../CHANGELOG.md)

Run commands from the repository root unless noted.

## Requirements

- Rust 1.75+ (stable)
- JDK 17 and Android SDK 35
- Python 3.11+ for the legacy Gateway reference test suite only

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

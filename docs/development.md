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

The Gateway formula lives only in [shiquda/homebrew-cmux-pocket](https://github.com/shiquda/homebrew-cmux-pocket): a scheduled workflow resolves the latest GitHub Release, verifies the tag's `Cargo.toml`, and updates `Formula/cmux-pocket.rb` to that tag's exact commit. This repository intentionally keeps no formula copy.

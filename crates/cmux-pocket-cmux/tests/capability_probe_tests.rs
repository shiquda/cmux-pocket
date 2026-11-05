//! Tests capability probing behavior in `LiveCmuxBackend`.
//!
//! Specifically validates that:
//! 1. Capability probe command failures (e.g. stopped/unavailable host) are not
//!    cached for 60s, allowing an immediately waking or recovering host to be detected.
//! 2. Successful capability probe results (both positive and actual unsupported)
//!    are cached to avoid spawning a child process on every poll/frame.

#![cfg(unix)]

use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

use cmux_pocket_cmux::{CmuxBackend, LiveCmuxBackend};

static TEST_COUNTER: AtomicU64 = AtomicU64::new(1);

const SCRIPT_TEMPLATE: &str = r#"#!/bin/sh
record="__RECORD__"
state_file="__STATE__"
printf '%s\n' "$*" >> "$record"

state="stopped"
if [ -f "$state_file" ]; then
    state=$(cat "$state_file")
fi

case "$1" in
  capabilities)
    case "$state" in
      stopped)
        echo "cmux is not running: socket not found" >&2
        exit 1
        ;;
      running_with_viewport)
        echo '{"capabilities":["terminal.viewport.v1","mobile.terminal.viewport"]}'
        exit 0
        ;;
      running_without_viewport)
        echo '{"capabilities":["other.capability"]}'
        exit 0
        ;;
      *)
        echo "unknown state: $state" >&2
        exit 1
        ;;
    esac
    ;;
  *)
    exit 0
    ;;
esac
"#;

struct FakeCmux {
    dir: PathBuf,
    backend: LiveCmuxBackend,
    record: PathBuf,
    state_file: PathBuf,
}

impl FakeCmux {
    fn new(name: &str, initial_state: &str) -> Self {
        let id = TEST_COUNTER.fetch_add(1, Ordering::Relaxed);
        let dir = std::env::temp_dir().join(format!(
            "cmux-pocket-probe-test-{name}-{}-{}",
            std::process::id(),
            id
        ));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("create temp dir");

        let record = dir.join("argv.log");
        let state_file = dir.join("state");
        fs::write(&state_file, initial_state).expect("write initial state");

        let script = dir.join("cmux");
        let body = SCRIPT_TEMPLATE
            .replace("__RECORD__", &record.display().to_string())
            .replace("__STATE__", &state_file.display().to_string());
        fs::write(&script, body).expect("write fake cmux");

        let mut perms = fs::metadata(&script).expect("stat fake cmux").permissions();
        perms.set_mode(0o755);
        fs::set_permissions(&script, perms).expect("chmod fake cmux");

        Self {
            backend: LiveCmuxBackend::with_path(&script),
            record,
            state_file,
            dir,
        }
    }

    fn set_state(&self, state: &str) {
        fs::write(&self.state_file, state).expect("write state");
    }

    fn recorded(&self) -> Vec<String> {
        fs::read_to_string(&self.record)
            .unwrap_or_default()
            .lines()
            .map(ToString::to_string)
            .collect()
    }
}

impl Drop for FakeCmux {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.dir);
    }
}

#[tokio::test]
async fn stopped_host_failure_is_not_cached_and_recovers_immediately_on_wake() {
    // 1. Host is stopped / unavailable when first probed.
    let fake = FakeCmux::new("stopped-then-wake", "stopped");

    let supported_initial = fake.backend.supports_viewport_resize().await;
    assert!(
        !supported_initial,
        "stopped host must report unsupported viewport"
    );
    assert_eq!(
        fake.recorded(),
        vec!["capabilities --json".to_string()],
        "should have probed capabilities once"
    );

    // 2. Host wakes up / starts cmux immediately (no 60s wait!).
    fake.set_state("running_with_viewport");

    let supported_after_wake = fake.backend.supports_viewport_resize().await;
    assert!(
        supported_after_wake,
        "immediately available host after wake must report viewport supported without waiting 60s"
    );
    assert_eq!(
        fake.recorded(),
        vec![
            "capabilities --json".to_string(),
            "capabilities --json".to_string()
        ],
        "must probe capabilities again on wake instead of using stale negative cache"
    );

    // 3. Positive capability result must be cached (subsequent call should not spawn cmux).
    let supported_cached = fake.backend.supports_viewport_resize().await;
    assert!(
        supported_cached,
        "cached capability result must stay supported"
    );
    assert_eq!(
        fake.recorded().len(),
        2,
        "successful capability result must be cached without spawning additional process"
    );
}

#[tokio::test]
async fn actual_unsupported_capability_result_is_cached() {
    // Host is running and cleanly returns capabilities, but lacks viewport support.
    let fake = FakeCmux::new("actual-unsupported", "running_without_viewport");

    let supported_first = fake.backend.supports_viewport_resize().await;
    assert!(
        !supported_first,
        "host lacking viewport capability must report unsupported"
    );
    assert_eq!(
        fake.recorded().len(),
        1,
        "first probe must spawn cmux capabilities"
    );

    // Second call immediately: must return false from cache without spawning a new process.
    let supported_second = fake.backend.supports_viewport_resize().await;
    assert!(
        !supported_second,
        "actual unsupported capability must stay unsupported from cache"
    );
    assert_eq!(
        fake.recorded().len(),
        1,
        "actual unsupported capability result must be cached to prevent polling process per frame"
    );
}

#[tokio::test]
async fn stopped_host_then_wake_without_viewport_probes_again_then_caches() {
    // Host is stopped.
    let fake = FakeCmux::new("stopped-then-unsupported", "stopped");

    assert!(!fake.backend.supports_viewport_resize().await);
    assert_eq!(fake.recorded().len(), 1);

    // Host wakes up, but it is an older version without viewport capability.
    fake.set_state("running_without_viewport");

    // Must probe again (failure wasn't cached).
    assert!(!fake.backend.supports_viewport_resize().await);
    assert_eq!(
        fake.recorded().len(),
        2,
        "must probe again on wake even if waking host is older cmux"
    );

    // But now that it succeeded and returned actual unsupported, it must cache!
    assert!(!fake.backend.supports_viewport_resize().await);
    assert_eq!(
        fake.recorded().len(),
        2,
        "actual unsupported result after wake must now be cached"
    );
}

#[tokio::test]
async fn missing_executable_failure_is_not_cached_and_recovers_once_installed() {
    let id = TEST_COUNTER.fetch_add(1, Ordering::Relaxed);
    let dir = std::env::temp_dir().join(format!(
        "cmux-pocket-probe-test-missing-binary-{}-{}",
        std::process::id(),
        id
    ));
    let _ = fs::remove_dir_all(&dir);
    fs::create_dir_all(&dir).expect("create temp dir");

    let script = dir.join("cmux");
    let record = dir.join("argv.log");
    let state_file = dir.join("state");

    // Script does not exist yet.
    let backend = LiveCmuxBackend::with_path(&script);

    let supported_missing = backend.supports_viewport_resize().await;
    assert!(!supported_missing, "missing binary must report unsupported");

    // Now write executable script.
    fs::write(&state_file, "running_with_viewport").expect("write state");
    let body = SCRIPT_TEMPLATE
        .replace("__RECORD__", &record.display().to_string())
        .replace("__STATE__", &state_file.display().to_string());
    fs::write(&script, body).expect("write fake cmux");
    let mut perms = fs::metadata(&script).expect("stat fake cmux").permissions();
    perms.set_mode(0o755);
    fs::set_permissions(&script, perms).expect("chmod fake cmux");

    // Immediately probe: must succeed.
    let supported_after_install = backend.supports_viewport_resize().await;
    assert!(
        supported_after_install,
        "backend must immediately re-probe once binary is available"
    );

    let _ = fs::remove_dir_all(&dir);
}

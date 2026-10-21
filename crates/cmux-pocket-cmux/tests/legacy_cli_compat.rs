//! Verifies that the live backend only passes `--force` / `--paste` to a cmux
//! build that advertises them. Older builds type unknown flags as text, so a
//! regression here would corrupt every keystroke.

#![cfg(unix)]

use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;

use cmux_pocket_cmux::{CmuxBackend, LiveCmuxBackend, TerminalInputKind};

const LEGACY_SCRIPT: &str = r#"#!/bin/sh
record="__RECORD__"
case "$2" in
  --help)
    case "$1" in
      send) echo "Usage: cmux send [flags] [--] <text>"; echo; echo "Flags:"; echo "  --workspace <id|ref|index>   Target workspace"; echo "  --surface <id|ref|index>     Target surface";;
      send-key) echo "Usage: cmux send-key [flags] [--] <key>"; echo; echo "Flags:"; echo "  --surface <id|ref|index>     Target surface";;
    esac
    exit 0
    ;;
esac
printf '%s\n' "$*" >> "$record"
exit 0
"#;

const CURRENT_SCRIPT: &str = r#"#!/bin/sh
record="__RECORD__"
case "$2" in
  --help)
    case "$1" in
      send) echo "Usage: cmux send [flags] [--] <text>"; echo; echo "Flags:"; echo "  --surface <id|ref|index>     Target surface"; echo "  --paste                      Paste the text instead of typing it"; echo "  --force                      Send even over a draft or into an open dialog";;
      send-key) echo "Usage: cmux send-key [flags] [--] <key>"; echo; echo "Flags:"; echo "  --surface <id|ref|index>     Target surface"; echo "  --force                      Send even into an open dialog";;
    esac
    exit 0
    ;;
esac
printf '%s\n' "$*" >> "$record"
exit 0
"#;

struct FakeCmux {
    dir: PathBuf,
    backend: LiveCmuxBackend,
    record: PathBuf,
}

impl FakeCmux {
    fn new(name: &str, template: &str) -> Self {
        let dir =
            std::env::temp_dir().join(format!("cmux-pocket-test-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).expect("create temp dir");
        let record = dir.join("argv.log");
        let script = dir.join("cmux");
        let body = template.replace("__RECORD__", &record.display().to_string());
        fs::write(&script, body).expect("write fake cmux");
        let mut perms = fs::metadata(&script).expect("stat fake cmux").permissions();
        perms.set_mode(0o755);
        fs::set_permissions(&script, perms).expect("chmod fake cmux");
        Self {
            backend: LiveCmuxBackend::with_path(&script),
            record,
            dir,
        }
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
async fn legacy_cli_never_receives_unknown_flags() {
    let fake = FakeCmux::new("legacy", LEGACY_SCRIPT);

    fake.backend.send_input("surface:7", "hello").await.unwrap();
    fake.backend
        .send_input_with_kind("surface:7", "\u{001b}[A", TerminalInputKind::Key)
        .await
        .unwrap();

    let recorded = fake.recorded();
    assert_eq!(
        recorded,
        vec![
            "send --surface surface:7 -- hello".to_string(),
            "send-key --surface surface:7 up".to_string(),
        ]
    );
    assert!(!recorded.iter().any(|line| line.contains("--force")));
    assert!(!recorded.iter().any(|line| line.contains("--paste")));
}

#[tokio::test]
async fn current_cli_forces_every_input() {
    let fake = FakeCmux::new("current", CURRENT_SCRIPT);

    fake.backend.send_input("surface:7", "hello").await.unwrap();
    fake.backend
        .send_input_with_kind("surface:7", "\u{001b}[A", TerminalInputKind::Key)
        .await
        .unwrap();

    assert_eq!(
        fake.recorded(),
        vec![
            "send --surface surface:7 --force --paste -- hello".to_string(),
            "send-key --surface surface:7 --force up".to_string(),
        ]
    );
}

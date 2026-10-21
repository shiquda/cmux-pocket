//! ADB reverse discovery for USB-connected Android devices.
//!
//! The Gateway only listens on loopback, so a phone reaches it over USB exclusively through
//! `adb reverse tcp:<port> tcp:<port>`. This module reports whether that path is available;
//! it never runs `adb reverse` on the user's behalf.

use std::time::Duration;
use tokio::process::Command;
use tokio::time::timeout;

const ADB_TIMEOUT: Duration = Duration::from_secs(3);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AdbReverse {
    /// Loopback port the Gateway listens on and the phone would use over USB.
    pub port: u16,
    /// True when a device is attached and the reverse rule already exists.
    pub active: bool,
}

/// Returns ADB reverse availability, or `None` when adb is missing, hangs, or has no device.
pub async fn discover(port: u16) -> Option<AdbReverse> {
    let devices = run_adb(&["devices"]).await?;
    if !has_attached_device(&devices) {
        return None;
    }
    let reverses = run_adb(&["reverse", "--list"]).await.unwrap_or_default();
    Some(AdbReverse {
        port,
        active: reverse_present(&reverses, port),
    })
}

async fn run_adb(args: &[&str]) -> Option<String> {
    let output = timeout(
        ADB_TIMEOUT,
        Command::new("adb").args(args).kill_on_drop(true).output(),
    )
    .await
    .ok()?
    .ok()?;
    if !output.status.success() {
        return None;
    }
    Some(String::from_utf8_lossy(&output.stdout).into_owned())
}

/// Parses `adb devices` output; unauthorized and offline devices do not count.
pub fn has_attached_device(output: &str) -> bool {
    output.lines().skip(1).any(|line| {
        let mut fields = line.split_whitespace();
        let _serial = fields.next();
        fields.next() == Some("device")
    })
}

/// Parses `adb reverse --list` output (e.g. `host-3 tcp:8088 tcp:8088`).
pub fn reverse_present(output: &str, port: u16) -> bool {
    let wanted = format!("tcp:{port}");
    output.lines().any(|line| {
        line.split_whitespace()
            .filter(|field| *field == wanted)
            .count()
            >= 2
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_attached_devices_count() {
        assert!(has_attached_device(
            "List of devices attached\nemulator-5554\tdevice\n"
        ));
        assert!(!has_attached_device(
            "List of devices attached\nemulator-5554\toffline\nphone\tunauthorized\n"
        ));
        assert!(!has_attached_device("List of devices attached\n"));
    }

    #[test]
    fn reverse_rule_must_map_the_gateway_port() {
        assert!(reverse_present("host-3 tcp:8088 tcp:8088\n", 8088));
        assert!(!reverse_present("host-3 tcp:9000 tcp:8088\n", 8088));
        assert!(!reverse_present("", 8088));
    }
}

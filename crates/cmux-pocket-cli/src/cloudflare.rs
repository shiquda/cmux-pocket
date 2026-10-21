//! Cloudflare Tunnel ingress discovery.
//!
//! Reads locally managed `~/.cloudflared/*.yml|yaml` ingress rules and reports hostnames whose
//! origin is the loopback Gateway. A rule is only a hint: the tunnel may not be running, and
//! the hostname may resolve from elsewhere. `running` is derived from the local `cloudflared`
//! process command line, never from a network request.
//!
//! Only files are read; nothing is written and no credentials are printed.

use serde_yaml::Value;
use std::path::{Path, PathBuf};
use std::time::Duration;
use tokio::process::Command;
use tokio::time::timeout;

const CLOUDFLARED_DIR: &str = ".cloudflared";
const MAX_CONFIG_FILES: usize = 32;
const MAX_CONFIG_BYTES: u64 = 256 * 1024;
const PROCESS_TIMEOUT: Duration = Duration::from_secs(3);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredTunnel {
    /// Public hostname routed to the local Gateway.
    pub hostname: String,
    /// Tunnel name or UUID declared by the config, used for process matching.
    pub tunnel: Option<String>,
    /// True when a local `cloudflared` process appears to run this tunnel.
    pub running: bool,
    /// Config file the rule came from.
    pub config_path: PathBuf,
}

/// Returns tunnel hostnames that forward to the local Gateway.
pub async fn discover(home: &Path, gateway_port: u16) -> Vec<DiscoveredTunnel> {
    let dir = home.join(CLOUDFLARED_DIR);
    let running_cmdline = running_cloudflared().await;
    discover_from_dir(&dir, gateway_port, &running_cmdline)
}

/// Deterministic discovery core: parses configs and matches them against a process listing.
pub fn discover_from_dir(
    dir: &Path,
    gateway_port: u16,
    running_cmdline: &str,
) -> Vec<DiscoveredTunnel> {
    let Ok(entries) = std::fs::read_dir(dir) else {
        return Vec::new();
    };
    let mut paths: Vec<PathBuf> = entries
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| {
            path.is_file()
                && path
                    .extension()
                    .and_then(|ext| ext.to_str())
                    .map(|ext| ext.eq_ignore_ascii_case("yml") || ext.eq_ignore_ascii_case("yaml"))
                    .unwrap_or(false)
        })
        .collect();
    paths.sort();
    paths.truncate(MAX_CONFIG_FILES);

    let mut found: Vec<DiscoveredTunnel> = Vec::new();
    for path in paths {
        let Ok(metadata) = std::fs::metadata(&path) else {
            continue;
        };
        if metadata.len() > MAX_CONFIG_BYTES {
            continue;
        }
        let Ok(raw) = std::fs::read_to_string(&path) else {
            continue;
        };
        let Ok(doc) = serde_yaml::from_str::<Value>(&raw) else {
            continue;
        };
        let tunnel = doc
            .get("tunnel")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|name| !name.is_empty())
            .map(str::to_string);
        let running = tunnel
            .as_deref()
            .map(|name| command_line_mentions(running_cmdline, name))
            .unwrap_or(false);
        for hostname in ingress_hostnames(&doc, gateway_port) {
            if found.iter().any(|entry| entry.hostname == hostname) {
                continue;
            }
            found.push(DiscoveredTunnel {
                hostname,
                tunnel: tunnel.clone(),
                running,
                config_path: path.clone(),
            });
        }
    }
    found
}

/// Extracts hostnames whose ingress origin is the loopback Gateway port.
pub fn ingress_hostnames(doc: &Value, gateway_port: u16) -> Vec<String> {
    let Some(rules) = doc.get("ingress").and_then(Value::as_sequence) else {
        return Vec::new();
    };
    rules
        .iter()
        .filter_map(|rule| {
            let hostname = rule.get("hostname")?.as_str()?.trim().trim_end_matches('.');
            if hostname.is_empty()
                || hostname.contains('*')
                || !hostname
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-' || byte == b'.')
            {
                return None;
            }
            let service = rule.get("service")?.as_str()?;
            (loopback_service_port(service) == Some(gateway_port)).then(|| hostname.to_string())
        })
        .collect()
}

/// Returns the port of a loopback-only `service:` origin (`http://127.0.0.1:8088`).
pub fn loopback_service_port(service: &str) -> Option<u16> {
    let url = url::Url::parse(service.trim()).ok()?;
    match url.scheme() {
        "http" | "https" => {}
        _ => return None,
    }
    let host = url.host_str()?.trim_matches(['[', ']']);
    if !cmux_pocket_macos::is_loopback_host(host) {
        return None;
    }
    let default_port = if url.scheme() == "https" { 443 } else { 80 };
    Some(url.port().unwrap_or(default_port))
}

fn command_line_mentions(cmdline: &str, tunnel: &str) -> bool {
    cmdline
        .split_whitespace()
        .any(|token| token == tunnel || token.ends_with(&format!("/{tunnel}")))
}

async fn running_cloudflared() -> String {
    let output = timeout(
        PROCESS_TIMEOUT,
        Command::new("pgrep")
            .args(["-fl", "cloudflared"])
            .kill_on_drop(true)
            .output(),
    )
    .await
    .ok()
    .and_then(Result::ok);
    output
        .map(|output| String::from_utf8_lossy(&output.stdout).into_owned())
        .unwrap_or_default()
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    fn tunnel_config() -> Value {
        serde_yaml::from_str(
            r#"
tunnel: 6f0a1f52-0000-4000-8000-000000000000
credentials-file: /Users/test/.cloudflared/6f0a1f52.json
ingress:
  - hostname: cmux-gateway.example.com
    service: http://127.0.0.1:8088
  - hostname: other.example.com
    service: http://127.0.0.1:9000
  - service: http_status:404
"#,
        )
        .unwrap()
    }

    #[test]
    fn only_gateway_backed_hostnames_are_reported() {
        assert_eq!(
            ingress_hostnames(&tunnel_config(), 8088),
            vec!["cmux-gateway.example.com".to_string()]
        );
        assert!(ingress_hostnames(&tunnel_config(), 9090).is_empty());
        assert!(ingress_hostnames(&Value::Null, 8088).is_empty());
    }

    #[test]
    fn loopback_service_parsing_accepts_localhost_and_rejects_remote() {
        assert_eq!(loopback_service_port("http://127.0.0.1:8088"), Some(8088));
        assert_eq!(loopback_service_port("http://localhost:8088/"), Some(8088));
        assert_eq!(loopback_service_port("http://[::1]:8088"), Some(8088));
        assert_eq!(loopback_service_port("https://mac.example.com:8088"), None);
        assert_eq!(loopback_service_port("http_status:404"), None);
        assert_eq!(loopback_service_port("tcp://127.0.0.1:8088"), None);
    }

    #[test]
    fn discovers_ingress_and_process_state_from_directory() {
        let home = tempdir().unwrap();
        let dir = home.path().join(CLOUDFLARED_DIR);
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(
            dir.join("config.yml"),
            serde_yaml::to_string(&tunnel_config()).unwrap(),
        )
        .unwrap();

        let stopped = discover_from_dir(&dir, 8088, "cloudflared tunnel run other-tunnel\n");
        assert_eq!(stopped.len(), 1);
        assert_eq!(stopped[0].hostname, "cmux-gateway.example.com");
        assert!(!stopped[0].running);

        let running = discover_from_dir(
            &dir,
            8088,
            "1234 cloudflared tunnel run 6f0a1f52-0000-4000-8000-000000000000\n",
        );
        assert!(running[0].running);

        assert!(discover_from_dir(&home.path().join("missing"), 8088, "").is_empty());
    }

    #[test]
    fn wildcard_and_invalid_hostnames_are_skipped() {
        let doc: Value = serde_yaml::from_str(
            r#"
ingress:
  - hostname: "*.example.com"
    service: http://127.0.0.1:8088
  - hostname: "bad host"
    service: http://127.0.0.1:8088
"#,
        )
        .unwrap();
        assert!(ingress_hostnames(&doc, 8088).is_empty());
    }
}

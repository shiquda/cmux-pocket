//! Private Tailscale Serve setup. Existing routes are inspected before mutation.

use crate::error::CliError;
use serde::Serialize;
use serde_json::Value;
use std::path::PathBuf;
use std::time::Duration;
use tokio::process::Command;
use tokio::time::timeout;

#[derive(Debug, Serialize)]
pub struct TailnetEndpoint {
    pub host: String,
    pub port: u16,
    pub route_created: bool,
}

fn dependency(message: impl Into<String>) -> CliError {
    CliError::DependencyUnavailable(message.into())
}

fn tailnet_host(status: &Value) -> Result<String, CliError> {
    if status["BackendState"] != "Running" || status["Self"]["Online"] != true {
        return Err(dependency("Tailscale is offline. Open Tailscale on the Mac, sign in, and connect; then rerun setup --tailscale."));
    }
    let host = status["Self"]["DNSName"]
        .as_str()
        .unwrap_or_default()
        .trim_end_matches('.');
    if !host.ends_with(".ts.net")
        || !host
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'.')
    {
        return Err(dependency("Tailscale has no usable tailnet DNS name. Enable MagicDNS and HTTPS certificates in Tailscale, then rerun setup --tailscale."));
    }
    Ok(host.to_string())
}

/// Returns true only when the existing HTTPS root already targets this Gateway.
fn inspect_route(config: &Value, host: &str, upstream: &str) -> Result<bool, CliError> {
    if !config.is_object() {
        return Err(dependency("Tailscale returned an unsupported Serve configuration. Existing forwarding was preserved."));
    }
    let authority = format!("{host}:443");
    if config["AllowFunnel"][&authority] == true {
        return Err(dependency("Tailscale Funnel is enabled on port 443. Disable Funnel before using this private-tailnet setup; Pocket does not change existing public exposure."));
    }
    let tcp = &config["TCP"]["443"];
    if !tcp.is_null() && tcp["HTTPS"] != true {
        return Err(dependency("Tailscale port 443 is already used by a different service. Existing forwarding was preserved. Inspect tailscale serve status."));
    }
    let root = &config["Web"][&authority]["Handlers"]["/"];
    if root.is_null() {
        return Ok(false);
    }
    if tcp["HTTPS"] == true && root["Proxy"].as_str() == Some(upstream) {
        return Ok(true);
    }
    Err(dependency("Tailscale HTTPS root already serves another target. Existing forwarding was preserved. Inspect tailscale serve status before changing it."))
}

pub struct Tailscale {
    executable: PathBuf,
}

impl Default for Tailscale {
    fn default() -> Self {
        Self {
            executable: "tailscale".into(),
        }
    }
}

impl Tailscale {
    async fn run(&self, args: &[&str]) -> Result<Vec<u8>, CliError> {
        let output = timeout(
            Duration::from_secs(15),
            Command::new(&self.executable).args(args).kill_on_drop(true).output(),
        ).await.map_err(|_| dependency("Tailscale did not respond within 15 seconds. Open Tailscale and retry. If Serve needs enabling, run tailscale serve with your Gateway port once to complete its HTTPS setup."))?
            .map_err(|_| dependency("Cannot run the Tailscale CLI. Install Tailscale and make the tailscale command available on PATH, then rerun setup --tailscale."))?;
        if !output.status.success() {
            return Err(dependency("Tailscale command failed. Run tailscale status and tailscale serve status for details. If Serve is not enabled, run tailscale serve with your Gateway port to complete its HTTPS setup, then rerun setup --tailscale."));
        }
        Ok(output.stdout)
    }

    async fn json(&self, args: &[&str]) -> Result<Value, CliError> {
        serde_json::from_slice(&self.run(args).await?).map_err(|_| {
            dependency(
                "Tailscale returned an unsupported status response. Update Tailscale and retry.",
            )
        })
    }

    pub async fn setup(&self, gateway_host: &str, port: u16) -> Result<TailnetEndpoint, CliError> {
        let host = tailnet_host(&self.json(&["status", "--json"]).await?)?;
        let gateway_host = if gateway_host == "::1" {
            "[::1]"
        } else {
            gateway_host
        };
        let upstream = format!("http://{gateway_host}:{port}");
        let config = self.json(&["serve", "status", "--json"]).await?;
        let already_configured = inspect_route(&config, &host, &upstream)?;
        if !already_configured {
            self.run(&["serve", "--bg", "--https=443", "--set-path=/", &upstream])
                .await?;
            let config = self.json(&["serve", "status", "--json"]).await?;
            if !inspect_route(&config, &host, &upstream)? {
                return Err(dependency("Tailscale Serve did not confirm the Gateway route. Inspect tailscale serve status and retry."));
            }
        }
        Ok(TailnetEndpoint {
            host,
            port: 443,
            route_created: !already_configured,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn offline_peers_and_missing_dns_are_actionable() {
        assert!(tailnet_host(&json!({"BackendState": "Stopped"}))
            .unwrap_err()
            .to_string()
            .contains("offline"));
        assert!(
            tailnet_host(&json!({"BackendState": "Running", "Self": {"Online": true}})).is_err()
        );
        assert_eq!(tailnet_host(&json!({"BackendState": "Running", "Self": {"Online": true, "DNSName": "mac.example.ts.net."}})).unwrap(), "mac.example.ts.net");
    }

    #[test]
    fn preserves_conflicting_routes_and_rejects_public_funnel() {
        let mut config = json!({
            "TCP": {"443": {"HTTPS": true}},
            "Web": {"mac.example.ts.net:443": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:8088"}}}}
        });
        assert!(inspect_route(&config, "mac.example.ts.net", "http://127.0.0.1:8088").unwrap());
        assert!(inspect_route(&config, "mac.example.ts.net", "http://127.0.0.1:9090").is_err());
        config["AllowFunnel"] = json!({"mac.example.ts.net:443": true});
        assert!(inspect_route(&config, "mac.example.ts.net", "http://127.0.0.1:8088").is_err());
        assert!(!inspect_route(&json!({}), "mac.example.ts.net", "http://127.0.0.1:8088").unwrap());
        assert!(inspect_route(
            &json!({"TCP": {"443": {"TCPForward": "localhost:22"}}}),
            "mac.example.ts.net",
            "http://127.0.0.1:8088"
        )
        .is_err());
        assert!(
            inspect_route(&Value::Null, "mac.example.ts.net", "http://127.0.0.1:8088").is_err()
        );
    }

    #[cfg(unix)]
    fn fake_tailscale(config: &Value) -> (tempfile::TempDir, Tailscale) {
        use std::os::unix::fs::PermissionsExt;
        let temp = tempfile::tempdir().unwrap();
        let executable = temp.path().join("tailscale test");
        std::fs::write(temp.path().join("config.json"), config.to_string()).unwrap();
        std::fs::write(&executable, r##"#!/bin/sh
base=$(dirname "$0")
case "$1 $2" in
  'status --json') printf '%s' '{"BackendState":"Running","Self":{"Online":true,"DNSName":"mac.example.ts.net."}}' ;;
  'serve status') cat "$base/config.json" ;;
  'serve --bg')
    printf 'configured\n' >> "$base/changes"
    printf '%s' '{"TCP":{"443":{"HTTPS":true}},"Web":{"mac.example.ts.net:443":{"Handlers":{"/":{"Proxy":"http://127.0.0.1:8088"}}}}}' > "$base/config.json"
    ;;
  *) exit 1 ;;
esac
"##).unwrap();
        std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o700)).unwrap();
        (temp, Tailscale { executable })
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn setup_creates_and_confirms_a_route_once() {
        let (temp, tailscale) = fake_tailscale(&json!({}));
        let endpoint = tailscale.setup("127.0.0.1", 8088).await.unwrap();
        assert_eq!(endpoint.host, "mac.example.ts.net");
        assert_eq!(endpoint.port, 443);
        assert!(endpoint.route_created);
        assert!(
            !tailscale
                .setup("127.0.0.1", 8088)
                .await
                .unwrap()
                .route_created
        );
        assert_eq!(
            std::fs::read_to_string(temp.path().join("changes")).unwrap(),
            "configured\n"
        );
    }

    #[cfg(unix)]
    #[tokio::test]
    async fn setup_leaves_an_existing_service_untouched() {
        let config = json!({
            "TCP": {"443": {"HTTPS": true}},
            "Web": {"mac.example.ts.net:443": {"Handlers": {"/": {"Proxy": "http://localhost:9000"}}}}
        });
        let (temp, tailscale) = fake_tailscale(&config);
        assert!(tailscale.setup("127.0.0.1", 8088).await.is_err());
        assert!(!temp.path().join("changes").exists());
        assert_eq!(
            std::fs::read_to_string(temp.path().join("config.json")).unwrap(),
            config.to_string()
        );
    }
}

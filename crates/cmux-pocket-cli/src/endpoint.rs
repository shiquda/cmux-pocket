//! Phone endpoint resolution and precedence logic.
//!
//! Enforces precedence:
//! 1. Explicit CLI argument (`--endpoint <URL>`)
//! 2. Locally saved endpoint in `config.toml` (`config.endpoint`)
//! 3. Verifiable automatic discovery (Tailscale .ts.net HTTPS route)
//!
//! Never falls back automatically to loopback addresses for phone endpoints,
//! while allowing explicit loopback specification for USB reverse / testing.

use crate::error::CliError;
use crate::tailscale::Tailscale;
use cmux_pocket_macos::{parse_and_validate_endpoint, GatewayConfig, ParsedEndpoint};
use serde::Serialize;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub enum EndpointSource {
    CliArgument,
    SavedConfig,
    TailscaleAuto,
}

impl std::fmt::Display for EndpointSource {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::CliArgument => write!(f, "CLI argument (--endpoint)"),
            Self::SavedConfig => write!(f, "saved configuration (config.endpoint)"),
            Self::TailscaleAuto => write!(f, "Tailscale automatic discovery"),
        }
    }
}

/// Resolves the phone connection endpoint using strict precedence.
pub async fn resolve_mobile_endpoint(
    cli_arg: Option<&str>,
    config: &GatewayConfig,
) -> Result<(ParsedEndpoint, EndpointSource), CliError> {
    // 1. Explicit CLI argument takes top priority
    if let Some(arg) = cli_arg {
        let trimmed = arg.trim();
        if !trimmed.is_empty() {
            let parsed = parse_and_validate_endpoint(trimmed).map_err(|e| {
                CliError::InvalidUsage(format!("Invalid endpoint specified in --endpoint: {}", e))
            })?;
            return Ok((parsed, EndpointSource::CliArgument));
        }
    }
    // 2. Saved configuration endpoint
    if let Some(saved) = &config.endpoint {
        let trimmed = saved.trim();
        if !trimmed.is_empty() {
            let parsed = parse_and_validate_endpoint(trimmed).map_err(|e| {
                CliError::ConfigOrToken(format!(
                    "Invalid saved endpoint in configuration ({}): {}",
                    trimmed, e
                ))
            })?;
            return Ok((parsed, EndpointSource::SavedConfig));
        }
    }

    // 3. Verifiable Tailscale auto-discovery
    let tailscale = Tailscale::default();
    if let Ok(ts_url) = tailscale.discover_endpoint().await {
        if let Ok(parsed) = parse_and_validate_endpoint(&ts_url) {
            return Ok((parsed, EndpointSource::TailscaleAuto));
        }
    }

    // 4. No valid phone endpoint found
    Err(CliError::DependencyUnavailable(
        "No phone-reachable Gateway endpoint found.\n\
         To connect cmux-pocket from Android:\n\
           1. Use Tailscale: sign in to Tailscale on the Mac and run `cmux-pocket setup --tailscale`\n\
           2. Use a custom domain or reverse proxy: run `cmux-pocket config set endpoint <URL>`\n\
           3. Or pass an endpoint directly: `cmux-pocket qr --endpoint <URL>`\n\n\
         Note: Loopback addresses (127.0.0.1) are not automatically used because phones cannot reach them over Wi-Fi/cellular.\n\
         To explicitly use loopback over USB reverse, pass `--endpoint ws://127.0.0.1:8088`."
            .to_string(),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn cli_arg_overrides_saved_config() {
        let config = GatewayConfig {
            endpoint: Some("wss://saved.example.com".to_string()),
            ..GatewayConfig::default()
        };
        let (endpoint, source) = resolve_mobile_endpoint(Some("wss://cli.example.com"), &config)
            .await
            .unwrap();
        assert_eq!(endpoint.host, "cli.example.com");
        assert_eq!(source, EndpointSource::CliArgument);
    }

    #[tokio::test]
    async fn saved_config_used_when_no_cli_arg() {
        let config = GatewayConfig {
            endpoint: Some("wss://saved.example.com".to_string()),
            ..GatewayConfig::default()
        };
        let (endpoint, source) = resolve_mobile_endpoint(None, &config).await.unwrap();
        assert_eq!(endpoint.host, "saved.example.com");
        assert_eq!(source, EndpointSource::SavedConfig);
    }

    #[tokio::test]
    async fn explicit_loopback_allowed_with_cli_arg() {
        let config = GatewayConfig::default();
        let (endpoint, source) = resolve_mobile_endpoint(Some("ws://127.0.0.1:8088"), &config)
            .await
            .unwrap();
        assert_eq!(endpoint.host, "127.0.0.1");
        assert_eq!(endpoint.port, 8088);
        assert!(endpoint.is_loopback);
        assert_eq!(source, EndpointSource::CliArgument);
    }

    #[tokio::test]
    async fn cleartext_non_loopback_rejected() {
        let config = GatewayConfig::default();
        let result = resolve_mobile_endpoint(Some("ws://remote.example.com:8088"), &config).await;
        assert!(result.is_err());
    }
}

//! Phone endpoint candidates and discovery.
//!
//! An endpoint is a *front door* to the loopback Gateway: Tailscale Serve, a LAN TLS proxy,
//! a Cloudflare Tunnel hostname, or ADB reverse. The Gateway itself always listens on one
//! loopback address, so several front doors may target it simultaneously.
//!
//! Discovery is deliberately conservative: a candidate is only selected automatically when
//! its Mac-side configuration is verified. Cloudflare configs without a running tunnel and
//! ADB reverse entries never become the default, because the phone cannot reach them yet.

use crate::adb::AdbReverse;
use crate::cloudflare::{self, DiscoveredTunnel};
use crate::error::CliError;
use crate::tailscale::{TailnetEndpoint, Tailscale};
use cmux_pocket_macos::{
    parse_and_validate_endpoint, GatewayConfig, ParsedEndpoint, DEFAULT_GATEWAY_PORT,
};
use serde::Serialize;
use std::path::Path;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
pub enum EndpointSource {
    CliArgument,
    SavedConfig,
    NamedEndpoint,
    TailscaleServe,
    CloudflareTunnel,
    AdbReverse,
}

impl std::fmt::Display for EndpointSource {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let label = match self {
            Self::CliArgument => "CLI argument (--endpoint)",
            Self::SavedConfig => "saved config.endpoint",
            Self::NamedEndpoint => "saved named endpoint",
            Self::TailscaleServe => "Tailscale Serve route",
            Self::CloudflareTunnel => "Cloudflare Tunnel config",
            Self::AdbReverse => "ADB reverse",
        };
        write!(f, "{label}")
    }
}

/// One selectable phone endpoint.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct EndpointCandidate {
    /// Normalized endpoint URL shown to the user.
    pub url: String,
    /// Default profile name used when the QR code is generated.
    pub name: String,
    pub source: EndpointSource,
    /// Human-readable provenance, e.g. `saved endpoint 'LAN'`.
    pub source_label: String,
    /// True only when Mac-side routing was verified (Tailscale route, running tunnel, active reverse).
    pub verified: bool,
    /// False for candidates that must be picked explicitly (ADB, stopped tunnel).
    pub can_default: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub note: Option<String>,
    #[serde(skip)]
    pub parsed: ParsedEndpoint,
}

/// Discovery results gathered from the local machine.
#[derive(Debug, Default, Clone)]
pub struct DiscoveredEndpoints {
    pub tailscale: Option<TailnetEndpoint>,
    pub cloudflare: Vec<DiscoveredTunnel>,
    pub adb: Option<AdbReverse>,
}

/// Gathers every discoverable endpoint, tolerating missing tools and offline services.
pub async fn discover_all(config: &GatewayConfig, home: Option<&Path>) -> DiscoveredEndpoints {
    let tailscale = Tailscale::default()
        .discover_verified(config.port)
        .await
        .ok()
        .flatten();
    let cloudflare = match home {
        Some(home) => cloudflare::discover(home, config.port).await,
        None => Vec::new(),
    };
    let adb = crate::adb::discover(config.port).await;
    DiscoveredEndpoints {
        tailscale,
        cloudflare,
        adb,
    }
}

/// Collects candidates using live discovery.
pub async fn collect_candidates(
    cli_arg: Option<&str>,
    config: &GatewayConfig,
    home: Option<&Path>,
) -> Result<Vec<EndpointCandidate>, CliError> {
    // An explicit flag skips the picker, so there is nothing to discover.
    let discovered = match cli_arg.map(str::trim).filter(|arg| !arg.is_empty()) {
        Some(_) => DiscoveredEndpoints::default(),
        None => discover_all(config, home).await,
    };
    build_candidates(cli_arg, config, &discovered)
}

fn candidate(
    url: &str,
    name: String,
    source: EndpointSource,
    source_label: String,
    verified: bool,
    can_default: bool,
    note: Option<String>,
) -> Result<EndpointCandidate, CliError> {
    let parsed = parse_and_validate_endpoint(url)
        .map_err(|e| CliError::ConfigOrToken(format!("Invalid endpoint '{url}': {e}")))?;
    Ok(EndpointCandidate {
        url: parsed.raw.clone(),
        name,
        source,
        source_label,
        verified,
        can_default,
        note,
        parsed,
    })
}

/// Builds the ordered, de-duplicated candidate list.
///
/// Order: explicit flag, legacy saved endpoint, named endpoints, verified Tailscale Serve,
/// Cloudflare Tunnel hostnames (running first), ADB reverse.
pub fn build_candidates(
    cli_arg: Option<&str>,
    config: &GatewayConfig,
    discovered: &DiscoveredEndpoints,
) -> Result<Vec<EndpointCandidate>, CliError> {
    let mut candidates: Vec<EndpointCandidate> = Vec::new();

    if let Some(arg) = cli_arg.map(str::trim).filter(|arg| !arg.is_empty()) {
        let parsed = parse_and_validate_endpoint(arg).map_err(|e| {
            CliError::InvalidUsage(format!("Invalid endpoint specified in --endpoint: {e}"))
        })?;
        let name = parsed.host.clone();
        candidates.push(candidate(
            &parsed.raw,
            name,
            EndpointSource::CliArgument,
            EndpointSource::CliArgument.to_string(),
            false,
            true,
            None,
        )?);
    }

    if let Some(saved) = &config.endpoint {
        let saved = saved.trim();
        if !saved.is_empty() {
            let parsed = parse_and_validate_endpoint(saved).map_err(|e| {
                CliError::ConfigOrToken(format!(
                    "Invalid saved endpoint in configuration ({saved}): {e}"
                ))
            })?;
            let name = parsed.host.clone();
            candidates.push(candidate(
                &parsed.raw,
                name,
                EndpointSource::SavedConfig,
                EndpointSource::SavedConfig.to_string(),
                false,
                true,
                None,
            )?);
        }
    }

    for entry in &config.endpoints {
        let parsed = parse_and_validate_endpoint(&entry.url).map_err(|e| {
            CliError::ConfigOrToken(format!(
                "Invalid saved endpoint '{}' ({}): {e}",
                entry.name, entry.url
            ))
        })?;
        candidates.push(candidate(
            &parsed.raw,
            entry.name.clone(),
            EndpointSource::NamedEndpoint,
            format!("saved endpoint '{}'", entry.name),
            false,
            true,
            None,
        )?);
    }

    if let Some(tailscale) = &discovered.tailscale {
        let url = if tailscale.port == 443 {
            format!("wss://{}", tailscale.host)
        } else {
            format!("wss://{}:{}", tailscale.host, tailscale.port)
        };
        candidates.push(candidate(
            &url,
            "Tailscale".to_string(),
            EndpointSource::TailscaleServe,
            format!("Tailscale Serve route on port {}", tailscale.port),
            true,
            true,
            None,
        )?);
    }

    let mut tunnels = discovered.cloudflare.clone();
    tunnels.sort_by_key(|tunnel| !tunnel.running);
    for tunnel in &tunnels {
        let note = if tunnel.running {
            "cloudflared tunnel process detected".to_string()
        } else {
            "config only; cloudflared is not running this tunnel".to_string()
        };
        candidates.push(candidate(
            &format!("wss://{}", tunnel.hostname),
            tunnel.hostname.clone(),
            EndpointSource::CloudflareTunnel,
            EndpointSource::CloudflareTunnel.to_string(),
            tunnel.running,
            tunnel.running,
            Some(note),
        )?);
    }

    if let Some(adb) = &discovered.adb {
        let note = if adb.active {
            "USB debugging attached; dev only".to_string()
        } else {
            format!(
                "dev only; run `adb reverse tcp:{0} tcp:{0}` first",
                adb.port
            )
        };
        candidates.push(candidate(
            &format!("ws://127.0.0.1:{}", adb.port),
            "USB (ADB)".to_string(),
            EndpointSource::AdbReverse,
            EndpointSource::AdbReverse.to_string(),
            adb.active,
            false,
            Some(note),
        )?);
    }

    let mut seen = std::collections::HashSet::new();
    candidates.retain(|candidate| seen.insert(candidate.url.clone()));
    Ok(candidates)
}

/// Returns the index of the candidate to encode when no interactive picker is used.
pub fn resolve_default_index(candidates: &[EndpointCandidate]) -> Result<usize, CliError> {
    candidates
        .iter()
        .position(|candidate| candidate.can_default)
        .ok_or_else(no_endpoint_error)
}

pub fn no_endpoint_error() -> CliError {
    CliError::DependencyUnavailable(
        [
            "No phone-reachable Gateway endpoint found.".to_string(),
            "Options:".to_string(),
            format!(
                "  1. Tailscale: run `cmux-pocket setup --tailscale`; discovery only accepts a Serve\n     route whose root target is this Gateway (port {DEFAULT_GATEWAY_PORT})."
            ),
            "  2. Named endpoint: `cmux-pocket endpoint add wss://gateway.example.com --name LAN`"
                .to_string(),
            "  3. Legacy default: `cmux-pocket config set endpoint wss://example.com`".to_string(),
            "  4. One-off: `cmux-pocket qr --endpoint <URL>`".to_string(),
            String::new(),
            "Run `cmux-pocket qr` in a terminal to pick from configured, Tailscale, Cloudflare"
                .to_string(),
            "Tunnel, and USB (ADB reverse) candidates.".to_string(),
            "Note: loopback (127.0.0.1) is never chosen automatically for phones.".to_string(),
        ]
        .join("\n"),
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cloudflare::DiscoveredTunnel;
    use cmux_pocket_macos::NamedEndpoint;
    use std::path::PathBuf;

    fn saved_config() -> GatewayConfig {
        GatewayConfig {
            endpoint: Some("wss://saved.example.com".to_string()),
            endpoints: vec![NamedEndpoint::new("LAN", "wss://lan.example.com")],
            ..GatewayConfig::default()
        }
    }

    #[test]
    fn explicit_argument_wins_and_is_not_discovered() {
        let candidates = build_candidates(
            Some("wss://cli.example.com"),
            &saved_config(),
            &DiscoveredEndpoints::default(),
        )
        .unwrap();
        assert_eq!(candidates[0].url, "wss://cli.example.com");
        assert_eq!(candidates[0].source, EndpointSource::CliArgument);
        assert_eq!(candidates[1].source, EndpointSource::SavedConfig);
    }

    #[test]
    fn named_endpoints_follow_the_legacy_default() {
        let candidates =
            build_candidates(None, &saved_config(), &DiscoveredEndpoints::default()).unwrap();
        assert_eq!(candidates[0].url, "wss://saved.example.com");
        assert_eq!(candidates[1].name, "LAN");
        assert_eq!(candidates[1].source, EndpointSource::NamedEndpoint);
        assert_eq!(
            candidates
                .get(resolve_default_index(&candidates).unwrap())
                .map(|c| c.url.clone()),
            Some("wss://saved.example.com".to_string())
        );
    }

    #[test]
    fn tailscale_requires_a_verified_route_and_precedes_cloudflare() {
        let discovered = DiscoveredEndpoints {
            tailscale: Some(TailnetEndpoint {
                host: "mac.example.ts.net".to_string(),
                port: 8443,
                route_created: false,
            }),
            cloudflare: vec![DiscoveredTunnel {
                hostname: "cmux-gateway.example.com".to_string(),
                tunnel: Some("tunnel".to_string()),
                running: true,
                config_path: PathBuf::from("/tmp/config.yml"),
            }],
            adb: Some(AdbReverse {
                port: 8088,
                active: true,
            }),
        };
        let candidates = build_candidates(None, &GatewayConfig::default(), &discovered).unwrap();
        assert_eq!(candidates[0].url, "wss://mac.example.ts.net:8443");
        assert!(candidates[0].verified);
        assert_eq!(candidates[1].url, "wss://cmux-gateway.example.com");
        assert!(candidates[1].verified);
        assert_eq!(candidates[2].url, "ws://127.0.0.1:8088");
        assert!(!candidates[2].can_default);
    }

    #[test]
    fn stopped_tunnel_and_adb_are_not_defaults() {
        let discovered = DiscoveredEndpoints {
            cloudflare: vec![DiscoveredTunnel {
                hostname: "cmux-gateway.example.com".to_string(),
                tunnel: Some("tunnel".to_string()),
                running: false,
                config_path: PathBuf::from("/tmp/config.yml"),
            }],
            adb: Some(AdbReverse {
                port: 8088,
                active: false,
            }),
            ..DiscoveredEndpoints::default()
        };
        let candidates = build_candidates(None, &GatewayConfig::default(), &discovered).unwrap();
        assert_eq!(candidates.len(), 2);
        assert!(resolve_default_index(&candidates).is_err());

        let default_only = build_candidates(
            Some("ws://127.0.0.1:8088"),
            &GatewayConfig::default(),
            &DiscoveredEndpoints::default(),
        )
        .unwrap();
        assert_eq!(
            &default_only[resolve_default_index(&default_only).unwrap()].name,
            "127.0.0.1"
        );
    }

    #[test]
    fn duplicate_urls_keep_the_highest_priority_entry() {
        let discovered = DiscoveredEndpoints {
            tailscale: Some(TailnetEndpoint {
                host: "mac.example.ts.net".to_string(),
                port: 443,
                route_created: false,
            }),
            ..DiscoveredEndpoints::default()
        };
        let config = GatewayConfig {
            endpoints: vec![NamedEndpoint::new("Tailscale", "wss://mac.example.ts.net")],
            ..GatewayConfig::default()
        };
        let candidates = build_candidates(None, &config, &discovered).unwrap();
        assert_eq!(candidates.len(), 1);
        assert_eq!(candidates[0].source, EndpointSource::NamedEndpoint);
    }

    #[test]
    fn cleartext_non_loopback_is_rejected() {
        assert!(build_candidates(
            Some("ws://remote.example.com:8088"),
            &GatewayConfig::default(),
            &DiscoveredEndpoints::default()
        )
        .is_err());
    }
}

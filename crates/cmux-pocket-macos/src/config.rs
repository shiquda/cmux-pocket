//! Gateway and CLI configuration management with atomic writes and validation.
//!
//! Enforces that listeners bind strictly to loopback addresses, paths remain outside
//! Homebrew Cellar, and configuration files are written atomically with user-only permissions.

use crate::error::MacOsError;
use crate::loopback::{is_loopback_host, validate_loopback_host};
use crate::paths::{validate_outside_cellar, PocketPaths};
use crate::permissions::{atomic_write_secret_file, ensure_file_user_only};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};

/// Default Gateway loopback host.
pub const DEFAULT_GATEWAY_HOST: &str = "127.0.0.1";

/// Default Gateway listener TCP port.
pub const DEFAULT_GATEWAY_PORT: u16 = 8088;

/// Configuration options for the Gateway service and local CLI.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct GatewayConfig {
    /// Host to bind (must be loopback: 127.0.0.1, localhost, ::1).
    #[serde(default = "default_host")]
    pub host: String,

    /// TCP port to bind (default: 8088).
    #[serde(default = "default_port")]
    pub port: u16,

    /// Optional explicit path to authentication token file.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub token_path: Option<PathBuf>,

    /// Optional explicit path to log directory.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub log_dir: Option<PathBuf>,

    /// Optional explicit path to `cmux` binary.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cmux_path: Option<PathBuf>,

    /// Optional custom remote endpoint for phone connections (e.g. wss://example.com, https://tunnel.domain:8443).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub endpoint: Option<String>,
    /// Additional unrecognized fields preserved across roundtrips.
    #[serde(flatten)]
    pub extra: toml::Table,
}

fn default_host() -> String {
    DEFAULT_GATEWAY_HOST.to_string()
}

fn default_port() -> u16 {
    DEFAULT_GATEWAY_PORT
}

impl Default for GatewayConfig {
    fn default() -> Self {
        Self {
            host: default_host(),
            port: default_port(),
            token_path: None,
            log_dir: None,
            cmux_path: None,
            extra: toml::Table::new(),
            endpoint: None,
        }
    }
}

impl GatewayConfig {
    /// Validates configuration invariants:
    /// 1. `host` must be a valid loopback address.
    /// 2. `port` must not be 0.
    /// 3. All configured paths must be outside Homebrew Cellar.
    pub fn validate(&self) -> Result<(), MacOsError> {
        validate_loopback_host(&self.host)?;

        if self.port == 0 {
            return Err(MacOsError::InvalidConfiguration(
                "Port 0 is not permitted".to_string(),
            ));
        }

        if let Some(token_path) = &self.token_path {
            validate_outside_cellar(token_path, "token_path")?;
        }

        if let Some(log_dir) = &self.log_dir {
            validate_outside_cellar(log_dir, "log_dir")?;
        }

        if let Some(cmux_path) = &self.cmux_path {
            validate_outside_cellar(cmux_path, "cmux_path")?;
        }

        if let Some(endpoint) = &self.endpoint {
            validate_remote_endpoint(endpoint)?;
        }

        Ok(())
    }

    /// Resolves effective token path using defaults if not explicitly configured.
    pub fn resolve_token_path(&self, paths: &PocketPaths) -> PathBuf {
        self.token_path
            .clone()
            .unwrap_or_else(|| paths.token_file.clone())
    }

    /// Resolves effective log directory using defaults if not explicitly configured.
    pub fn resolve_log_dir(&self, paths: &PocketPaths) -> PathBuf {
        self.log_dir
            .clone()
            .unwrap_or_else(|| paths.log_dir.clone())
    }

    /// Serializes configuration to a formatted TOML string.
    pub fn to_toml_string(&self) -> Result<String, MacOsError> {
        let serialized = toml::to_string_pretty(self)?;
        Ok(serialized)
    }

    /// Formats configuration for display, redacting any sensitive data.
    pub fn to_redacted_toml_string(&self) -> Result<String, MacOsError> {
        self.to_toml_string()
    }
}

/// Parsed representation of an endpoint for mobile connection.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ParsedEndpoint {
    pub raw: String,
    pub scheme: String,
    pub host: String,
    pub port: u16,
    pub is_loopback: bool,
    pub is_tls: bool,
}

/// Parses and validates a Gateway endpoint string intended for phone connection.
///
/// Enforces TLS for all non-loopback connections, and prevents arbitrary cleartext WebSockets.
pub fn parse_and_validate_endpoint(endpoint: &str) -> Result<ParsedEndpoint, MacOsError> {
    let trimmed = endpoint.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        return Err(MacOsError::InvalidConfiguration(
            "Endpoint cannot be empty".to_string(),
        ));
    }

    let (scheme_opt, rest) = if let Some(idx) = trimmed.find("://") {
        let scheme = &trimmed[..idx];
        let rest = &trimmed[idx + 3..];
        (Some(scheme), rest)
    } else {
        (None, trimmed)
    };

    let authority = if let Some(idx) = rest.find(['/', '?']) {
        &rest[..idx]
    } else {
        rest
    };

    if authority.is_empty() {
        return Err(MacOsError::InvalidConfiguration(
            "Endpoint has no valid host authority".to_string(),
        ));
    }

    let (host, explicit_port) = if authority.starts_with('[') {
        let end_bracket = authority.find(']').ok_or_else(|| {
            MacOsError::InvalidConfiguration("Unclosed IPv6 bracket in endpoint".to_string())
        })?;
        let host = &authority[1..end_bracket];
        let after = &authority[end_bracket + 1..];
        let port = if let Some(rest) = after.strip_prefix(':') {
            let p: u16 = rest.parse().map_err(|_| {
                MacOsError::InvalidConfiguration(format!(
                    "Invalid port in endpoint '{}'",
                    authority
                ))
            })?;
            Some(p)
        } else {
            None
        };
        (host, port)
    } else if let Some(idx) = authority.rfind(':') {
        let host = &authority[..idx];
        let p: u16 = authority[idx + 1..].parse().map_err(|_| {
            MacOsError::InvalidConfiguration(format!("Invalid port in endpoint '{}'", authority))
        })?;
        (host, Some(p))
    } else {
        (authority, None)
    };

    if host.is_empty() {
        return Err(MacOsError::InvalidConfiguration(
            "Endpoint host cannot be empty".to_string(),
        ));
    }
    if host == "0.0.0.0" || host == "::" {
        return Err(MacOsError::InvalidConfiguration(
            "Wildcard address (0.0.0.0 / ::) cannot be used as an endpoint".to_string(),
        ));
    }

    let is_loopback = is_loopback_host(host);

    let (is_tls, scheme) = match scheme_opt {
        Some(s) if s.eq_ignore_ascii_case("wss") => (true, "wss".to_string()),
        Some(s) if s.eq_ignore_ascii_case("https") => (true, "wss".to_string()),
        Some(s) if s.eq_ignore_ascii_case("ws") => {
            if !is_loopback {
                return Err(MacOsError::InvalidConfiguration(
                    "Cleartext WebSocket is only allowed for loopback connections; use https:// or wss://".to_string(),
                ));
            }
            (false, "ws".to_string())
        }
        Some(s) if s.eq_ignore_ascii_case("http") => {
            if !is_loopback {
                return Err(MacOsError::InvalidConfiguration(
                    "Cleartext WebSocket is only allowed for loopback connections; use https:// or wss://".to_string(),
                ));
            }
            (false, "ws".to_string())
        }
        Some(s) => {
            return Err(MacOsError::InvalidConfiguration(format!(
                "Unsupported scheme '{}': must be wss://, https://, ws://, or http://",
                s
            )));
        }
        None => {
            if is_loopback {
                (false, "ws".to_string())
            } else {
                (true, "wss".to_string())
            }
        }
    };

    let port = explicit_port.unwrap_or(if is_tls { 443 } else { 8088 });
    if port == 0 {
        return Err(MacOsError::InvalidConfiguration(
            "Port 0 is not permitted".to_string(),
        ));
    }

    let host_formatted = if host.contains(':') && !host.starts_with('[') {
        format!("[{}]", host)
    } else {
        host.to_string()
    };

    let raw = if (is_tls && port == 443) || (!is_tls && port == 80) {
        format!("{}://{}", scheme, host_formatted)
    } else {
        format!("{}://{}:{}", scheme, host_formatted, port)
    };

    Ok(ParsedEndpoint {
        raw,
        scheme,
        host: host.to_string(),
        port,
        is_loopback,
        is_tls,
    })
}

/// Validates a custom remote endpoint.
pub fn validate_remote_endpoint(endpoint: &str) -> Result<ParsedEndpoint, MacOsError> {
    parse_and_validate_endpoint(endpoint)
}

/// Loads and validates configuration from a file.
pub fn load_config(path: &Path) -> Result<GatewayConfig, MacOsError> {
    validate_outside_cellar(path, "config")?;

    if !path.exists() {
        return Err(MacOsError::Io(std::io::Error::new(
            std::io::ErrorKind::NotFound,
            format!("Config file not found at: {path:?}"),
        )));
    }

    ensure_file_user_only(path)?;

    let raw = fs::read_to_string(path)?;
    let config: GatewayConfig = toml::from_str(&raw)?;
    config.validate()?;

    Ok(config)
}

/// Saves configuration atomically to a file with `0o600` permissions.
pub fn save_config(path: &Path, config: &GatewayConfig) -> Result<(), MacOsError> {
    validate_outside_cellar(path, "config")?;
    config.validate()?;

    let toml_str = config.to_toml_string()?;
    atomic_write_secret_file(path, toml_str.as_bytes())
}

/// Ensures a valid configuration file exists at `path`.
///
/// If file exists, loads and returns it (`was_created = false`).
/// If absent, writes default configuration atomically and returns it (`was_created = true`).
pub fn ensure_config(path: &Path) -> Result<(GatewayConfig, bool), MacOsError> {
    if path.exists() {
        let config = load_config(path)?;
        Ok((config, false))
    } else {
        let config = GatewayConfig::default();
        save_config(path, &config)?;
        Ok((config, true))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn test_default_config_valid() {
        let config = GatewayConfig::default();
        assert_eq!(config.host, "127.0.0.1");
        assert_eq!(config.port, 8088);
        assert!(config.validate().is_ok());
    }

    #[test]
    fn test_non_loopback_config_rejected() {
        let config = GatewayConfig {
            host: "0.0.0.0".to_string(),
            ..GatewayConfig::default()
        };
        assert!(config.validate().is_err());

        let config = GatewayConfig {
            host: "192.168.1.1".to_string(),
            ..GatewayConfig::default()
        };
        assert!(config.validate().is_err());
    }

    #[test]
    fn test_cellar_config_paths_rejected() {
        let config = GatewayConfig {
            token_path: Some(PathBuf::from("/opt/homebrew/Cellar/cmux-pocket/token")),
            ..GatewayConfig::default()
        };
        assert!(config.validate().is_err());
    }

    #[test]
    fn test_save_load_config_roundtrip() {
        let tmp = tempdir().unwrap();
        let config_path = tmp.path().join("config.toml");
        let config = GatewayConfig {
            port: 8089,
            host: "localhost".to_string(),
            ..GatewayConfig::default()
        };
        save_config(&config_path, &config).unwrap();

        let loaded = load_config(&config_path).unwrap();
        assert_eq!(loaded.port, 8089);
        assert_eq!(loaded.host, "localhost");
    }

    #[test]
    fn test_preserve_unknown_keys() {
        let toml_raw = r#"
host = "127.0.0.1"
port = 8088
future_feature_flag = true
custom_timeout_ms = 5000
"#;
        let tmp = tempdir().unwrap();
        let config_path = tmp.path().join("config.toml");
        atomic_write_secret_file(&config_path, toml_raw.as_bytes()).unwrap();

        let loaded = load_config(&config_path).unwrap();
        assert_eq!(loaded.port, 8088);
        assert!(loaded.extra.contains_key("future_feature_flag"));
        assert!(loaded.extra.contains_key("custom_timeout_ms"));

        // Save back and verify unknown keys persisted
        save_config(&config_path, &loaded).unwrap();
        let reloaded_raw = fs::read_to_string(&config_path).unwrap();
        assert!(reloaded_raw.contains("future_feature_flag = true"));
        assert!(reloaded_raw.contains("custom_timeout_ms = 5000"));
    }

    #[test]
    fn test_endpoint_validation_and_parsing() {
        let ep = parse_and_validate_endpoint("wss://mac.ts.net").unwrap();
        assert_eq!(ep.scheme, "wss");
        assert_eq!(ep.host, "mac.ts.net");
        assert_eq!(ep.port, 443);
        assert!(!ep.is_loopback);
        assert!(ep.is_tls);
        assert_eq!(ep.raw, "wss://mac.ts.net");

        let ep = parse_and_validate_endpoint("https://tunnel.example.com:8443").unwrap();
        assert_eq!(ep.scheme, "wss");
        assert_eq!(ep.host, "tunnel.example.com");
        assert_eq!(ep.port, 8443);
        assert!(!ep.is_loopback);
        assert!(ep.is_tls);
        assert_eq!(ep.raw, "wss://tunnel.example.com:8443");

        let ep = parse_and_validate_endpoint("mac.example.ts.net").unwrap();
        assert_eq!(ep.scheme, "wss");
        assert_eq!(ep.host, "mac.example.ts.net");
        assert_eq!(ep.port, 443);
        assert!(!ep.is_loopback);
        assert!(ep.is_tls);

        let ep = parse_and_validate_endpoint("ws://127.0.0.1:8088").unwrap();
        assert_eq!(ep.scheme, "ws");
        assert_eq!(ep.host, "127.0.0.1");
        assert_eq!(ep.port, 8088);
        assert!(ep.is_loopback);
        assert!(!ep.is_tls);

        let ep = parse_and_validate_endpoint("127.0.0.1:8088").unwrap();
        assert_eq!(ep.scheme, "ws");
        assert_eq!(ep.host, "127.0.0.1");
        assert_eq!(ep.port, 8088);
        assert!(ep.is_loopback);

        assert!(parse_and_validate_endpoint("ws://example.com:8088").is_err());
        assert!(parse_and_validate_endpoint("http://192.168.1.10:8088").is_err());
        assert!(parse_and_validate_endpoint("0.0.0.0:8088").is_err());
        assert!(parse_and_validate_endpoint("").is_err());
    }

    #[test]
    fn test_endpoint_config_roundtrip() {
        let tmp = tempdir().unwrap();
        let config_path = tmp.path().join("config.toml");
        let config = GatewayConfig {
            port: 8088,
            host: "127.0.0.1".to_string(),
            endpoint: Some("wss://custom.domain.com:8443".to_string()),
            ..GatewayConfig::default()
        };
        config.validate().unwrap();
        save_config(&config_path, &config).unwrap();

        let loaded = load_config(&config_path).unwrap();
        assert_eq!(
            loaded.endpoint.as_deref(),
            Some("wss://custom.domain.com:8443")
        );
        loaded.validate().unwrap();
    }
}

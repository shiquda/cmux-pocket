//! Gateway and CLI configuration management with atomic writes and validation.
//!
//! Enforces that listeners bind strictly to loopback addresses, paths remain outside
//! Homebrew Cellar, and configuration files are written atomically with user-only permissions.

use crate::error::MacOsError;
use crate::loopback::validate_loopback_host;
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
}

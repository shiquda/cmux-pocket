//! Error types for macOS lifecycle, config, paths, and launchd management.

use std::net::IpAddr;
use std::path::PathBuf;
use thiserror::Error;

/// Specific error conditions for loopback validation.
#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum LoopbackError {
    #[error(
        "Host '{0}' is not a loopback address (must be 127.0.0.1, localhost, or 127.0.0.0/8 / ::1)"
    )]
    NonLoopbackHost(String),

    #[error("IP address '{0}' is not a loopback address")]
    NonLoopbackIp(IpAddr),

    #[error("Wildcard bind '{0}' is forbidden; Gateway must bind only to loopback")]
    WildcardBindForbidden(String),

    #[error("Invalid IP or socket address format: '{0}'")]
    InvalidAddress(String),

    #[error("URL '{0}' does not target a loopback host")]
    NonLoopbackUrl(String),
}

/// Comprehensive errors for macOS platform primitives.
#[derive(Debug, Error)]
pub enum MacOsError {
    #[error("Loopback error: {0}")]
    Loopback(#[from] LoopbackError),

    #[error("Path for {name} is inside Homebrew Cellar, which is prohibited: {path:?}")]
    CellarPathForbidden { name: String, path: PathBuf },

    #[error("Insecure file permissions for '{path:?}': mode is {mode:#o}, expected mode with mask {expected_mask:#o} (must be user-only)")]
    InsecurePermissions {
        path: PathBuf,
        mode: u32,
        expected_mask: u32,
    },

    #[error("Token file at '{0}' exists but is empty")]
    TokenEmpty(PathBuf),

    #[error("Token file not found at '{0}'")]
    TokenNotFound(PathBuf),

    #[error("Token contains invalid or unsafe characters")]
    InvalidTokenFormat,

    #[error("Unable to determine user home directory")]
    HomeDirNotFound,

    #[error("Launchd plist error: {0}")]
    Plist(String),

    #[error("Invalid configuration: {0}")]
    InvalidConfiguration(String),

    #[error("IO error: {0}")]
    Io(#[from] std::io::Error),

    #[error("TOML serialization error: {0}")]
    TomlSerialize(#[from] toml::ser::Error),

    #[error("TOML deserialization error: {0}")]
    TomlDeserialize(#[from] toml::de::Error),
}

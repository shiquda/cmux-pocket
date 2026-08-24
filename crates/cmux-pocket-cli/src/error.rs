//! Error types and exit codes for cmux-pocket CLI.

use cmux_pocket_cmux::CmuxError;
use cmux_pocket_gateway::GatewayError;
use cmux_pocket_macos::MacOsError;
use serde::{Serialize, Serializer};
use std::fmt;

/// Standard CLI exit codes as defined by the cmux-pocket specification.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(i32)]
pub enum CliExitCode {
    /// Success or already in desired state (0).
    Success = 0,
    /// Invalid command line arguments or invalid usage (2).
    InvalidUsage = 2,
    /// Missing or invalid configuration or token (3).
    ConfigOrTokenError = 3,
    /// Dependency unavailable: cmux, launchd, port conflict, or gateway unreachable (4).
    DependencyUnavailable = 4,
    /// Runtime or internal system failure (5).
    RuntimeFailure = 5,
}

impl CliExitCode {
    pub fn as_i32(self) -> i32 {
        self as i32
    }
}

impl Serialize for CliExitCode {
    fn serialize<S>(&self, serializer: S) -> Result<S::Ok, S::Error>
    where
        S: Serializer,
    {
        serializer.serialize_i32(self.as_i32())
    }
}

impl fmt::Display for CliExitCode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.as_i32())
    }
}

/// Unified error type for the cmux-pocket CLI.
#[derive(Debug, thiserror::Error)]
pub enum CliError {
    #[error("Invalid argument or command: {0}")]
    InvalidUsage(String),

    #[error("Configuration or token error: {0}")]
    ConfigOrToken(String),

    #[error("Dependency unavailable: {0}")]
    DependencyUnavailable(String),

    #[error("Runtime failure: {0}")]
    RuntimeFailure(String),

    #[error(transparent)]
    MacOs(#[from] MacOsError),

    #[error(transparent)]
    Loopback(#[from] cmux_pocket_macos::LoopbackError),
    #[error(transparent)]
    Cmux(#[from] CmuxError),

    #[error(transparent)]
    Gateway(#[from] GatewayError),

    #[error(transparent)]
    Io(#[from] std::io::Error),

    #[error(transparent)]
    TomlSer(#[from] toml::ser::Error),

    #[error(transparent)]
    TomlDe(#[from] toml::de::Error),

    #[error(transparent)]
    Json(#[from] serde_json::Error),
}

impl CliError {
    /// Maps this error to the appropriate process exit code.
    pub fn exit_code(&self) -> CliExitCode {
        match self {
            CliError::InvalidUsage(_) | CliError::Loopback(_) => CliExitCode::InvalidUsage,
            CliError::ConfigOrToken(_) => CliExitCode::ConfigOrTokenError,
            CliError::DependencyUnavailable(_) => CliExitCode::DependencyUnavailable,
            CliError::RuntimeFailure(_) => CliExitCode::RuntimeFailure,
            CliError::MacOs(e) => match e {
                MacOsError::TokenNotFound(_)
                | MacOsError::TokenEmpty(_)
                | MacOsError::InvalidTokenFormat
                | MacOsError::InsecurePermissions { .. }
                | MacOsError::InvalidConfiguration(_)
                | MacOsError::CellarPathForbidden { .. } => CliExitCode::ConfigOrTokenError,
                MacOsError::Loopback(_) => CliExitCode::InvalidUsage,
                _ => CliExitCode::RuntimeFailure,
            },
            CliError::Cmux(e) => match e {
                CmuxError::NonZeroExit { .. }
                | CmuxError::Timeout { .. }
                | CmuxError::Unavailable { .. } => CliExitCode::DependencyUnavailable,
                CmuxError::ParseError { .. } | CmuxError::Protocol(_) => {
                    CliExitCode::RuntimeFailure
                }
                CmuxError::Io(_) => CliExitCode::DependencyUnavailable,
            },
            CliError::Gateway(e) => match e {
                GatewayError::AuthFailed(_) => CliExitCode::ConfigOrTokenError,
                GatewayError::Io(io_err) => match io_err.kind() {
                    std::io::ErrorKind::AddrInUse
                    | std::io::ErrorKind::ConnectionRefused
                    | std::io::ErrorKind::ConnectionReset => CliExitCode::DependencyUnavailable,
                    _ => CliExitCode::RuntimeFailure,
                },
                GatewayError::BackendUnavailable(_) => CliExitCode::DependencyUnavailable,
                GatewayError::Cmux(_) => CliExitCode::DependencyUnavailable,
                _ => CliExitCode::RuntimeFailure,
            },
            CliError::Io(e) => match e.kind() {
                std::io::ErrorKind::NotFound | std::io::ErrorKind::PermissionDenied => {
                    CliExitCode::ConfigOrTokenError
                }
                std::io::ErrorKind::AddrInUse
                | std::io::ErrorKind::ConnectionRefused
                | std::io::ErrorKind::ConnectionReset => CliExitCode::DependencyUnavailable,
                _ => CliExitCode::RuntimeFailure,
            },
            CliError::TomlSer(_) | CliError::TomlDe(_) => CliExitCode::ConfigOrTokenError,
            CliError::Json(_) => CliExitCode::RuntimeFailure,
        }
    }
}

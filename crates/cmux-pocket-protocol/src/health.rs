use serde::{Deserialize, Serialize};
use std::fmt;

/// Health state of the cmux backend connection.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "status", rename_all = "snake_case")]
pub enum BackendHealth {
    Healthy,
    Unhealthy { reason: String },
    Recovering,
}

impl BackendHealth {
    pub fn healthy() -> Self {
        Self::Healthy
    }

    pub fn unhealthy(reason: impl Into<String>) -> Self {
        Self::Unhealthy {
            reason: reason.into(),
        }
    }

    pub fn recovering() -> Self {
        Self::Recovering
    }

    pub fn is_healthy(&self) -> bool {
        matches!(self, Self::Healthy)
    }

    pub fn is_unhealthy(&self) -> bool {
        matches!(self, Self::Unhealthy { .. })
    }

    pub fn is_recovering(&self) -> bool {
        matches!(self, Self::Recovering)
    }

    pub fn reason(&self) -> Option<&str> {
        match self {
            Self::Unhealthy { reason } => Some(reason.as_str()),
            _ => None,
        }
    }

    pub fn as_status_str(&self) -> &'static str {
        match self {
            Self::Healthy => "healthy",
            Self::Unhealthy { .. } => "unhealthy",
            Self::Recovering => "recovering",
        }
    }
}

impl fmt::Display for BackendHealth {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Healthy => write!(f, "healthy"),
            Self::Unhealthy { reason } => write!(f, "unhealthy: {reason}"),
            Self::Recovering => write!(f, "recovering"),
        }
    }
}

/// Errors originating in the protocol parsing, normalization, and envelope layers.
#[derive(Debug, thiserror::Error)]
pub enum ProtocolError {
    #[error("JSON serialization error: {0}")]
    Json(#[from] serde_json::Error),

    #[error("Authentication failed: {0}")]
    AuthFailed(String),

    #[error("Invalid request: {0}")]
    InvalidRequest(String),

    #[error("Method not found: {0}")]
    MethodNotFound(String),

    #[error("Invalid parameters: {0}")]
    InvalidParams(String),

    #[error("Normalization error: {0}")]
    Normalization(String),

    #[error("Backend unavailable: {0}")]
    BackendUnavailable(String),

    #[error("Internal protocol error: {0}")]
    Internal(String),
}

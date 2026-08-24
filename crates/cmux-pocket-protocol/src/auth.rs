use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

/// Protocol server version reported to Android clients.
pub const PROTOCOL_SERVER_VERSION: &str = "2.0.0";

/// Standard WebSocket close code for authentication rejection.
pub const WS_CLOSE_AUTH_FAILED: u16 = 1008;

/// Reason for authentication failure when token is invalid.
pub const AUTH_REASON_INVALID_TOKEN: &str = "invalid_token";

/// Reason for authentication failure when client sent non-auth first frame.
pub const AUTH_REASON_UNAUTHENTICATED: &str = "unauthenticated";

// Capability identifiers supported by cmux-pocket Gateway v2
pub const CAP_RENDER_GRID: &str = "terminal.render_grid.v1";
pub const CAP_INPUT_ORDERED: &str = "terminal.input.ordered.v1";
pub const CAP_WORKSPACE_CHANGES: &str = "workspace.changes.v1";
pub const CAP_EVENTS: &str = "events.v1";
pub const CAP_CLIENT_FOCUS: &str = "client_focus.v1";
pub const CAP_MULTI_SURFACE: &str = "multi_surface.v1";

/// List of all default capabilities advertised to clients on successful auth.
pub const ALL_CAPABILITIES: &[&str] = &[
    CAP_RENDER_GRID,
    CAP_INPUT_ORDERED,
    CAP_WORKSPACE_CHANGES,
    CAP_EVENTS,
    CAP_CLIENT_FOCUS,
    CAP_MULTI_SURFACE,
];

fn default_auth_type() -> String {
    "auth".to_string()
}

fn default_auth_ok_type() -> String {
    "auth_ok".to_string()
}

fn default_auth_error_type() -> String {
    "auth_error".to_string()
}

fn default_server_version() -> String {
    PROTOCOL_SERVER_VERSION.to_string()
}

pub fn default_capabilities() -> Vec<String> {
    ALL_CAPABILITIES.iter().map(|s| (*s).to_string()).collect()
}

/// First frame sent by an Android client to authenticate with the Gateway.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AuthRequest {
    #[serde(default = "default_auth_type")]
    pub r#type: String,
    pub token: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub client_id: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl AuthRequest {
    pub fn new(token: impl Into<String>) -> Self {
        Self {
            r#type: default_auth_type(),
            token: token.into(),
            client_id: Some("android-client".to_string()),
            extra: Map::new(),
        }
    }

    pub fn with_client_id(token: impl Into<String>, client_id: impl Into<String>) -> Self {
        Self {
            r#type: default_auth_type(),
            token: token.into(),
            client_id: Some(client_id.into()),
            extra: Map::new(),
        }
    }
}

/// Successful authentication response returned to the client.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AuthOk {
    #[serde(default = "default_auth_ok_type")]
    pub r#type: String,
    pub session_id: String,
    #[serde(default = "default_server_version")]
    pub server_version: String,
    #[serde(default = "default_capabilities")]
    pub capabilities: Vec<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl AuthOk {
    pub fn new(session_id: impl Into<String>) -> Self {
        Self {
            r#type: default_auth_ok_type(),
            session_id: session_id.into(),
            server_version: default_server_version(),
            capabilities: default_capabilities(),
            extra: Map::new(),
        }
    }

    pub fn with_capabilities(
        session_id: impl Into<String>,
        server_version: impl Into<String>,
        capabilities: Vec<String>,
    ) -> Self {
        Self {
            r#type: default_auth_ok_type(),
            session_id: session_id.into(),
            server_version: server_version.into(),
            capabilities,
            extra: Map::new(),
        }
    }
}

/// Authentication error response returned when credentials fail or are missing.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AuthError {
    #[serde(default = "default_auth_error_type")]
    pub r#type: String,
    pub reason: String,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl AuthError {
    pub fn new(reason: impl Into<String>) -> Self {
        Self {
            r#type: default_auth_error_type(),
            reason: reason.into(),
            extra: Map::new(),
        }
    }

    pub fn invalid_token() -> Self {
        Self::new(AUTH_REASON_INVALID_TOKEN)
    }

    pub fn unauthenticated() -> Self {
        Self::new(AUTH_REASON_UNAUTHENTICATED)
    }
}

/// Polymorphic container representing either an `auth_ok` or `auth_error` response.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum AuthResponse {
    #[serde(rename = "auth_ok")]
    Ok(AuthOkPayload),
    #[serde(rename = "auth_error")]
    Error(AuthErrorPayload),
}

/// Inner payload of an `auth_ok` frame when tagged by `type`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AuthOkPayload {
    pub session_id: String,
    #[serde(default = "default_server_version")]
    pub server_version: String,
    #[serde(default = "default_capabilities")]
    pub capabilities: Vec<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

/// Inner payload of an `auth_error` frame when tagged by `type`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AuthErrorPayload {
    pub reason: String,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl AuthResponse {
    pub fn ok(session_id: impl Into<String>) -> Self {
        Self::Ok(AuthOkPayload {
            session_id: session_id.into(),
            server_version: default_server_version(),
            capabilities: default_capabilities(),
            extra: Map::new(),
        })
    }

    pub fn error(reason: impl Into<String>) -> Self {
        Self::Error(AuthErrorPayload {
            reason: reason.into(),
            extra: Map::new(),
        })
    }

    pub fn is_ok(&self) -> bool {
        matches!(self, Self::Ok(_))
    }

    pub fn session_id(&self) -> Option<&str> {
        match self {
            Self::Ok(payload) => Some(&payload.session_id),
            Self::Error(_) => None,
        }
    }

    pub fn reason(&self) -> Option<&str> {
        match self {
            Self::Ok(_) => None,
            Self::Error(payload) => Some(&payload.reason),
        }
    }
}

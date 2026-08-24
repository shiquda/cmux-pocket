use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::borrow::Cow;
use std::fmt;

/// Standard JSON-RPC and gateway error codes.
pub const CODE_PARSE_ERROR: i32 = -32700;
pub const CODE_INVALID_REQUEST: i32 = -32600;
pub const CODE_METHOD_NOT_FOUND: i32 = -32601;
pub const CODE_INVALID_PARAMS: i32 = -32602;
pub const CODE_INTERNAL_ERROR: i32 = -32603;
pub const CODE_BACKEND_UNAVAILABLE: i32 = -32000;

/// Identifier for JSON-RPC requests and responses, accommodating both strings and integers.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(untagged)]
pub enum RequestId {
    String(String),
    Number(i64),
}

impl RequestId {
    pub fn as_str(&self) -> Cow<'_, str> {
        match self {
            Self::String(s) => Cow::Borrowed(s.as_str()),
            Self::Number(n) => Cow::Owned(n.to_string()),
        }
    }
}

impl fmt::Display for RequestId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::String(s) => write!(f, "{s}"),
            Self::Number(n) => write!(f, "{n}"),
        }
    }
}

impl From<String> for RequestId {
    fn from(s: String) -> Self {
        Self::String(s)
    }
}

impl From<&str> for RequestId {
    fn from(s: &str) -> Self {
        Self::String(s.to_string())
    }
}

impl From<i64> for RequestId {
    fn from(n: i64) -> Self {
        Self::Number(n)
    }
}

impl From<i32> for RequestId {
    fn from(n: i32) -> Self {
        Self::Number(n as i64)
    }
}

impl From<u64> for RequestId {
    fn from(n: u64) -> Self {
        Self::Number(n as i64)
    }
}

impl From<u32> for RequestId {
    fn from(n: u32) -> Self {
        Self::Number(n as i64)
    }
}

fn default_params() -> Value {
    Value::Object(Map::new())
}

/// JSON-RPC-like request frame received from an Android client.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct JsonRpcRequest {
    pub id: RequestId,
    pub method: String,
    #[serde(default = "default_params")]
    pub params: Value,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl JsonRpcRequest {
    pub fn new(id: impl Into<RequestId>, method: impl Into<String>, params: Value) -> Self {
        Self {
            id: id.into(),
            method: method.into(),
            params,
            extra: Map::new(),
        }
    }

    pub fn without_params(id: impl Into<RequestId>, method: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            method: method.into(),
            params: default_params(),
            extra: Map::new(),
        }
    }
}

/// JSON-RPC-like response / server event frame sent to the client.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct JsonRpcResponse {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub id: Option<RequestId>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub result: Option<Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<JsonRpcError>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub event: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub data: Option<Value>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl JsonRpcResponse {
    pub fn result(
        id: impl Into<RequestId>,
        result: impl Serialize,
    ) -> Result<Self, serde_json::Error> {
        Ok(Self {
            id: Some(id.into()),
            result: Some(serde_json::to_value(result)?),
            error: None,
            event: None,
            data: None,
            extra: Map::new(),
        })
    }

    pub fn result_value(id: impl Into<RequestId>, result: Value) -> Self {
        Self {
            id: Some(id.into()),
            result: Some(result),
            error: None,
            event: None,
            data: None,
            extra: Map::new(),
        }
    }

    pub fn error(id: Option<RequestId>, error: JsonRpcError) -> Self {
        Self {
            id,
            result: None,
            error: Some(error),
            event: None,
            data: None,
            extra: Map::new(),
        }
    }

    pub fn event(
        event: impl Into<String>,
        data: impl Serialize,
    ) -> Result<Self, serde_json::Error> {
        Ok(Self {
            id: None,
            result: None,
            error: None,
            event: Some(event.into()),
            data: Some(serde_json::to_value(data)?),
            extra: Map::new(),
        })
    }

    pub fn event_value(event: impl Into<String>, data: Value) -> Self {
        Self {
            id: None,
            result: None,
            error: None,
            event: Some(event.into()),
            data: Some(data),
            extra: Map::new(),
        }
    }

    pub fn is_result(&self) -> bool {
        self.result.is_some()
    }

    pub fn is_error(&self) -> bool {
        self.error.is_some()
    }

    pub fn is_event(&self) -> bool {
        self.event.is_some()
    }
}

/// JSON-RPC error payload object.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct JsonRpcError {
    pub code: i32,
    pub message: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub data: Option<Value>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl JsonRpcError {
    pub fn new(code: i32, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
            data: None,
            extra: Map::new(),
        }
    }

    pub fn with_data(
        code: i32,
        message: impl Into<String>,
        data: impl Serialize,
    ) -> Result<Self, serde_json::Error> {
        Ok(Self {
            code,
            message: message.into(),
            data: Some(serde_json::to_value(data)?),
            extra: Map::new(),
        })
    }

    pub fn method_not_found(method: &str) -> Self {
        Self::new(
            CODE_METHOD_NOT_FOUND,
            format!("Method '{method}' not implemented in gateway"),
        )
    }

    pub fn invalid_params(message: impl Into<String>) -> Self {
        Self::new(CODE_INVALID_PARAMS, message)
    }

    pub fn backend_unavailable(message: impl Into<String>) -> Self {
        Self::new(CODE_BACKEND_UNAVAILABLE, message)
    }

    pub fn internal_error(message: impl Into<String>) -> Self {
        Self::new(CODE_INTERNAL_ERROR, message)
    }

    pub fn parse_error(message: impl Into<String>) -> Self {
        Self::new(CODE_PARSE_ERROR, message)
    }

    pub fn invalid_request(message: impl Into<String>) -> Self {
        Self::new(CODE_INVALID_REQUEST, message)
    }
}

/// Canonical gateway RPC methods and their accepted aliases.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum RpcMethod {
    HostStatus,
    WorkspaceList,
    WorkspaceCreate,
    WorkspaceSelect,
    SurfaceCreate,
    SurfaceClose,
    SurfaceFocus,
    EventsSubscribe,
    TerminalInput,
    TerminalScroll,
    TerminalReplay,
    TerminalViewport,
}

impl RpcMethod {
    /// Resolve an incoming method name or alias to its canonical `RpcMethod` enum variant.
    pub fn from_name(name: &str) -> Option<Self> {
        match name {
            "mobile.host.status" => Some(Self::HostStatus),
            "mobile.workspace.list" | "workspace.list" => Some(Self::WorkspaceList),
            "mobile.workspace.create" => Some(Self::WorkspaceCreate),
            "mobile.workspace.select" => Some(Self::WorkspaceSelect),
            "mobile.surface.create" => Some(Self::SurfaceCreate),
            "mobile.surface.close" => Some(Self::SurfaceClose),
            "mobile.surface.focus" => Some(Self::SurfaceFocus),
            "mobile.events.subscribe" => Some(Self::EventsSubscribe),
            "mobile.terminal.input" | "terminal.input" => Some(Self::TerminalInput),
            "mobile.terminal.scroll" | "terminal.scroll" => Some(Self::TerminalScroll),
            "mobile.terminal.replay" | "terminal.replay" => Some(Self::TerminalReplay),
            "mobile.terminal.viewport" | "terminal.viewport" => Some(Self::TerminalViewport),
            _ => None,
        }
    }

    /// The canonical dot-separated method string.
    pub const fn canonical_name(&self) -> &'static str {
        match self {
            Self::HostStatus => "mobile.host.status",
            Self::WorkspaceList => "mobile.workspace.list",
            Self::WorkspaceCreate => "mobile.workspace.create",
            Self::WorkspaceSelect => "mobile.workspace.select",
            Self::SurfaceCreate => "mobile.surface.create",
            Self::SurfaceClose => "mobile.surface.close",
            Self::SurfaceFocus => "mobile.surface.focus",
            Self::EventsSubscribe => "mobile.events.subscribe",
            Self::TerminalInput => "mobile.terminal.input",
            Self::TerminalScroll => "mobile.terminal.scroll",
            Self::TerminalReplay => "mobile.terminal.replay",
            Self::TerminalViewport => "mobile.terminal.viewport",
        }
    }

    /// Check if a method name is an accepted legacy/short alias.
    pub fn is_alias(name: &str) -> bool {
        matches!(
            name,
            "workspace.list"
                | "terminal.input"
                | "terminal.scroll"
                | "terminal.replay"
                | "terminal.viewport"
        )
    }

    /// List of aliases accepted for this method.
    pub const fn aliases(&self) -> &'static [&'static str] {
        match self {
            Self::WorkspaceList => &["workspace.list"],
            Self::TerminalInput => &["terminal.input"],
            Self::TerminalScroll => &["terminal.scroll"],
            Self::TerminalReplay => &["terminal.replay"],
            Self::TerminalViewport => &["terminal.viewport"],
            _ => &[],
        }
    }
}

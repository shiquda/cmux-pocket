use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

use crate::auth::default_capabilities;
use crate::workspace::{SurfaceInfo, WorkspaceInfo};

fn default_ok_status() -> String {
    "ok".to_string()
}

fn default_workspace_name() -> String {
    "New Workspace".to_string()
}

fn default_surface_type() -> String {
    "terminal".to_string()
}

fn default_event_transport() -> String {
    "websocket".to_string()
}

fn default_viewport_columns() -> u32 {
    80
}

fn default_viewport_rows() -> u32 {
    24
}

fn default_true() -> bool {
    true
}

// ---------------------------------------------------------------------------
// Host Status
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct HostStatusResponse {
    pub mac_display_name: String,
    pub mac_app_version: String,
    #[serde(default = "default_capabilities")]
    pub capabilities: Vec<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl HostStatusResponse {
    pub fn new(mac_display_name: impl Into<String>, mac_app_version: impl Into<String>) -> Self {
        Self {
            mac_display_name: mac_display_name.into(),
            mac_app_version: mac_app_version.into(),
            capabilities: default_capabilities(),
            extra: Map::new(),
        }
    }
}

// ---------------------------------------------------------------------------
// Workspace Mutations
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WorkspaceCreateParams {
    #[serde(default = "default_workspace_name")]
    pub name: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub initial_surface: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mutation_id: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl WorkspaceCreateParams {
    pub fn new(name: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            initial_surface: None,
            mutation_id: None,
            extra: Map::new(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WorkspaceCreateResponse {
    #[serde(default = "default_ok_status")]
    pub status: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workspace: Option<WorkspaceInfo>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mutation_id: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl WorkspaceCreateResponse {
    pub fn ok(workspace: WorkspaceInfo, mutation_id: Option<String>) -> Self {
        Self {
            status: default_ok_status(),
            workspace: Some(workspace),
            mutation_id,
            extra: Map::new(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WorkspaceSelectParams {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workspace_key: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workspace_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mutation_id: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl WorkspaceSelectParams {
    pub fn target_workspace_key(&self) -> Option<&str> {
        self.workspace_key
            .as_deref()
            .or(self.workspace_id.as_deref())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WorkspaceSelectResponse {
    #[serde(default = "default_ok_status")]
    pub status: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workspace_key: Option<String>,
    #[serde(default)]
    pub host_focus_moved: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mutation_id: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl WorkspaceSelectResponse {
    pub fn ok(workspace_key: Option<String>, mutation_id: Option<String>) -> Self {
        Self {
            status: default_ok_status(),
            workspace_key,
            host_focus_moved: false,
            mutation_id,
            extra: Map::new(),
        }
    }
}

// ---------------------------------------------------------------------------
// Surface Mutations
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SurfaceCreateParams {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workspace_key: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workspace_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    #[serde(rename = "type", default = "default_surface_type")]
    pub surface_type: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mutation_id: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl SurfaceCreateParams {
    pub fn target_workspace_key(&self) -> Option<&str> {
        self.workspace_key
            .as_deref()
            .or(self.workspace_id.as_deref())
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SurfaceCreateResponse {
    #[serde(default = "default_ok_status")]
    pub status: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub surface: Option<SurfaceInfo>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mutation_id: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl SurfaceCreateResponse {
    pub fn ok(surface: SurfaceInfo, mutation_id: Option<String>) -> Self {
        Self {
            status: default_ok_status(),
            surface: Some(surface),
            mutation_id,
            extra: Map::new(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SurfaceCloseParams {
    pub surface_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workspace_key: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mutation_id: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SurfaceCloseResponse {
    #[serde(default = "default_ok_status")]
    pub status: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub surface_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mutation_id: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl SurfaceCloseResponse {
    pub fn ok(surface_id: impl Into<String>, mutation_id: Option<String>) -> Self {
        Self {
            status: default_ok_status(),
            surface_id: Some(surface_id.into()),
            mutation_id,
            extra: Map::new(),
        }
    }

    pub fn error(surface_id: impl Into<String>, mutation_id: Option<String>) -> Self {
        Self {
            status: "error".to_string(),
            surface_id: Some(surface_id.into()),
            mutation_id,
            extra: Map::new(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SurfaceFocusParams {
    pub surface_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mutation_id: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SurfaceFocusResponse {
    #[serde(default = "default_ok_status")]
    pub status: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub surface_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mutation_id: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl SurfaceFocusResponse {
    pub fn ok(surface_id: impl Into<String>, mutation_id: Option<String>) -> Self {
        Self {
            status: default_ok_status(),
            surface_id: Some(surface_id.into()),
            mutation_id,
            extra: Map::new(),
        }
    }
}

// ---------------------------------------------------------------------------
// Event Subscriptions
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EventsSubscribeParams {
    #[serde(default)]
    pub topics: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub stream_id: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct EventsSubscribeResponse {
    pub stream_id: String,
    #[serde(default)]
    pub already_subscribed: bool,
    #[serde(default = "default_event_transport")]
    pub event_transport: String,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl EventsSubscribeResponse {
    pub fn new(stream_id: impl Into<String>) -> Self {
        Self {
            stream_id: stream_id.into(),
            already_subscribed: false,
            event_transport: default_event_transport(),
            extra: Map::new(),
        }
    }
}

// ---------------------------------------------------------------------------
// Terminal Operations
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TerminalInputParams {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub surface_id: Option<String>,
    #[serde(default)]
    pub text: String,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TerminalInputResponse {
    #[serde(default = "default_ok_status")]
    pub status: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub surface_id: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl TerminalInputResponse {
    pub fn ok(surface_id: impl Into<String>) -> Self {
        Self {
            status: default_ok_status(),
            surface_id: Some(surface_id.into()),
            extra: Map::new(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct TerminalScrollParams {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub surface_id: Option<String>,
    #[serde(default)]
    pub delta_lines: f64,
    #[serde(default)]
    pub col: i32,
    #[serde(default)]
    pub row: i32,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TerminalScrollResponse {
    #[serde(default = "default_ok_status")]
    pub status: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub surface_id: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl TerminalScrollResponse {
    pub fn ok(surface_id: impl Into<String>) -> Self {
        Self {
            status: default_ok_status(),
            surface_id: Some(surface_id.into()),
            extra: Map::new(),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TerminalReplayParams {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub surface_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub max_scrollback_rows: Option<u32>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TerminalViewportParams {
    #[serde(default = "default_viewport_columns")]
    pub viewport_columns: u32,
    #[serde(default = "default_viewport_rows")]
    pub viewport_rows: u32,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TerminalViewportResponse {
    #[serde(default = "default_true")]
    pub accepted: bool,
    pub columns: u32,
    pub rows: u32,
    #[serde(default)]
    pub geometry_owner: bool,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl TerminalViewportResponse {
    pub fn new(columns: u32, rows: u32) -> Self {
        Self {
            accepted: true,
            columns,
            rows,
            geometry_owner: false,
            extra: Map::new(),
        }
    }
}

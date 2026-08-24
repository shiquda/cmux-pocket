use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

fn default_surface_type() -> String {
    "terminal".to_string()
}

/// A surface/tab within a workspace.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SurfaceInfo {
    pub id: String,
    #[serde(rename = "type", default = "default_surface_type")]
    pub surface_type: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workspace_key: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub pane_id: Option<String>,
    #[serde(default)]
    pub tab_index: i32,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub agent_state: Option<String>,
    #[serde(default)]
    pub attention: bool,
    #[serde(default)]
    pub dead: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cwd: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl SurfaceInfo {
    pub fn new(id: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            surface_type: default_surface_type(),
            title: None,
            workspace_key: None,
            pane_id: None,
            tab_index: 0,
            agent_state: None,
            attention: false,
            dead: false,
            cwd: None,
            extra: Map::new(),
        }
    }

    pub fn with_title(id: impl Into<String>, title: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            surface_type: default_surface_type(),
            title: Some(title.into()),
            workspace_key: None,
            pane_id: None,
            tab_index: 0,
            agent_state: None,
            attention: false,
            dead: false,
            cwd: None,
            extra: Map::new(),
        }
    }
}

/// A workspace containing zero or more surfaces.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WorkspaceInfo {
    pub id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub key: Option<String>,
    pub name: String,
    #[serde(default)]
    pub order: i32,
    #[serde(default)]
    pub active_on_host: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub cwd: Option<String>,
    #[serde(default)]
    pub surfaces: Vec<SurfaceInfo>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl WorkspaceInfo {
    pub fn new(id: impl Into<String>, name: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            key: None,
            name: name.into(),
            order: 0,
            active_on_host: false,
            cwd: None,
            surfaces: Vec::new(),
            extra: Map::new(),
        }
    }
}

/// Response payload for `mobile.workspace.list`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WorkspaceListResponse {
    #[serde(default)]
    pub workspaces: Vec<WorkspaceInfo>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl WorkspaceListResponse {
    pub fn new(workspaces: Vec<WorkspaceInfo>) -> Self {
        Self {
            workspaces,
            extra: Map::new(),
        }
    }
}

/// Event broadcast on `workspace.tree` topic for workspace or surface changes.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct WorkspaceTreeEvent {
    pub action: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workspace: Option<WorkspaceInfo>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub surface: Option<SurfaceInfo>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub surface_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub workspaces: Option<Vec<WorkspaceInfo>>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mutation_id: Option<String>,
    #[serde(flatten)]
    pub extra: Map<String, Value>,
}

impl WorkspaceTreeEvent {
    pub fn sync(workspaces: Vec<WorkspaceInfo>) -> Self {
        Self {
            action: "sync".to_string(),
            workspace: None,
            surface: None,
            surface_id: None,
            workspaces: Some(workspaces),
            mutation_id: None,
            extra: Map::new(),
        }
    }

    pub fn workspace_created(workspace: WorkspaceInfo, mutation_id: Option<String>) -> Self {
        Self {
            action: "workspace_created".to_string(),
            workspace: Some(workspace),
            surface: None,
            surface_id: None,
            workspaces: None,
            mutation_id,
            extra: Map::new(),
        }
    }

    pub fn surface_created(surface: SurfaceInfo, mutation_id: Option<String>) -> Self {
        Self {
            action: "surface_created".to_string(),
            workspace: None,
            surface: Some(surface),
            surface_id: None,
            workspaces: None,
            mutation_id,
            extra: Map::new(),
        }
    }

    pub fn surface_closed(surface_id: impl Into<String>, mutation_id: Option<String>) -> Self {
        Self {
            action: "surface_closed".to_string(),
            workspace: None,
            surface: None,
            surface_id: Some(surface_id.into()),
            workspaces: None,
            mutation_id,
            extra: Map::new(),
        }
    }
}

/// Computes a deterministic JSON signature for a workspace tree to detect changes.
/// Matches Python's `workspace_tree_signature(workspaces)`.
type SurfaceSignature<'a> = (&'a str, Option<&'a str>, Option<&'a str>);
type WorkspaceSignature<'a> = (&'a str, &'a str, Option<&'a str>, Vec<SurfaceSignature<'a>>);

pub fn workspace_tree_signature(workspaces: &[WorkspaceInfo]) -> String {
    let payload: Vec<WorkspaceSignature<'_>> = workspaces
        .iter()
        .map(|ws| {
            let surfaces = ws
                .surfaces
                .iter()
                .map(|s| (s.id.as_str(), s.title.as_deref(), s.cwd.as_deref()))
                .collect();
            (
                ws.id.as_str(),
                ws.name.as_str(),
                ws.cwd.as_deref(),
                surfaces,
            )
        })
        .collect();

    serde_json::to_string(&payload).unwrap_or_else(|_| "[]".to_string())
}

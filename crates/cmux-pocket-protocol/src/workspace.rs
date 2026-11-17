use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};

fn default_surface_type() -> String {
    "terminal".to_string()
}

/// Canonical agent lifecycle values the Gateway reports for a surface.
///
/// cmux reports several spellings for the same turn state (`working`/`busy`,
/// `needsInput`/`needs_input`). Clients must not re-implement that mapping, so
/// the Gateway normalizes to these three values and omits the field entirely
/// when it cannot confirm the state.
pub mod agent_lifecycle {
    /// The agent is producing a turn.
    pub const WORKING: &str = "working";
    /// The agent is blocked on the user (permission prompt, question, plan).
    pub const NEEDS_INPUT: &str = "needs_input";
    /// The surface has no agent turn in flight.
    pub const IDLE: &str = "idle";
}

/// Normalizes a cmux-reported agent state into a canonical lifecycle value.
///
/// `running` is deliberately not mapped: cmux's session store uses it for "the
/// agent process is alive", which says nothing about a turn in flight. Treating
/// it as working is exactly the stale-state bug this mapping exists to avoid.
pub fn normalize_agent_state(raw: &str) -> Option<&'static str> {
    match raw.trim().to_ascii_lowercase().as_str() {
        "working" | "busy" | "launching" => Some(agent_lifecycle::WORKING),
        "needsinput" | "needs_input" | "needs-input" | "waiting" => Some(agent_lifecycle::NEEDS_INPUT),
        "idle" | "ended" | "done" => Some(agent_lifecycle::IDLE),
        _ => None,
    }
}

/// Orders canonical lifecycle values so a surface with several agent sessions
/// reports the most actionable one: input needed beats working beats idle.
pub fn agent_state_rank(state: &str) -> u8 {
    match state {
        agent_lifecycle::NEEDS_INPUT => 3,
        agent_lifecycle::WORKING => 2,
        agent_lifecycle::IDLE => 1,
        _ => 0,
    }
}

/// A surface/tab within a workspace.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SurfaceInfo {
    pub id: String,
    /// Durable surface identity (the UUID cmux reports for agent sessions).
    ///
    /// Only present when the host answers `cmux tree --id-format both`; the
    /// wire id stays the short `surface:N` ref that every RPC accepts.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub surface_uuid: Option<String>,
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
            surface_uuid: None,
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
            surface_uuid: None,
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

    pub fn workspace_renamed(workspace: WorkspaceInfo, mutation_id: Option<String>) -> Self {
        Self {
            action: "workspace_renamed".to_string(),
            workspace: Some(workspace),
            surface: None,
            surface_id: None,
            workspaces: None,
            mutation_id,
            extra: Map::new(),
        }
    }

    pub fn workspace_closed(workspace_key: impl Into<String>, mutation_id: Option<String>) -> Self {
        let mut extra = Map::new();
        extra.insert("workspace_key".to_string(), Value::String(workspace_key.into()));
        Self {
            action: "workspace_closed".to_string(),
            workspace: None,
            surface: None,
            surface_id: None,
            workspaces: None,
            mutation_id,
            extra,
        }
    }

    pub fn surface_renamed(surface: SurfaceInfo, mutation_id: Option<String>) -> Self {
        Self {
            action: "surface_renamed".to_string(),
            workspace: None,
            surface: Some(surface),
            surface_id: None,
            workspaces: None,
            mutation_id,
            extra: Map::new(),
        }
    }
}

/// Computes a deterministic JSON signature for a workspace tree to detect changes.
/// Matches Python's `workspace_tree_signature(workspaces)`.
///
/// Agent lifecycle is part of the signature: a turn starting or finishing must
/// reach clients even when the tree's structure and titles are unchanged.
type SurfaceSignature<'a> = (
    &'a str,
    Option<&'a str>,
    Option<&'a str>,
    Option<&'a str>,
    bool,
);
type WorkspaceSignature<'a> = (&'a str, &'a str, Option<&'a str>, Vec<SurfaceSignature<'a>>);

pub fn workspace_tree_signature(workspaces: &[WorkspaceInfo]) -> String {
    let payload: Vec<WorkspaceSignature<'_>> = workspaces
        .iter()
        .map(|ws| {
            let surfaces = ws
                .surfaces
                .iter()
                .map(|s| {
                    (
                        s.id.as_str(),
                        s.title.as_deref(),
                        s.cwd.as_deref(),
                        s.agent_state.as_deref(),
                        s.attention,
                    )
                })
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

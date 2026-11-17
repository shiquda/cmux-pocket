//! Parser for `cmux current --json` agent lifecycle facts.
//!
//! `cmux tree` carries no agent state, and the hook session store's
//! `agent_lifecycle` only says whether an agent process is alive (a dead
//! process can still be recorded as `running`). `cmux current` is cmux's own
//! merged projection of the turn state it already knows, tagged with a
//! freshness per resource, so it is the only source that can distinguish
//! "working", "needs input", "idle" and "cannot confirm".

use cmux_pocket_protocol::workspace::{agent_lifecycle, agent_state_rank, normalize_agent_state};
use cmux_pocket_protocol::{SurfaceInfo, WorkspaceInfo};
use serde_json::Value;
use std::collections::HashMap;

use crate::error::CmuxError;

/// Prefix of the local terminal resource reference in a `cmux current` item.
const LOCAL_TERMINAL_PREFIX: &str = "local/terminal/";

/// One surface's confirmed agent lifecycle.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SurfaceAgentLifecycle {
    /// Durable surface UUID, the same identity the hook store and events use.
    pub surface_uuid: String,
    /// Canonical lifecycle value from [`agent_lifecycle`].
    pub state: &'static str,
    /// Agent kind reported by cmux (`omp`, `claude`, `codex`, ...).
    pub agent_kind: Option<String>,
    /// Agent session id currently driving the surface.
    pub session_id: Option<String>,
}

/// Parses `cmux current --json` into per-surface lifecycle states.
///
/// Items that are not current local terminals, or whose freshness is not
/// `current`, are skipped: an unconfirmed projection must reach clients as
/// "unknown" rather than as a state that happens to be plausible.
pub fn parse_agent_lifecycles(json_str: &str) -> Result<Vec<SurfaceAgentLifecycle>, CmuxError> {
    let val: Value = serde_json::from_str(json_str)
        .map_err(|e| CmuxError::parse_error(format!("invalid JSON in current output: {e}")))?;
    parse_agent_lifecycles_value(&val)
}

/// Parses an already-decoded `cmux current` payload.
pub fn parse_agent_lifecycles_value(
    val: &Value,
) -> Result<Vec<SurfaceAgentLifecycle>, CmuxError> {
    let items = val
        .get("items")
        .and_then(Value::as_array)
        .ok_or_else(|| CmuxError::parse_error("current output has no items array"))?;

    let mut states = Vec::new();
    for item in items {
        let Some(obj) = item.as_object() else {
            continue;
        };

        let freshness = obj
            .get("freshness")
            .and_then(|f| f.get("state"))
            .and_then(Value::as_str);
        if freshness != Some("current") {
            continue;
        }

        let Some(surface_uuid) = obj
            .get("resource_ref")
            .and_then(Value::as_str)
            .and_then(|reference| reference.strip_prefix(LOCAL_TERMINAL_PREFIX))
            .filter(|uuid| !uuid.is_empty())
        else {
            continue;
        };

        let agents = obj
            .get("agents")
            .and_then(Value::as_array)
            .map(Vec::as_slice)
            .unwrap_or(&[]);

        // A resource cmux reports with no agent session is confirmed idle. A
        // resource with sessions whose states are all unrecognised is not: it
        // stays unknown so the client never sees a fabricated idle.
        let mut best: Option<&'static str> = None;
        let mut agent_kind: Option<String> = None;
        let mut session_id: Option<String> = None;
        for agent in agents {
            let Some(state) = agent
                .get("state")
                .and_then(Value::as_str)
                .and_then(normalize_agent_state)
            else {
                continue;
            };
            if best.is_none_or(|current| agent_state_rank(state) > agent_state_rank(current)) {
                best = Some(state);
                agent_kind = agent
                    .get("kind")
                    .and_then(Value::as_str)
                    .map(ToString::to_string);
                session_id = agent
                    .get("session_id")
                    .and_then(Value::as_str)
                    .map(ToString::to_string);
            }
        }

        let state = match (best, agents.is_empty()) {
            (Some(state), _) => state,
            (None, true) => agent_lifecycle::IDLE,
            (None, false) => continue,
        };

        states.push(SurfaceAgentLifecycle {
            surface_uuid: surface_uuid.to_string(),
            state,
            agent_kind,
            session_id,
        });
    }

    Ok(states)
}

/// Applies parsed lifecycle states to freshly parsed workspaces.
///
/// Surfaces whose UUID is missing (older cmux without `--id-format both`) or
/// absent from the snapshot keep `agent_state: None`, which clients render as
/// unknown instead of idle.
pub fn apply_agent_lifecycles(
    workspaces: &mut [WorkspaceInfo],
    states: &[SurfaceAgentLifecycle],
) {
    if states.is_empty() {
        return;
    }

    let by_uuid: HashMap<&str, &SurfaceAgentLifecycle> = states
        .iter()
        .map(|state| (state.surface_uuid.as_str(), state))
        .collect();

    for workspace in workspaces.iter_mut() {
        for surface in workspace.surfaces.iter_mut() {
            apply_surface_lifecycle(surface, &by_uuid);
        }
    }
}

fn apply_surface_lifecycle(
    surface: &mut SurfaceInfo,
    by_uuid: &HashMap<&str, &SurfaceAgentLifecycle>,
) {
    if surface.surface_type != "terminal" {
        return;
    }
    let Some(uuid) = surface.surface_uuid.as_deref() else {
        return;
    };
    let Some(state) = by_uuid.get(uuid) else {
        return;
    };

    surface.agent_state = Some(state.state.to_string());
    surface.attention = state.state == agent_lifecycle::NEEDS_INPUT;
    if let Some(kind) = &state.agent_kind {
        surface
            .extra
            .insert("agent_kind".to_string(), Value::String(kind.clone()));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn item(uuid: &str, freshness: &str, agents: Value) -> Value {
        json!({
            "resource_ref": format!("local/terminal/{uuid}"),
            "kind": "terminal",
            "freshness": { "state": freshness },
            "agents": agents,
        })
    }

    fn snapshot(items: Vec<Value>) -> Value {
        json!({ "items": items, "truncated": false })
    }

    #[test]
    fn working_agent_is_reported_as_working() {
        let states = parse_agent_lifecycles_value(&snapshot(vec![item(
            "S1",
            "current",
            json!([{ "kind": "omp", "state": "working", "session_id": "sess-1" }]),
        )]))
        .unwrap();

        assert_eq!(states.len(), 1);
        assert_eq!(states[0].state, agent_lifecycle::WORKING);
        assert_eq!(states[0].agent_kind.as_deref(), Some("omp"));
        assert_eq!(states[0].session_id.as_deref(), Some("sess-1"));
    }

    #[test]
    fn needs_input_wins_over_a_working_sibling() {
        let states = parse_agent_lifecycles_value(&snapshot(vec![item(
            "S1",
            "current",
            json!([
                { "kind": "omp", "state": "working", "session_id": "a" },
                { "kind": "omp", "state": "needsInput", "session_id": "b" },
            ]),
        )]))
        .unwrap();

        assert_eq!(states[0].state, agent_lifecycle::NEEDS_INPUT);
        assert_eq!(states[0].session_id.as_deref(), Some("b"));
    }

    #[test]
    fn stale_freshness_is_not_a_state() {
        let states = parse_agent_lifecycles_value(&snapshot(vec![
            item(
                "S1",
                "stale",
                json!([{ "kind": "omp", "state": "working" }]),
            ),
            item(
                "S2",
                "current",
                json!([{ "kind": "omp", "state": "idle" }]),
            ),
        ]))
        .unwrap();

        assert_eq!(states.len(), 1);
        assert_eq!(states[0].surface_uuid, "S2");
        assert_eq!(states[0].state, agent_lifecycle::IDLE);
    }

    #[test]
    fn surface_without_agents_is_idle() {
        let states =
            parse_agent_lifecycles_value(&snapshot(vec![item("S1", "current", json!([]))])).unwrap();

        assert_eq!(states.len(), 1);
        assert_eq!(states[0].state, agent_lifecycle::IDLE);
    }

    #[test]
    fn unrecognised_agent_state_stays_unknown() {
        let states = parse_agent_lifecycles_value(&snapshot(vec![item(
            "S1",
            "current",
            json!([{ "kind": "omp", "state": "mystery" }]),
        )]))
        .unwrap();

        assert!(states.is_empty());
    }

    #[test]
    fn non_local_resources_are_ignored() {
        let states = parse_agent_lifecycles_value(&snapshot(vec![json!({
            "resource_ref": "cloud/terminal/S1",
            "kind": "terminal",
            "freshness": { "state": "current" },
            "agents": [{ "kind": "omp", "state": "working" }],
        })]))
        .unwrap();

        assert!(states.is_empty());
    }

    #[test]
    fn applies_states_by_durable_uuid_only() {
        let mut matched = SurfaceInfo::new("surface:1");
        matched.surface_uuid = Some("S1".to_string());
        let mut unmatched = SurfaceInfo::new("surface:2");
        unmatched.surface_uuid = Some("S2".to_string());
        let no_uuid = SurfaceInfo::new("surface:3");
        let mut browser = SurfaceInfo::new("surface:4");
        browser.surface_uuid = Some("S1".to_string());
        browser.surface_type = "browser".to_string();

        let mut workspace = WorkspaceInfo::new("ws", "ws");
        workspace.surfaces = vec![matched, unmatched, no_uuid, browser];
        let mut workspaces = vec![workspace];

        apply_agent_lifecycles(
            &mut workspaces,
            &[SurfaceAgentLifecycle {
                surface_uuid: "S1".to_string(),
                state: agent_lifecycle::NEEDS_INPUT,
                agent_kind: Some("omp".to_string()),
                session_id: None,
            }],
        );

        let surfaces = &workspaces[0].surfaces;
        assert_eq!(
            surfaces[0].agent_state.as_deref(),
            Some(agent_lifecycle::NEEDS_INPUT)
        );
        assert!(surfaces[0].attention);
        assert_eq!(
            surfaces[0].extra.get("agent_kind").and_then(Value::as_str),
            Some("omp")
        );
        assert_eq!(surfaces[1].agent_state, None);
        assert_eq!(surfaces[2].agent_state, None);
        // Browser surfaces never carry terminal agent state.
        assert_eq!(surfaces[3].agent_state, None);
    }
}

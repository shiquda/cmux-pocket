//! Parser for `cmux tree --all --json` output.

use cmux_pocket_protocol::{SurfaceInfo, WorkspaceInfo};
use serde_json::Value;

use crate::error::CmuxError;

/// Parses JSON output from `cmux tree --all --json` into a list of `WorkspaceInfo`.
pub fn parse_workspace_tree(json_str: &str) -> Result<Vec<WorkspaceInfo>, CmuxError> {
    let val: Value = serde_json::from_str(json_str)
        .map_err(|e| CmuxError::parse_error(format!("invalid JSON in tree output: {e}")))?;
    parse_workspace_tree_value(&val)
}

/// Parses a `serde_json::Value` tree representation into a list of `WorkspaceInfo`.
pub fn parse_workspace_tree_value(val: &Value) -> Result<Vec<WorkspaceInfo>, CmuxError> {
    let mut workspaces = Vec::new();

    // 1. Array of workspaces directly
    if let Some(arr) = val.as_array() {
        for (idx, item) in arr.iter().enumerate() {
            if let Some(ws) = parse_single_workspace(item, idx as i32) {
                workspaces.push(ws);
            }
        }
        return Ok(workspaces);
    }

    // 2. Object with "windows" array or "workspaces" array
    if let Some(obj) = val.as_object() {
        if let Some(Value::Array(windows)) = obj.get("windows") {
            let mut order = 0i32;
            for win in windows {
                if let Some(Value::Array(ws_list)) = win.get("workspaces") {
                    for item in ws_list {
                        if let Some(ws) = parse_single_workspace(item, order) {
                            workspaces.push(ws);
                            order += 1;
                        }
                    }
                }
            }
            if !workspaces.is_empty() {
                return Ok(workspaces);
            }
        }

        if let Some(Value::Array(ws_list)) = obj.get("workspaces") {
            for (idx, item) in ws_list.iter().enumerate() {
                if let Some(ws) = parse_single_workspace(item, idx as i32) {
                    workspaces.push(ws);
                }
            }
            return Ok(workspaces);
        }
    }

    Ok(workspaces)
}

fn parse_single_workspace(item: &Value, default_order: i32) -> Option<WorkspaceInfo> {
    let obj = item.as_object()?;

    let id = obj
        .get("id")
        .and_then(Value::as_str)
        .or_else(|| obj.get("ref").and_then(Value::as_str))?
        .to_string();

    let key = obj
        .get("key")
        .and_then(Value::as_str)
        .map(ToString::to_string)
        .or_else(|| Some(id.clone()));

    let name = obj
        .get("title")
        .and_then(Value::as_str)
        .or_else(|| obj.get("name").and_then(Value::as_str))
        .or_else(|| obj.get("ref").and_then(Value::as_str))
        .unwrap_or(&id)
        .to_string();

    let order = obj
        .get("order")
        .and_then(Value::as_i64)
        .map(|v| v as i32)
        .unwrap_or(default_order);

    let active_on_host = obj
        .get("selected")
        .and_then(Value::as_bool)
        .or_else(|| obj.get("active_on_host").and_then(Value::as_bool))
        .unwrap_or(false);

    let mut surfaces = Vec::new();
    let mut resolved_cwd: Option<String> = obj
        .get("cwd")
        .and_then(Value::as_str)
        .map(ToString::to_string);

    // Parse surfaces from panes or direct surfaces array
    if let Some(Value::Array(panes)) = obj.get("panes") {
        let mut tab_idx = 0i32;
        for pane in panes {
            let pane_id = pane
                .get("id")
                .and_then(Value::as_str)
                .or_else(|| pane.get("ref").and_then(Value::as_str))
                .map(ToString::to_string);

            if let Some(Value::Array(surf_arr)) = pane.get("surfaces") {
                for surf_val in surf_arr {
                    if let Some(surf) =
                        parse_single_surface(surf_val, &id, pane_id.as_deref(), tab_idx)
                    {
                        if resolved_cwd.is_none() && surf.cwd.is_some() {
                            resolved_cwd = surf.cwd.clone();
                        }
                        surfaces.push(surf);
                        tab_idx += 1;
                    }
                }
            }
        }
    } else if let Some(Value::Array(surf_arr)) = obj.get("surfaces") {
        for (idx, surf_val) in surf_arr.iter().enumerate() {
            if let Some(surf) = parse_single_surface(surf_val, &id, None, idx as i32) {
                if resolved_cwd.is_none() && surf.cwd.is_some() {
                    resolved_cwd = surf.cwd.clone();
                }
                surfaces.push(surf);
            }
        }
    }

    Some(WorkspaceInfo {
        id,
        key,
        name,
        order,
        active_on_host,
        cwd: resolved_cwd,
        surfaces,
        extra: serde_json::Map::new(),
    })
}

fn parse_single_surface(
    surf_val: &Value,
    workspace_id: &str,
    pane_id: Option<&str>,
    default_tab_idx: i32,
) -> Option<SurfaceInfo> {
    let surf_obj = surf_val.as_object()?;

    let id = surf_obj
        .get("ref")
        .and_then(Value::as_str)
        .or_else(|| surf_obj.get("id").and_then(Value::as_str))?
        .to_string();

    let surface_type = surf_obj
        .get("type")
        .and_then(Value::as_str)
        .unwrap_or("terminal")
        .to_string();

    let title = surf_obj
        .get("title")
        .and_then(Value::as_str)
        .map(ToString::to_string);

    let tab_index = surf_obj
        .get("tab_index")
        .and_then(Value::as_i64)
        .map(|v| v as i32)
        .unwrap_or(default_tab_idx);

    let agent_state = surf_obj
        .get("agent_state")
        .and_then(Value::as_str)
        .map(ToString::to_string);

    let attention = surf_obj
        .get("attention")
        .and_then(Value::as_bool)
        .unwrap_or(false);

    let dead = surf_obj
        .get("dead")
        .and_then(Value::as_bool)
        .unwrap_or(false);

    let cwd = surf_obj
        .get("cwd")
        .and_then(Value::as_str)
        .map(ToString::to_string);

    Some(SurfaceInfo {
        id,
        surface_type,
        title,
        workspace_key: Some(workspace_id.to_string()),
        pane_id: pane_id.map(ToString::to_string),
        tab_index,
        agent_state,
        attention,
        dead,
        cwd,
        extra: serde_json::Map::new(),
    })
}

/// Extracts a surface reference like `surface:1` from `cmux new-surface` stdout.
pub fn extract_surface_id(output: &str) -> Option<String> {
    for word in output.split_whitespace() {
        let trimmed =
            word.trim_matches(|c: char| !c.is_alphanumeric() && c != ':' && c != '-' && c != '_');
        if trimmed.starts_with("surface:") {
            return Some(trimmed.to_string());
        }
    }
    None
}

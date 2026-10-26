use cmux_pocket_protocol::rpc::CODE_BACKEND_UNAVAILABLE;
use serde_json::{json, Map, Value};
use tracing::warn;

use crate::dispatch::DispatchContext;
use crate::error::GatewayError;

pub(crate) async fn dispatch(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
    method: &str,
    params: &Value,
) -> Result<(), GatewayError> {
    match method {
        "mobile.workspace.list" | "workspace.list" => handle_list(ctx, id_val).await,
        "mobile.workspace.create" => handle_create(ctx, id_val, params).await,
        "mobile.workspace.select" => handle_select(ctx, id_val, params).await,
        "mobile.workspace.rename" => handle_rename(ctx, id_val, params).await,
        "mobile.workspace.close" => handle_close(ctx, id_val, params).await,
        _ => super::send_unknown_method(ctx, id_val, method).await,
    }
}

pub(crate) async fn handle_list(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
) -> Result<(), GatewayError> {
    match ctx.backend.list_workspaces().await {
        Ok(workspaces) => {
            ctx.health.mark_healthy();

            // If client active surface is None, auto-select first available surface
            if ctx.session.active_surface_id().is_none() && !workspaces.is_empty() {
                let mut selected_id = None;
                for ws in &workspaces {
                    if ws.active_on_host && !ws.surfaces.is_empty() {
                        selected_id = Some(ws.surfaces[0].id.clone());
                        break;
                    }
                }
                if selected_id.is_none() && !workspaces[0].surfaces.is_empty() {
                    selected_id = Some(workspaces[0].surfaces[0].id.clone());
                }
                if let Some(sid) = selected_id {
                    ctx.session.set_active_surface(Some(sid));
                }
            }

            let res = json!({
                "id": id_val,
                "result": {
                    "workspaces": workspaces,
                }
            });
            ctx.session.send_json(res).await?;
        }
        Err(e) => {
            warn!("list_workspaces failed: {}", e);
            ctx.health.mark_unhealthy(e.to_string());
            let err_res = json!({
                "id": id_val,
                "error": {
                    "code": CODE_BACKEND_UNAVAILABLE,
                    "message": format!("Backend unavailable: {}", e),
                }
            });
            ctx.session.send_json(err_res).await?;
        }
    }
    Ok(())
}

pub(crate) async fn handle_create(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
    params: &Value,
) -> Result<(), GatewayError> {
    let name = params.get("name").and_then(|v| v.as_str()).unwrap_or("");
    let mutation_id = params.get("mutation_id").cloned();

    match ctx.backend.create_workspace(name).await {
        Ok(new_ws) => {
            ctx.health.mark_healthy();
            let mut res_map = Map::new();
            res_map.insert("status".to_string(), Value::String("ok".to_string()));
            res_map.insert("workspace".to_string(), serde_json::to_value(&new_ws)?);
            if let Some(m_id) = &mutation_id {
                res_map.insert("mutation_id".to_string(), m_id.clone());
            }

            let res = json!({
                "id": id_val,
                "result": Value::Object(res_map),
            });
            ctx.session.send_json(res).await?;

            // Broadcast workspace.tree event
            let mut b_map = Map::new();
            b_map.insert(
                "action".to_string(),
                Value::String("workspace_created".to_string()),
            );
            b_map.insert("workspace".to_string(), serde_json::to_value(&new_ws)?);
            if let Some(m_id) = mutation_id {
                b_map.insert("mutation_id".to_string(), m_id);
            }

            ctx.callbacks
                .broadcast("workspace.tree", Value::Object(b_map))
                .await;
        }
        Err(e) => {
            warn!("create_workspace failed: {}", e);
            ctx.health.mark_unhealthy(e.to_string());
            let err_res = json!({
                "id": id_val,
                "error": {
                    "code": CODE_BACKEND_UNAVAILABLE,
                    "message": format!("Workspace creation failed: {}", e),
                }
            });
            ctx.session.send_json(err_res).await?;
        }
    }
    Ok(())
}

pub(crate) async fn handle_select(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
    params: &Value,
) -> Result<(), GatewayError> {
    // Client-local navigation only; host_focus_moved is always false
    let mutation_id = params.get("mutation_id").cloned();
    let ws_key = params
        .get("workspace_key")
        .or_else(|| params.get("workspace_id"))
        .cloned()
        .unwrap_or(Value::Null);

    let mut res_map = Map::new();
    res_map.insert("status".to_string(), Value::String("ok".to_string()));
    res_map.insert("workspace_key".to_string(), ws_key);
    res_map.insert("host_focus_moved".to_string(), Value::Bool(false));
    if let Some(m_id) = mutation_id {
        res_map.insert("mutation_id".to_string(), m_id);
    }

    let res = json!({
        "id": id_val,
        "result": Value::Object(res_map),
    });
    ctx.session.send_json(res).await?;
    Ok(())
}

pub(crate) async fn handle_rename(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
    params: &Value,
) -> Result<(), GatewayError> {
    let mutation_id = params.get("mutation_id").cloned();
    let ws_key = params
        .get("workspace_key")
        .or_else(|| params.get("workspace_id"))
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let title = params
        .get("title")
        .or_else(|| params.get("name"))
        .and_then(|v| v.as_str())
        .unwrap_or("");

    match ctx.backend.rename_workspace(ws_key, title).await {
        Ok(renamed_ws) => {
            ctx.health.mark_healthy();
            let mut res_map = Map::new();
            res_map.insert("status".to_string(), Value::String("ok".to_string()));
            res_map.insert("workspace".to_string(), serde_json::to_value(&renamed_ws)?);
            if let Some(m_id) = &mutation_id {
                res_map.insert("mutation_id".to_string(), m_id.clone());
            }

            let res = json!({
                "id": id_val,
                "result": Value::Object(res_map),
            });
            ctx.session.send_json(res).await?;

            // Broadcast workspace.tree event
            let mut b_map = Map::new();
            b_map.insert(
                "action".to_string(),
                Value::String("workspace_renamed".to_string()),
            );
            b_map.insert("workspace".to_string(), serde_json::to_value(&renamed_ws)?);
            if let Some(m_id) = mutation_id {
                b_map.insert("mutation_id".to_string(), m_id);
            }

            ctx.callbacks
                .broadcast("workspace.tree", Value::Object(b_map))
                .await;
        }
        Err(e) => {
            warn!("rename_workspace failed: {}", e);
            ctx.health.mark_unhealthy(e.to_string());
            let err_res = json!({
                "id": id_val,
                "error": {
                    "code": CODE_BACKEND_UNAVAILABLE,
                    "message": format!("Workspace rename failed: {}", e),
                }
            });
            ctx.session.send_json(err_res).await?;
        }
    }
    Ok(())
}

pub(crate) async fn handle_close(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
    params: &Value,
) -> Result<(), GatewayError> {
    let mutation_id = params.get("mutation_id").cloned();
    let ws_key = params
        .get("workspace_key")
        .or_else(|| params.get("workspace_id"))
        .and_then(|v| v.as_str())
        .unwrap_or("");

    match ctx.backend.close_workspace(ws_key).await {
        Ok(success) => {
            ctx.health.mark_healthy();
            let mut res_map = Map::new();
            res_map.insert(
                "status".to_string(),
                Value::String(if success { "ok" } else { "error" }.to_string()),
            );
            res_map.insert(
                "workspace_key".to_string(),
                Value::String(ws_key.to_string()),
            );
            if let Some(m_id) = &mutation_id {
                res_map.insert("mutation_id".to_string(), m_id.clone());
            }

            let res = json!({
                "id": id_val,
                "result": Value::Object(res_map),
            });
            ctx.session.send_json(res).await?;

            // Broadcast workspace.tree event
            let mut b_map = Map::new();
            b_map.insert(
                "action".to_string(),
                Value::String("workspace_closed".to_string()),
            );
            b_map.insert(
                "workspace_key".to_string(),
                Value::String(ws_key.to_string()),
            );
            if let Some(m_id) = mutation_id {
                b_map.insert("mutation_id".to_string(), m_id);
            }

            ctx.callbacks
                .broadcast("workspace.tree", Value::Object(b_map))
                .await;
        }
        Err(e) => {
            warn!("close_workspace failed: {}", e);
            ctx.health.mark_unhealthy(e.to_string());
            let err_res = json!({
                "id": id_val,
                "error": {
                    "code": CODE_BACKEND_UNAVAILABLE,
                    "message": format!("Workspace close failed: {}", e),
                }
            });
            ctx.session.send_json(err_res).await?;
        }
    }
    Ok(())
}

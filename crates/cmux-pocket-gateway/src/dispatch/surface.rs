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
        "mobile.surface.create" => handle_create(ctx, id_val, params).await,
        "mobile.surface.close" => handle_close(ctx, id_val, params).await,
        "mobile.surface.rename" => handle_rename(ctx, id_val, params).await,
        "mobile.surface.focus" => handle_focus(ctx, id_val, params).await,
        _ => super::send_unknown_method(ctx, id_val, method).await,
    }
}

pub(crate) async fn handle_create(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
    params: &Value,
) -> Result<(), GatewayError> {
    let mutation_id = params.get("mutation_id").cloned();
    let ws_key = params
        .get("workspace_key")
        .or_else(|| params.get("workspace_id"))
        .and_then(|v| v.as_str())
        .unwrap_or("ws-main");
    let title = params.get("title").and_then(|v| v.as_str());
    let surf_type = params
        .get("type")
        .and_then(|v| v.as_str())
        .unwrap_or("terminal");

    match ctx
        .backend
        .create_surface(ws_key, title, Some(surf_type))
        .await
    {
        Ok(new_surf) => {
            ctx.health.mark_healthy();
            let mut res_map = Map::new();
            res_map.insert("status".to_string(), Value::String("ok".to_string()));
            res_map.insert("surface".to_string(), serde_json::to_value(&new_surf)?);
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
                Value::String("surface_created".to_string()),
            );
            b_map.insert("surface".to_string(), serde_json::to_value(&new_surf)?);
            if let Some(m_id) = mutation_id {
                b_map.insert("mutation_id".to_string(), m_id);
            }

            ctx.callbacks
                .broadcast("workspace.tree", Value::Object(b_map))
                .await;
        }
        Err(e) => {
            warn!("create_surface failed: {}", e);
            ctx.health.mark_unhealthy(e.to_string());
            let err_res = json!({
                "id": id_val,
                "error": {
                    "code": CODE_BACKEND_UNAVAILABLE,
                    "message": format!("Surface creation failed: {}", e),
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
    let surf_id = params
        .get("surface_id")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let ws_key = params.get("workspace_key").and_then(|v| v.as_str());

    match ctx.backend.close_surface(surf_id, ws_key).await {
        Ok(success) => {
            ctx.health.mark_healthy();
            if success && ctx.session.active_surface_id().as_deref() == Some(surf_id) {
                ctx.session.set_active_surface(None);
            }

            let mut res_map = Map::new();
            res_map.insert(
                "status".to_string(),
                Value::String(if success { "ok" } else { "error" }.to_string()),
            );
            res_map.insert("surface_id".to_string(), Value::String(surf_id.to_string()));
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
                Value::String("surface_closed".to_string()),
            );
            b_map.insert("surface_id".to_string(), Value::String(surf_id.to_string()));
            if let Some(m_id) = mutation_id {
                b_map.insert("mutation_id".to_string(), m_id);
            }

            ctx.callbacks
                .broadcast("workspace.tree", Value::Object(b_map))
                .await;
        }
        Err(e) => {
            warn!("close_surface failed: {}", e);
            ctx.health.mark_unhealthy(e.to_string());
            let err_res = json!({
                "id": id_val,
                "error": {
                    "code": CODE_BACKEND_UNAVAILABLE,
                    "message": format!("Surface close failed: {}", e),
                }
            });
            ctx.session.send_json(err_res).await?;
        }
    }
    Ok(())
}

pub(crate) async fn handle_rename(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
    params: &Value,
) -> Result<(), GatewayError> {
    let mutation_id = params.get("mutation_id").cloned();
    let surf_id = params
        .get("surface_id")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let title = params
        .get("title")
        .or_else(|| params.get("name"))
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let ws_key = params.get("workspace_key").and_then(|v| v.as_str());

    match ctx.backend.rename_surface(surf_id, title, ws_key).await {
        Ok(renamed_surf) => {
            ctx.health.mark_healthy();
            let mut res_map = Map::new();
            res_map.insert("status".to_string(), Value::String("ok".to_string()));
            res_map.insert("surface".to_string(), serde_json::to_value(&renamed_surf)?);
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
                Value::String("surface_renamed".to_string()),
            );
            b_map.insert("surface".to_string(), serde_json::to_value(&renamed_surf)?);
            if let Some(m_id) = mutation_id {
                b_map.insert("mutation_id".to_string(), m_id);
            }

            ctx.callbacks
                .broadcast("workspace.tree", Value::Object(b_map))
                .await;
        }
        Err(e) => {
            warn!("rename_surface failed: {}", e);
            ctx.health.mark_unhealthy(e.to_string());
            let err_res = json!({
                "id": id_val,
                "error": {
                    "code": CODE_BACKEND_UNAVAILABLE,
                    "message": format!("Surface rename failed: {}", e),
                }
            });
            ctx.session.send_json(err_res).await?;
        }
    }
    Ok(())
}

pub(crate) async fn handle_focus(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
    params: &Value,
) -> Result<(), GatewayError> {
    let mutation_id = params.get("mutation_id").cloned();
    let surf_id = params
        .get("surface_id")
        .and_then(|v| v.as_str())
        .unwrap_or("");

    ctx.session.set_active_surface(Some(surf_id.to_string()));

    let mut res_map = Map::new();
    res_map.insert("status".to_string(), Value::String("ok".to_string()));
    res_map.insert("surface_id".to_string(), Value::String(surf_id.to_string()));
    if let Some(m_id) = mutation_id {
        res_map.insert("mutation_id".to_string(), m_id);
    }

    let res = json!({
        "id": id_val,
        "result": Value::Object(res_map),
    });
    ctx.session.send_json(res).await?;

    if ctx.session.is_subscribed_to("terminal.render_grid") && !surf_id.is_empty() {
        ctx.callbacks.request_priority_refresh(surf_id.to_string());
    }
    Ok(())
}

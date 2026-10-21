use async_trait::async_trait;
use cmux_pocket_cmux::CmuxBackend;
use cmux_pocket_protocol::auth::{ALL_CAPABILITIES, CAP_TERMINAL_VIEWPORT};
use cmux_pocket_protocol::rpc::{
    CODE_BACKEND_UNAVAILABLE, CODE_INVALID_PARAMS, CODE_METHOD_NOT_FOUND,
};
use cmux_pocket_protocol::{CAP_HOST_WAKE, PROTOCOL_SERVER_VERSION};
use serde_json::{json, Map, Value};
use std::sync::Arc;
use tracing::warn;

use crate::error::GatewayError;
use crate::health::HealthTracker;
use crate::host::SharedHostController;
use crate::session::ClientSession;
use crate::surface_locks::SurfaceLockManager;
use crate::viewport::ViewportLeaseManager;

#[async_trait]
pub trait GatewayCallbacks: Send + Sync {
    async fn broadcast(&self, event: &str, data: Value);
    fn request_priority_refresh(&self, surface_id: String);
    /// Starts a bounded fast poll for cmux readiness after a remote wake request.
    fn request_wake_watch(&self);
}

pub struct DispatchContext<'a> {
    pub session: &'a ClientSession,
    pub backend: &'a Arc<dyn CmuxBackend>,
    pub health: &'a HealthTracker,
    pub surface_locks: &'a SurfaceLockManager,
    pub host: &'a SharedHostController,
    pub viewport_leases: &'a ViewportLeaseManager,
    pub callbacks: &'a (dyn GatewayCallbacks + 'a),
}

/// Dispatches an authenticated JSON-RPC-like request frame.
pub async fn dispatch_rpc<'a>(
    ctx: DispatchContext<'a>,
    req_id: Option<Value>,
    method: &str,
    params: Value,
) -> Result<(), GatewayError> {
    let id_val = req_id.unwrap_or(Value::Null);

    match method {
        "mobile.host.status" => {
            let running = ctx.backend.ping().await.is_ok();
            let host = ctx.host.status().await;
            let viewport_supported = ctx.viewport_leases.supported().await;
            let capabilities: Vec<&str> = ALL_CAPABILITIES
                .iter()
                .copied()
                .filter(|capability| host.can_wake || *capability != CAP_HOST_WAKE)
                .filter(|capability| viewport_supported || *capability != CAP_TERMINAL_VIEWPORT)
                .collect();
            let res = json!({
                "id": id_val,
                "result": {
                    "mac_display_name": "cmux Host",
                    "mac_app_version": env!("CARGO_PKG_VERSION"),
                    "gateway_version": env!("CARGO_PKG_VERSION"),
                    "protocol_version": PROTOCOL_SERVER_VERSION,
                    "capabilities": capabilities,
                    "cmux_status": {
                        "installed": host.installed,
                        "running": running,
                        "can_wake": host.can_wake,
                    },
                }
            });
            ctx.session.send_json(res).await?;
        }

        "mobile.host.wake_cmux" => {
            let host = ctx.host.status().await;
            if !host.can_wake {
                let err_res = json!({
                    "id": id_val,
                    "error": {
                        "code": CODE_BACKEND_UNAVAILABLE,
                        "message": "Remote cmux wake is not available on this Gateway",
                    }
                });
                ctx.session.send_json(err_res).await?;
            } else if ctx.backend.ping().await.is_ok() {
                let res = json!({
                    "id": id_val,
                    "result": {
                        "success": true,
                        "status": "running",
                        "message": "cmux is already running",
                    }
                });
                ctx.session.send_json(res).await?;
                ctx.callbacks.request_wake_watch();
            } else {
                match ctx.host.wake_cmux().await {
                    Ok(()) => {
                        let res = json!({
                            "id": id_val,
                            "result": {
                                "success": true,
                                "status": "launching",
                                "message": "cmux launch initiated",
                            }
                        });
                        ctx.session.send_json(res).await?;
                        ctx.callbacks.request_wake_watch();
                    }
                    Err(error) => {
                        warn!("wake_cmux failed: {}", error);
                        let err_res = json!({
                            "id": id_val,
                            "error": {
                                "code": CODE_BACKEND_UNAVAILABLE,
                                "message": "Unable to launch cmux on the host",
                            }
                        });
                        ctx.session.send_json(err_res).await?;
                    }
                }
            }
        }

        "mobile.workspace.list" | "workspace.list" => {
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
        }

        "mobile.workspace.create" => {
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
        }

        "mobile.workspace.select" => {
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
        }
        "mobile.workspace.rename" => {
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
        }

        "mobile.workspace.close" => {
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
        }

        "mobile.surface.create" => {
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
        }

        "mobile.surface.close" => {
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
        }
        "mobile.surface.rename" => {
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
        }

        "mobile.surface.focus" => {
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
        }

        "mobile.events.subscribe" => {
            let mut topics = Vec::new();
            if let Some(arr) = params.get("topics").and_then(|v| v.as_array()) {
                for t in arr {
                    if let Some(s) = t.as_str() {
                        topics.push(s.to_string());
                    }
                }
            }
            let stream_id = params
                .get("stream_id")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string())
                .unwrap_or_else(|| uuid::Uuid::new_v4().to_string());

            ctx.session.subscribe_topics(&topics);

            let res = json!({
                "id": id_val,
                "result": {
                    "stream_id": stream_id,
                    "already_subscribed": false,
                    "event_transport": "websocket",
                }
            });
            ctx.session.send_json(res).await?;

            if ctx.session.is_subscribed_to("terminal.render_grid") {
                if let Some(sid) = ctx.session.active_surface_id() {
                    let lock = ctx.surface_locks.get_surface_mutex(&sid);
                    let _guard = lock.lock().await;
                    if let Ok(snapshot) = ctx.backend.get_snapshot(&sid, 0).await {
                        ctx.health.mark_healthy();
                        let frame_msg = json!({
                            "event": "terminal.render_grid",
                            "data": serde_json::to_value(snapshot)?,
                        });
                        ctx.session.send_json(frame_msg).await?;
                    }
                }
            }
        }

        "mobile.terminal.input" | "terminal.input" => {
            let surf_id = params
                .get("surface_id")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string())
                .or_else(|| ctx.session.active_surface_id())
                .unwrap_or_else(|| "surface:1".to_string());
            let text = params.get("text").and_then(|v| v.as_str()).unwrap_or("");

            match ctx.backend.send_input(&surf_id, text).await {
                Ok(()) => {
                    ctx.health.mark_healthy();
                    // Success ACK is delivered happens-before priority refresh
                    let res = json!({
                        "id": id_val,
                        "result": {
                            "status": "ok",
                            "surface_id": surf_id,
                        }
                    });
                    ctx.session.send_json(res).await?;

                    // Priority refresh for surface
                    ctx.callbacks.request_priority_refresh(surf_id);
                }
                Err(e) => {
                    warn!("send_input failed for {}: {}", surf_id, e);
                    let err_res = json!({
                        "id": id_val,
                        "error": {
                            "code": -32000,
                            "message": format!("Input failed: {}", e),
                        }
                    });
                    ctx.session.send_json(err_res).await?;
                }
            }
        }

        "mobile.terminal.scroll" | "terminal.scroll" => {
            let surf_id = params
                .get("surface_id")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string())
                .or_else(|| ctx.session.active_surface_id())
                .unwrap_or_else(|| "surface:1".to_string());
            let delta_lines = params
                .get("delta_lines")
                .and_then(|v| v.as_f64())
                .unwrap_or(0.0);
            let col = params.get("col").and_then(|v| v.as_u64()).unwrap_or(0) as usize;
            let row = params.get("row").and_then(|v| v.as_u64()).unwrap_or(0) as usize;

            let lock = ctx.surface_locks.get_surface_mutex(&surf_id);
            let _guard = lock.lock().await;

            match ctx
                .backend
                .handle_scroll(&surf_id, delta_lines, col, row)
                .await
            {
                Ok(frame) => {
                    ctx.health.mark_healthy();
                    let res = json!({
                        "id": id_val,
                        "result": {
                            "status": "ok",
                            "surface_id": surf_id,
                        }
                    });
                    ctx.session.send_json(res).await?;

                    let event_msg = json!({
                        "event": "terminal.render_grid",
                        "data": serde_json::to_value(frame)?,
                    });
                    ctx.session.send_json(event_msg).await?;
                }
                Err(e) => {
                    warn!("handle_scroll failed for {}: {}", surf_id, e);
                    ctx.health.mark_unhealthy(e.to_string());
                    let err_res = json!({
                        "id": id_val,
                        "error": {
                            "code": CODE_BACKEND_UNAVAILABLE,
                            "message": format!("Scroll failed: {}", e),
                        }
                    });
                    ctx.session.send_json(err_res).await?;
                }
            }
        }

        "mobile.terminal.replay" | "terminal.replay" => {
            let surf_id = params
                .get("surface_id")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string())
                .or_else(|| ctx.session.active_surface_id())
                .unwrap_or_else(|| "surface:1".to_string());
            let max_sb = params
                .get("max_scrollback_rows")
                .and_then(|v| v.as_u64())
                .map(|v| v.min(1000) as usize)
                .unwrap_or(0);

            let lock = ctx.surface_locks.get_surface_mutex(&surf_id);
            let _guard = lock.lock().await;

            match ctx.backend.get_snapshot(&surf_id, max_sb).await {
                Ok(frame) => {
                    ctx.health.mark_healthy();
                    let frame_val = serde_json::to_value(frame)?;
                    let res = json!({
                        "id": id_val,
                        "result": frame_val.clone(),
                    });
                    ctx.session.send_json(res).await?;

                    let event_msg = json!({
                        "event": "terminal.render_grid",
                        "data": frame_val,
                    });
                    ctx.session.send_json(event_msg).await?;
                }
                Err(e) => {
                    warn!("get_snapshot failed for {}: {}", surf_id, e);
                    ctx.health.mark_unhealthy(e.to_string());
                    let err_res = json!({
                        "id": id_val,
                        "error": {
                            "code": CODE_BACKEND_UNAVAILABLE,
                            "message": format!("Replay failed: {}", e),
                        }
                    });
                    ctx.session.send_json(err_res).await?;
                }
            }
        }

        "mobile.terminal.viewport" | "terminal.viewport" => {
            let surface_id = params
                .get("surface_id")
                .and_then(Value::as_str)
                .map(str::to_string)
                .or_else(|| ctx.session.active_surface_id());
            let client_id = params
                .get("client_id")
                .and_then(Value::as_str)
                .filter(|id| !id.trim().is_empty())
                .map(str::to_string);
            let generation = params.get("viewport_generation").and_then(Value::as_u64);
            let clear = params
                .get("clear")
                .and_then(Value::as_bool)
                .unwrap_or(false);

            let (surface_id, client_id, generation) = match (surface_id, client_id, generation) {
                (Some(surface_id), Some(client_id), Some(generation)) => {
                    (surface_id, client_id, generation)
                }
                _ => {
                    let err_res = json!({
                        "id": id_val,
                        "error": {
                            "code": CODE_INVALID_PARAMS,
                            "message": "Viewport requests require surface_id, client_id and viewport_generation",
                        }
                    });
                    ctx.session.send_json(err_res).await?;
                    return Ok(());
                }
            };

            if clear {
                // Clearing an existing lease must not depend on a fresh
                // capability probe: a transient probe failure would otherwise
                // strand the host report until its TTL.
                match ctx
                    .viewport_leases
                    .clear(&ctx.session.session_id, &client_id, &surface_id, generation)
                    .await
                {
                    Ok(outcome) => {
                        ctx.health.mark_healthy();
                        let mut result = Map::new();
                        result.insert("accepted".into(), Value::Bool(true));
                        result.insert("cleared".into(), Value::Bool(outcome.cleared));
                        result.insert("stale".into(), Value::Bool(!outcome.cleared));
                        result.insert("geometry_owner".into(), Value::Bool(false));
                        result.insert("generation".into(), json!(generation));
                        if let Some((columns, rows)) = outcome.geometry {
                            result.insert("columns".into(), json!(columns));
                            result.insert("rows".into(), json!(rows));
                        }
                        result.insert("surface_id".into(), Value::String(surface_id));
                        ctx.session
                            .send_json(json!({"id": id_val, "result": Value::Object(result)}))
                            .await?;
                    }
                    Err(e) => {
                        warn!("viewport clear failed for {}: {}", surface_id, e);
                        let err_res = json!({
                            "id": id_val,
                            "error": {
                                "code": CODE_BACKEND_UNAVAILABLE,
                                "message": format!("Viewport clear failed: {e}"),
                            }
                        });
                        ctx.session.send_json(err_res).await?;
                    }
                }
                return Ok(());
            }

            if !ctx.viewport_leases.supported().await {
                let err_res = json!({
                    "id": id_val,
                    "error": {
                        "code": CODE_METHOD_NOT_FOUND,
                        "message": "Host does not support terminal viewport resizing",
                    }
                });
                ctx.session.send_json(err_res).await?;
                return Ok(());
            }

            let columns = params
                .get("viewport_columns")
                .and_then(Value::as_u64)
                .filter(|v| *v > 0 && *v <= u32::MAX as u64);
            let rows = params
                .get("viewport_rows")
                .and_then(Value::as_u64)
                .filter(|v| *v > 0 && *v <= u32::MAX as u64);
            let (columns, rows) = match (columns, rows) {
                (Some(columns), Some(rows)) => (columns as u32, rows as u32),
                _ => {
                    let err_res = json!({
                        "id": id_val,
                        "error": {
                            "code": CODE_INVALID_PARAMS,
                            "message": "Viewport report requires positive viewport_columns and viewport_rows",
                        }
                    });
                    ctx.session.send_json(err_res).await?;
                    return Ok(());
                }
            };

            match ctx
                .viewport_leases
                .report(
                    &ctx.session.session_id,
                    &client_id,
                    &surface_id,
                    columns,
                    rows,
                    generation,
                )
                .await
            {
                Ok(outcome) => {
                    ctx.health.mark_healthy();
                    let mut result = Map::new();
                    result.insert("accepted".into(), Value::Bool(outcome.accepted));
                    result.insert("pending".into(), Value::Bool(outcome.pending));
                    result.insert("stale".into(), Value::Bool(!outcome.accepted));
                    result.insert("geometry_owner".into(), Value::Bool(true));
                    result.insert("columns".into(), json!(outcome.requested_columns));
                    result.insert("rows".into(), json!(outcome.requested_rows));
                    result.insert("requested_columns".into(), json!(outcome.requested_columns));
                    result.insert("requested_rows".into(), json!(outcome.requested_rows));
                    result.insert("generation".into(), json!(outcome.generation));
                    result.insert("surface_id".into(), Value::String(surface_id.clone()));
                    if let Some(columns) = outcome.effective_columns {
                        result.insert("effective_columns".into(), json!(columns));
                    }
                    if let Some(rows) = outcome.effective_rows {
                        result.insert("effective_rows".into(), json!(rows));
                    }
                    ctx.session
                        .send_json(json!({"id": id_val, "result": Value::Object(result)}))
                        .await?;
                    // A settled report changes the grid now; ask the poller to
                    // push the new baseline without waiting for its next tick.
                    if !outcome.pending {
                        ctx.callbacks.request_priority_refresh(surface_id);
                    }
                }
                Err(e) => {
                    warn!("viewport report failed for {}: {}", surface_id, e);
                    let err_res = json!({
                        "id": id_val,
                        "error": {
                            "code": CODE_BACKEND_UNAVAILABLE,
                            "message": format!("Viewport report failed: {e}"),
                        }
                    });
                    ctx.session.send_json(err_res).await?;
                }
            }
        }

        _ => {
            let err_res = json!({
                "id": id_val,
                "error": {
                    "code": CODE_METHOD_NOT_FOUND,
                    "message": format!("Method '{}' not implemented in gateway", method),
                }
            });
            ctx.session.send_json(err_res).await?;
        }
    }

    Ok(())
}

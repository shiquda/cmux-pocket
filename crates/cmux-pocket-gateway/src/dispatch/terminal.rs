use cmux_pocket_protocol::rpc::CODE_BACKEND_UNAVAILABLE;
use serde_json::{json, Value};
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
        "mobile.terminal.input" | "terminal.input" => handle_input(ctx, id_val, params).await,
        "mobile.terminal.scroll" | "terminal.scroll" => handle_scroll(ctx, id_val, params).await,
        "mobile.terminal.replay" | "terminal.replay" => handle_replay(ctx, id_val, params).await,
        _ => super::send_unknown_method(ctx, id_val, method).await,
    }
}

pub(crate) async fn handle_input(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
    params: &Value,
) -> Result<(), GatewayError> {
    let surf_id = params
        .get("surface_id")
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
        .or_else(|| ctx.session.active_surface_id())
        .unwrap_or_else(|| "surface:1".to_string());
    let text = params.get("text").and_then(|v| v.as_str()).unwrap_or("");
    let input_kind = match params.get("input_kind").and_then(|v| v.as_str()) {
        Some("key") => cmux_pocket_cmux::TerminalInputKind::Key,
        _ => cmux_pocket_cmux::TerminalInputKind::Text,
    };

    match ctx
        .backend
        .send_input_with_kind(&surf_id, text, input_kind)
        .await
    {
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
    Ok(())
}

pub(crate) async fn handle_scroll(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
    params: &Value,
) -> Result<(), GatewayError> {
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
    Ok(())
}

pub(crate) async fn handle_replay(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
    params: &Value,
) -> Result<(), GatewayError> {
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
    Ok(())
}

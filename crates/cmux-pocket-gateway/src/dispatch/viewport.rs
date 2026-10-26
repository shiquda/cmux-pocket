use cmux_pocket_protocol::rpc::{
    CODE_BACKEND_UNAVAILABLE, CODE_INVALID_PARAMS, CODE_METHOD_NOT_FOUND,
};
use serde_json::{json, Map, Value};
use tracing::warn;

use crate::dispatch::DispatchContext;
use crate::error::GatewayError;

pub(crate) async fn dispatch(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
    params: &Value,
) -> Result<(), GatewayError> {
    handle_viewport(ctx, id_val, params).await
}

pub(crate) async fn handle_viewport(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
    params: &Value,
) -> Result<(), GatewayError> {
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

    Ok(())
}

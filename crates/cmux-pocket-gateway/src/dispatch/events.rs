use serde_json::{json, Value};

use crate::dispatch::DispatchContext;
use crate::error::GatewayError;

pub(crate) async fn dispatch(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
    params: &Value,
) -> Result<(), GatewayError> {
    handle_subscribe(ctx, id_val, params).await
}

pub(crate) async fn handle_subscribe(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
    params: &Value,
) -> Result<(), GatewayError> {
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
    Ok(())
}

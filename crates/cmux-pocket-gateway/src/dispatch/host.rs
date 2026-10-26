use cmux_pocket_protocol::auth::{ALL_CAPABILITIES, CAP_TERMINAL_VIEWPORT};
use cmux_pocket_protocol::rpc::CODE_BACKEND_UNAVAILABLE;
use cmux_pocket_protocol::{CAP_HOST_WAKE, PROTOCOL_SERVER_VERSION};
use serde_json::{json, Value};
use tracing::warn;

use crate::dispatch::DispatchContext;
use crate::error::GatewayError;

pub(crate) async fn dispatch(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
    method: &str,
) -> Result<(), GatewayError> {
    match method {
        "mobile.host.status" => handle_status(ctx, id_val).await,
        "mobile.host.wake_cmux" => handle_wake_cmux(ctx, id_val).await,
        _ => super::send_unknown_method(ctx, id_val, method).await,
    }
}

pub(crate) async fn handle_status(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
) -> Result<(), GatewayError> {
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
    Ok(())
}

pub(crate) async fn handle_wake_cmux(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
) -> Result<(), GatewayError> {
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
    Ok(())
}

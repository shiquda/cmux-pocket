use async_trait::async_trait;
use cmux_pocket_cmux::CmuxBackend;
use cmux_pocket_protocol::rpc::CODE_METHOD_NOT_FOUND;
use serde_json::{json, Value};
use std::sync::Arc;

use crate::error::GatewayError;
use crate::health::HealthTracker;
use crate::host::SharedHostController;
use crate::session::ClientSession;
use crate::surface_locks::SurfaceLockManager;
use crate::viewport::ViewportLeaseManager;

pub mod events;
pub mod host;
pub mod surface;
pub mod terminal;
pub mod viewport;
pub mod workspace;

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

pub(crate) async fn send_unknown_method(
    ctx: &DispatchContext<'_>,
    id_val: &Value,
    method: &str,
) -> Result<(), GatewayError> {
    let err_res = json!({
        "id": id_val,
        "error": {
            "code": CODE_METHOD_NOT_FOUND,
            "message": format!("Method '{}' not implemented in gateway", method),
        }
    });
    ctx.session.send_json(err_res).await?;
    Ok(())
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
        "mobile.host.status" | "mobile.host.wake_cmux" => {
            host::dispatch(&ctx, &id_val, method).await
        }

        "mobile.workspace.list"
        | "workspace.list"
        | "mobile.workspace.create"
        | "mobile.workspace.select"
        | "mobile.workspace.rename"
        | "mobile.workspace.close" => workspace::dispatch(&ctx, &id_val, method, &params).await,

        "mobile.surface.create"
        | "mobile.surface.close"
        | "mobile.surface.rename"
        | "mobile.surface.focus" => surface::dispatch(&ctx, &id_val, method, &params).await,

        "mobile.events.subscribe" => events::dispatch(&ctx, &id_val, &params).await,

        "mobile.terminal.input"
        | "terminal.input"
        | "mobile.terminal.scroll"
        | "terminal.scroll"
        | "mobile.terminal.replay"
        | "terminal.replay" => terminal::dispatch(&ctx, &id_val, method, &params).await,

        "mobile.terminal.viewport" | "terminal.viewport" => {
            viewport::dispatch(&ctx, &id_val, &params).await
        }

        _ => send_unknown_method(&ctx, &id_val, method).await,
    }
}

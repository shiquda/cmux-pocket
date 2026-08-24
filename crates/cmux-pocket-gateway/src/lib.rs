//! Tokio and tokio-tungstenite WebSocket Gateway runtime for cmux-pocket.

pub mod agent;
pub mod auth;
pub mod dispatch;
pub mod error;
pub mod health;
pub mod poller;
pub mod server;
pub mod session;
pub mod surface_locks;

pub use agent::{AgentEventSupervisor, EventDedup};
pub use auth::{
    build_auth_error_invalid_token, build_auth_error_unauthenticated, build_auth_ok,
    constant_time_token_eq, verify_token, WS_CLOSE_AUTH_FAILED,
};
pub use dispatch::{dispatch_rpc, DispatchContext, GatewayCallbacks};
pub use error::GatewayError;
pub use health::HealthTracker;
pub use poller::{fanout_screen_snapshots, ScreenPoller, TreePoller};
pub use server::CmuxGateway;
pub use session::{ClientSession, ControlMessage, RenderSlot, SessionState};
pub use surface_locks::SurfaceLockManager;

pub fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

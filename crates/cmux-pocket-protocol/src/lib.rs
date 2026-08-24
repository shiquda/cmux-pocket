//! Wire models, serde envelopes, normalization, and display width for cmux-pocket.

pub mod agent;
pub mod auth;
pub mod health;
pub mod mutation;
pub mod render;
pub mod rpc;
pub mod workspace;

// Re-export common types at crate root for ergonomic use
pub use agent::{
    notification_record_is_completion, parse_agent_completion_event, AgentSessionCompleted,
};
pub use auth::{
    default_capabilities, AuthError, AuthErrorPayload, AuthOk, AuthOkPayload, AuthRequest,
    AuthResponse, ALL_CAPABILITIES, AUTH_REASON_INVALID_TOKEN, AUTH_REASON_UNAUTHENTICATED,
    CAP_CLIENT_FOCUS, CAP_EVENTS, CAP_INPUT_ORDERED, CAP_MULTI_SURFACE, CAP_RENDER_GRID,
    CAP_WORKSPACE_CHANGES, PROTOCOL_SERVER_VERSION, WS_CLOSE_AUTH_FAILED,
};
pub use health::{BackendHealth, ProtocolError};
pub use mutation::{
    EventsSubscribeParams, EventsSubscribeResponse, HostStatusResponse, SurfaceCloseParams,
    SurfaceCloseResponse, SurfaceCreateParams, SurfaceCreateResponse, SurfaceFocusParams,
    SurfaceFocusResponse, TerminalInputParams, TerminalInputResponse, TerminalReplayParams,
    TerminalScrollParams, TerminalScrollResponse, TerminalViewportParams, TerminalViewportResponse,
    WorkspaceCreateParams, WorkspaceCreateResponse, WorkspaceSelectParams, WorkspaceSelectResponse,
};
pub use render::{
    ansi_lines_to_render_grid, char_cell_width, display_cell_width, normalize_official_replay,
    parse_ansi_line, AnsiSpan, Cursor, RenderGridFrame, RowSpan, Style, ANSI_BG_COLORS,
    ANSI_COLORS, DEFAULT_TERMINAL_BG, DEFAULT_TERMINAL_FG, RENDER_GRID_FORMAT_V1,
};
pub use rpc::{
    JsonRpcError, JsonRpcRequest, JsonRpcResponse, RequestId, RpcMethod, CODE_BACKEND_UNAVAILABLE,
    CODE_INTERNAL_ERROR, CODE_INVALID_PARAMS, CODE_INVALID_REQUEST, CODE_METHOD_NOT_FOUND,
    CODE_PARSE_ERROR,
};
pub use workspace::{
    workspace_tree_signature, SurfaceInfo, WorkspaceInfo, WorkspaceListResponse, WorkspaceTreeEvent,
};

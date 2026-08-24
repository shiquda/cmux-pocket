use thiserror::Error;

#[derive(Debug, Error)]
pub enum GatewayError {
    #[error("Loopback violation: {0}")]
    Loopback(#[from] cmux_pocket_macos::error::LoopbackError),

    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    #[error("WebSocket error: {0}")]
    WebSocket(#[from] Box<tokio_tungstenite::tungstenite::Error>),

    #[error("cmux backend error: {0}")]
    Cmux(#[from] cmux_pocket_cmux::error::CmuxError),

    #[error("Serialization error: {0}")]
    Json(#[from] serde_json::Error),

    #[error("Client session closed")]
    SessionClosed,

    #[error("Authentication failed: {0}")]
    AuthFailed(String),

    #[error("Backend unavailable: {0}")]
    BackendUnavailable(String),

    #[error("Internal gateway error: {0}")]
    Internal(String),
}

use cmux_pocket_protocol::ProtocolError;
use std::time::Duration;

/// Errors produced by cmux backend operations.
#[derive(Debug, thiserror::Error)]
pub enum CmuxError {
    /// The cmux binary exited with a non-zero status code.
    ///
    /// Stderr is intentionally excluded from the display representation to avoid
    /// leaking user inputs or terminal contents into logs or error envelopes.
    #[error("cmux {command} failed with exit code {}", .exit_code.map(|c| c.to_string()).unwrap_or_else(|| "unknown".to_string()))]
    NonZeroExit {
        command: String,
        exit_code: Option<i32>,
    },

    /// A cmux command timed out before completing.
    #[error("cmux {command} timed out after {duration:?}")]
    Timeout { command: String, duration: Duration },

    /// cmux executable or socket is unavailable (e.g., not found or daemon not running).
    #[error("cmux is unavailable: {reason}")]
    Unavailable { reason: String },

    /// Failed to parse cmux stdout output.
    #[error("cmux output parse error: {message}")]
    ParseError { message: String },

    /// Protocol error during normalization or frame construction.
    #[error("protocol error: {0}")]
    Protocol(#[from] ProtocolError),

    /// Standard I/O error during child process execution.
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
}

impl CmuxError {
    pub fn non_zero_exit(command: impl Into<String>, exit_code: Option<i32>) -> Self {
        Self::NonZeroExit {
            command: command.into(),
            exit_code,
        }
    }

    pub fn timeout(command: impl Into<String>, duration: Duration) -> Self {
        Self::Timeout {
            command: command.into(),
            duration,
        }
    }

    pub fn unavailable(reason: impl Into<String>) -> Self {
        Self::Unavailable {
            reason: reason.into(),
        }
    }

    pub fn parse_error(message: impl Into<String>) -> Self {
        Self::ParseError {
            message: message.into(),
        }
    }

    /// Returns true if the error indicates cmux is unreachable/unavailable.
    pub fn is_unavailable(&self) -> bool {
        matches!(self, Self::Unavailable { .. })
    }

    /// Returns true if the error was caused by a command timeout.
    pub fn is_timeout(&self) -> bool {
        matches!(self, Self::Timeout { .. })
    }
}

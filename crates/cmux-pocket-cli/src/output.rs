//! Output formatting, JSON response envelopes, and secret redaction.

use crate::error::{CliError, CliExitCode};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

/// Structured JSON envelope for CLI command outputs.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct JsonEnvelope<T> {
    pub ok: bool,
    pub code: i32,
    pub message: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<T>,
}

impl<T> JsonEnvelope<T> {
    /// Creates a successful JSON envelope.
    pub fn success(data: T, message: impl Into<String>) -> Self {
        Self {
            ok: true,
            code: CliExitCode::Success.as_i32(),
            message: message.into(),
            data: Some(data),
        }
    }
}

impl JsonEnvelope<serde_json::Value> {
    /// Creates a success envelope with no extra data payload.
    pub fn success_empty(message: impl Into<String>) -> Self {
        Self {
            ok: true,
            code: CliExitCode::Success.as_i32(),
            message: message.into(),
            data: None,
        }
    }

    /// Creates an error JSON envelope.
    pub fn error(code: CliExitCode, message: impl Into<String>) -> Self {
        Self {
            ok: false,
            code: code.as_i32(),
            message: message.into(),
            data: None,
        }
    }
}

/// Computes a safe SHA-256 fingerprint for a secret token string.
///
/// Output format is `sha256:<first 12 hex chars>...<last 4 hex chars>`.
pub fn token_fingerprint(token: &str) -> String {
    if token.trim().is_empty() {
        return "none".to_string();
    }
    let mut hasher = Sha256::new();
    hasher.update(token.trim().as_bytes());
    let hash_hex = format!("{:x}", hasher.finalize());
    format!(
        "sha256:{}...{}",
        &hash_hex[..12],
        &hash_hex[hash_hex.len() - 4..]
    )
}

/// Redacts a raw secret value, masking characters.
pub fn mask_secret(secret: &str) -> String {
    if secret.is_empty() {
        return "<empty>".to_string();
    }
    format!("•••••••• ({})", token_fingerprint(secret))
}

/// Prints a success response either as formatted JSON or human readable prose.
pub fn print_success<T: Serialize>(data: &T, human_prose: &str, json_mode: bool) {
    if json_mode {
        let envelope = JsonEnvelope::success(data, human_prose);
        if let Ok(json_str) = serde_json::to_string_pretty(&envelope) {
            println!("{}", json_str);
        } else {
            println!("{{\"ok\":true,\"code\":0,\"message\":\"{}\"}}", human_prose);
        }
    } else {
        println!("{}", human_prose);
    }
}

/// Prints an error response either as formatted JSON to stdout or human readable error to stderr.
pub fn print_error(error: &CliError, json_mode: bool) {
    let exit_code = error.exit_code();
    let msg = error.to_string();

    if json_mode {
        let envelope = JsonEnvelope::error(exit_code, &msg);
        if let Ok(json_str) = serde_json::to_string_pretty(&envelope) {
            println!("{}", json_str);
        } else {
            println!(
                "{{\"ok\":false,\"code\":{},\"message\":\"{}\"}}",
                exit_code.as_i32(),
                msg
            );
        }
    } else {
        eprintln!("Error: {}", msg);
    }
}

use cmux_pocket_protocol::auth::{
    AuthError, AuthOk, AUTH_REASON_INVALID_TOKEN, AUTH_REASON_UNAUTHENTICATED,
};

pub use cmux_pocket_protocol::auth::WS_CLOSE_AUTH_FAILED;

/// Performs a constant-time comparison of two string tokens to prevent timing attacks.
pub fn constant_time_token_eq(a: &str, b: &str) -> bool {
    let a_bytes = a.as_bytes();
    let b_bytes = b.as_bytes();

    if a_bytes.len() != b_bytes.len() {
        return false;
    }

    let mut diff = 0u8;
    for (x, y) in a_bytes.iter().zip(b_bytes.iter()) {
        diff |= x ^ y;
    }
    diff == 0
}

/// Validates an incoming client auth request against the configured gateway token.
pub fn verify_token(expected_token: &str, candidate_token: &str) -> bool {
    if expected_token.is_empty() || candidate_token.is_empty() {
        return false;
    }
    constant_time_token_eq(expected_token, candidate_token)
}

/// Builds an `auth_ok` response payload.
pub fn build_auth_ok(session_id: &str) -> AuthOk {
    AuthOk::new(session_id)
}

/// Builds an `auth_error` response payload for invalid credentials.
pub fn build_auth_error_invalid_token() -> AuthError {
    AuthError::new(AUTH_REASON_INVALID_TOKEN)
}

/// Builds an `auth_error` response payload for missing/unauthenticated first frame.
pub fn build_auth_error_unauthenticated() -> AuthError {
    AuthError::new(AUTH_REASON_UNAUTHENTICATED)
}

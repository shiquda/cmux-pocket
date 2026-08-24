//! Secure token generation, storage, permission enforcement, and fingerprinting.
//!
//! Generates 256-bit cryptographically secure tokens, writes them atomically with
//! user-only permissions (`0o600`), and provides safe redaction / fingerprinting
//! primitives so token values are never leaked into logs, plists, or diagnostics.

use crate::error::MacOsError;
use crate::paths::validate_outside_cellar;
use crate::permissions::{atomic_write_secret_file, ensure_file_user_only};
use sha2::{Digest, Sha256};
use std::fmt;
use std::fs;
use std::path::Path;

/// Entropy length in bytes for Gateway authentication tokens (256 bits).
pub const TOKEN_BYTE_LEN: usize = 32;

/// Generates a cryptographically secure random token (64-character hexadecimal string).
pub fn generate_token() -> Result<String, MacOsError> {
    let mut bytes = [0u8; TOKEN_BYTE_LEN];
    getrandom::getrandom(&mut bytes).map_err(|e| {
        MacOsError::Io(std::io::Error::other(format!(
            "Failed to generate secure random token: {e}"
        )))
    })?;

    Ok(bytes.iter().map(|b| format!("{b:02x}")).collect())
}

/// Fingerprint and metadata for a token without revealing the secret value.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TokenFingerprint {
    /// SHA-256 full hexadecimal digest (64 characters).
    pub sha256_full: String,
    /// SHA-256 truncated prefix (first 12 characters).
    pub sha256_short: String,
    /// Length of the token string in bytes.
    pub char_length: usize,
}

impl TokenFingerprint {
    /// Computes a deterministic SHA-256 fingerprint for a token string.
    pub fn compute(token: &str) -> Self {
        let mut hasher = Sha256::new();
        hasher.update(token.as_bytes());
        let digest = hasher.finalize();
        let sha256_full: String = digest.iter().map(|b| format!("{b:02x}")).collect();
        let sha256_short = sha256_full[..12].to_string();
        let char_length = token.len();

        Self {
            sha256_full,
            sha256_short,
            char_length,
        }
    }

    /// Formats a safe summary string suitable for logs and non-secret CLI status.
    pub fn display_summary(&self) -> String {
        format!(
            "sha256:{}... ({} chars)",
            self.sha256_short, self.char_length
        )
    }
}

/// A wrapper around a secret token string that redacts its value in `Debug` and `Display`.
#[derive(Clone, PartialEq, Eq)]
pub struct RedactedToken(String);

impl RedactedToken {
    /// Creates a new `RedactedToken` container.
    pub fn new(secret: String) -> Self {
        Self(secret)
    }

    /// Computes the token fingerprint.
    pub fn fingerprint(&self) -> TokenFingerprint {
        TokenFingerprint::compute(&self.0)
    }

    /// Exposes the raw secret value for explicit protocol authorization.
    pub fn expose_secret(&self) -> &str {
        &self.0
    }
}

impl fmt::Debug for RedactedToken {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let fp = self.fingerprint();
        write!(f, "RedactedToken([SECRET:{}])", fp.display_summary())
    }
}

impl fmt::Display for RedactedToken {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "[REDACTED]")
    }
}

/// Atomically saves a token to the given path with `0o600` permissions.
///
/// Ensures path is strictly outside the Homebrew Cellar.
pub fn save_token(path: &Path, token: &str) -> Result<(), MacOsError> {
    validate_outside_cellar(path, "token")?;

    let trimmed = token.trim();
    if trimmed.is_empty() {
        return Err(MacOsError::TokenEmpty(path.to_path_buf()));
    }

    let content = format!("{trimmed}\n");
    atomic_write_secret_file(path, content.as_bytes())
}

/// Loads and validates a token from the given path.
///
/// Verifies:
/// 1. Path is outside Homebrew Cellar.
/// 2. File exists.
/// 3. File permissions are user-only (`0o600` / `mode & 0o077 == 0`).
/// 4. File is not empty.
pub fn load_token(path: &Path) -> Result<String, MacOsError> {
    validate_outside_cellar(path, "token")?;

    if !path.exists() {
        return Err(MacOsError::TokenNotFound(path.to_path_buf()));
    }

    ensure_file_user_only(path)?;

    let raw = fs::read_to_string(path)?;
    let trimmed = raw.trim().to_string();

    if trimmed.is_empty() {
        return Err(MacOsError::TokenEmpty(path.to_path_buf()));
    }

    Ok(trimmed)
}

/// Ensures a valid token exists at `path`.
///
/// If the token file exists, loads and returns it (`was_created = false`).
/// If absent, generates a fresh token, saves it atomically, and returns it (`was_created = true`).
pub fn ensure_token(path: &Path) -> Result<(String, bool), MacOsError> {
    if path.exists() {
        let token = load_token(path)?;
        Ok((token, false))
    } else {
        let token = generate_token()?;
        save_token(path, &token)?;
        Ok((token, true))
    }
}

/// Rotates the token at `path` by generating a new one and atomically overwriting the file.
pub fn rotate_token(path: &Path) -> Result<String, MacOsError> {
    let new_token = generate_token()?;
    save_token(path, &new_token)?;
    Ok(new_token)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn test_generate_token() {
        let t1 = generate_token().unwrap();
        let t2 = generate_token().unwrap();

        assert_eq!(t1.len(), 64);
        assert_eq!(t2.len(), 64);
        assert_ne!(t1, t2);
        assert!(t1.chars().all(|c| c.is_ascii_hexdigit()));
    }

    #[test]
    fn test_token_fingerprint() {
        let token = "test-token-12345";
        let fp = TokenFingerprint::compute(token);

        assert_eq!(fp.char_length, 16);
        assert_eq!(fp.sha256_full.len(), 64);
        assert_eq!(fp.sha256_short.len(), 12);
        assert!(fp.sha256_full.starts_with(&fp.sha256_short));
        assert!(fp.display_summary().contains(&fp.sha256_short));
    }

    #[test]
    fn test_redacted_token_display_and_debug() {
        let token = "super-secret-token-value";
        let redacted = RedactedToken::new(token.to_string());

        assert_eq!(format!("{redacted}"), "[REDACTED]");
        assert!(!format!("{redacted:?}").contains("super-secret-token-value"));
        assert!(format!("{redacted:?}").contains("SECRET"));
        assert_eq!(redacted.expose_secret(), "super-secret-token-value");
    }

    #[test]
    fn test_save_and_load_token() {
        let tmp = tempdir().unwrap();
        let token_path = tmp.path().join("gateway-token");

        let generated = generate_token().unwrap();
        save_token(&token_path, &generated).unwrap();

        let loaded = load_token(&token_path).unwrap();
        assert_eq!(loaded, generated);
    }

    #[test]
    fn test_ensure_token_idempotent() {
        let tmp = tempdir().unwrap();
        let token_path = tmp.path().join("gateway-token");

        let (t1, created1) = ensure_token(&token_path).unwrap();
        assert!(created1);
        assert!(!t1.is_empty());

        let (t2, created2) = ensure_token(&token_path).unwrap();
        assert!(!created2);
        assert_eq!(t1, t2);
    }

    #[test]
    fn test_rotate_token() {
        let tmp = tempdir().unwrap();
        let token_path = tmp.path().join("gateway-token");

        let (t1, _) = ensure_token(&token_path).unwrap();
        let t2 = rotate_token(&token_path).unwrap();

        assert_ne!(t1, t2);
        let loaded = load_token(&token_path).unwrap();
        assert_eq!(loaded, t2);
    }

    #[test]
    fn test_load_empty_token_fails() {
        let tmp = tempdir().unwrap();
        let token_path = tmp.path().join("empty-token");

        atomic_write_secret_file(&token_path, b"   \n\n  ").unwrap();
        match load_token(&token_path) {
            Err(MacOsError::TokenEmpty(p)) => assert_eq!(p, token_path),
            other => panic!("Expected TokenEmpty, got: {other:?}"),
        }
    }
}

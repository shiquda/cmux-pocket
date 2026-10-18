//! cmux-pocket configuration URI (`cmux-pocket://`) format, builder, and parser.
//!
//! Standardized format:
//! `cmux-pocket://import?v=1&host=<HOST>&port=<PORT>&tls=<true|false>&token=<TOKEN>&name=<NAME>`
//!
//! Rules:
//! - Scheme: `cmux-pocket`
//! - Authority / Path: `import` (also accepts `connect`)
//! - Version `v`: must be `"1"`
//! - Host: Gateway host or endpoint
//! - Port: 1..=65535 (default 443 if TLS, 8088 if loopback)
//! - TLS: `"true"` or `"false"`. Cleartext (`tls=false`) is only allowed for loopback hosts.
//! - Token: Gateway bearer authentication token
//! - Name: Profile display name (optional, defaults to host)

use cmux_pocket_macos::{is_loopback_host, ParsedEndpoint};
use serde::{Deserialize, Serialize};
use thiserror::Error;
use url::Url;

pub const CURRENT_URI_VERSION: &str = "1";

pub const SECURITY_WARNING: &str =
    "⚠️  SECURITY NOTICE: This QR code and URL contain your Gateway authentication token.\n\
     Anyone with access can view and control your cmux terminal sessions.\n\
     Do NOT take screenshots, share publicly, or post in chat groups.";

pub const LOOPBACK_NOTICE: &str =
    "ℹ️  NOTE: Loopback endpoint specified (127.0.0.1 / localhost).\n\
     This configuration is reachable only via USB reverse port forwarding (`adb reverse tcp:8088 tcp:8088`)\n\
     or locally on this device, not over Wi-Fi or cellular networks.";

#[derive(Debug, Error, PartialEq, Eq)]
pub enum CmuxUriError {
    #[error("Invalid URL format: {0}")]
    InvalidUrl(String),

    #[error("Invalid scheme '{0}': expected 'cmux-pocket'")]
    InvalidScheme(String),

    #[error(
        "Unsupported configuration version '{0}': this app expects version '{CURRENT_URI_VERSION}'"
    )]
    UnsupportedVersion(String),

    #[error("Missing required parameter: '{0}'")]
    MissingParameter(&'static str),

    #[error("Invalid port '{0}': must be between 1 and 65535")]
    InvalidPort(String),

    #[error(
        "Cleartext WebSocket is only allowed for loopback connections; use https:// or wss://"
    )]
    CleartextNonLoopbackForbidden,
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct CmuxImportPayload {
    pub version: String,
    pub host: String,
    pub port: u16,
    pub tls: bool,
    pub token: String,
    pub name: String,
    pub is_loopback: bool,
}

impl std::fmt::Debug for CmuxImportPayload {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("CmuxImportPayload")
            .field("version", &self.version)
            .field("host", &self.host)
            .field("port", &self.port)
            .field("tls", &self.tls)
            .field("token", &self.masked_token())
            .field("name", &self.name)
            .field("is_loopback", &self.is_loopback)
            .finish()
    }
}

impl CmuxImportPayload {
    /// Returns a safe masked representation of the secret token, e.g. `1a2b...c3d4` or `••••••••`.
    pub fn masked_token(&self) -> String {
        if self.token.len() <= 8 {
            "••••••••".to_string()
        } else {
            let start = &self.token[..4];
            let end = &self.token[self.token.len() - 4..];
            format!("{}••••{}", start, end)
        }
    }

    /// Formats the target endpoint as a websocket URL (`wss://...` or `ws://...`).
    pub fn websocket_url(&self) -> String {
        let scheme = if self.tls { "wss" } else { "ws" };
        let host_formatted = if self.host.contains(':') && !self.host.starts_with('[') {
            format!("[{}]", self.host)
        } else {
            self.host.clone()
        };
        if (self.tls && self.port == 443) || (!self.tls && self.port == 80) {
            format!("{}://{}", scheme, host_formatted)
        } else {
            format!("{}://{}:{}", scheme, host_formatted, self.port)
        }
    }
}

/// Builds a `cmux-pocket://` import URI from endpoint and token.
pub fn build_import_uri(
    endpoint: &ParsedEndpoint,
    token: &str,
    profile_name: Option<&str>,
) -> String {
    let name = profile_name
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| {
            if endpoint.is_loopback {
                "USB / Local"
            } else {
                &endpoint.host
            }
        });

    let mut url = Url::parse("cmux-pocket://import").expect("Valid base URL");
    url.query_pairs_mut()
        .append_pair("v", CURRENT_URI_VERSION)
        .append_pair("host", &endpoint.host)
        .append_pair("port", &endpoint.port.to_string())
        .append_pair("tls", if endpoint.is_tls { "true" } else { "false" })
        .append_pair("token", token)
        .append_pair("name", name);

    url.to_string()
}

/// Parses and validates a `cmux-pocket://` import URI.
pub fn parse_import_uri(uri_str: &str) -> Result<CmuxImportPayload, CmuxUriError> {
    let url = Url::parse(uri_str).map_err(|e| CmuxUriError::InvalidUrl(e.to_string()))?;

    if !url.scheme().eq_ignore_ascii_case("cmux-pocket") {
        return Err(CmuxUriError::InvalidScheme(url.scheme().to_string()));
    }

    let mut version = None;
    let mut host = None;
    let mut port_str = None;
    let mut tls_str = None;
    let mut token = None;
    let mut name = None;

    for (k, v) in url.query_pairs() {
        match k.as_ref() {
            "v" => version = Some(v.to_string()),
            "host" => host = Some(v.to_string()),
            "port" => port_str = Some(v.to_string()),
            "tls" => tls_str = Some(v.to_string()),
            "token" => token = Some(v.to_string()),
            "name" => name = Some(v.to_string()),
            _ => {}
        }
    }

    let version = version.ok_or(CmuxUriError::MissingParameter("v"))?;
    if version != CURRENT_URI_VERSION {
        return Err(CmuxUriError::UnsupportedVersion(version));
    }

    let host = host
        .filter(|h| !h.trim().is_empty())
        .ok_or(CmuxUriError::MissingParameter("host"))?;

    let is_loopback = is_loopback_host(&host);

    let tls = match tls_str.as_deref() {
        Some("true") | Some("1") => true,
        Some("false") | Some("0") => false,
        _ => !is_loopback,
    };

    if !tls && !is_loopback {
        return Err(CmuxUriError::CleartextNonLoopbackForbidden);
    }

    let port = if let Some(p) = port_str {
        let p_num: u16 = p
            .parse()
            .map_err(|_| CmuxUriError::InvalidPort(p.clone()))?;
        if p_num == 0 {
            return Err(CmuxUriError::InvalidPort(p));
        }
        p_num
    } else if tls {
        443
    } else {
        8088
    };

    let token = token
        .filter(|t| !t.trim().is_empty())
        .ok_or(CmuxUriError::MissingParameter("token"))?;

    let name = name.unwrap_or_else(|| host.clone());

    Ok(CmuxImportPayload {
        version,
        host,
        port,
        tls,
        token,
        name,
        is_loopback,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use cmux_pocket_macos::parse_and_validate_endpoint;

    #[test]
    fn test_build_and_parse_roundtrip_tls() {
        let ep = parse_and_validate_endpoint("wss://mac.example.ts.net").unwrap();
        let token = "secret-token-abcdef123456";
        let uri = build_import_uri(&ep, token, Some("My Mac"));

        assert!(uri.starts_with("cmux-pocket://import?"));
        let parsed = parse_import_uri(&uri).unwrap();
        assert_eq!(parsed.version, "1");
        assert_eq!(parsed.host, "mac.example.ts.net");
        assert_eq!(parsed.port, 443);
        assert!(parsed.tls);
        assert_eq!(parsed.token, token);
        assert_eq!(parsed.name, "My Mac");
        assert!(!parsed.is_loopback);
        assert_eq!(parsed.websocket_url(), "wss://mac.example.ts.net");
    }

    #[test]
    fn test_build_and_parse_roundtrip_loopback() {
        let ep = parse_and_validate_endpoint("ws://127.0.0.1:8088").unwrap();
        let token = "test-token-loopback";
        let uri = build_import_uri(&ep, token, None);

        let parsed = parse_import_uri(&uri).unwrap();
        assert_eq!(parsed.version, "1");
        assert_eq!(parsed.host, "127.0.0.1");
        assert_eq!(parsed.port, 8088);
        assert!(!parsed.tls);
        assert_eq!(parsed.token, token);
        assert_eq!(parsed.name, "USB / Local");
        assert!(parsed.is_loopback);
        assert_eq!(parsed.websocket_url(), "ws://127.0.0.1:8088");
    }

    #[test]
    fn test_rejects_unsupported_version() {
        let uri = "cmux-pocket://import?v=2&host=example.ts.net&port=443&token=abc";
        let err = parse_import_uri(uri).unwrap_err();
        assert!(matches!(err, CmuxUriError::UnsupportedVersion(_)));
    }

    #[test]
    fn test_rejects_invalid_scheme() {
        let uri = "https://example.ts.net?v=1&host=example.ts.net&port=443&token=abc";
        let err = parse_import_uri(uri).unwrap_err();
        assert!(matches!(err, CmuxUriError::InvalidScheme(_)));
    }

    #[test]
    fn test_rejects_cleartext_non_loopback() {
        let uri = "cmux-pocket://import?v=1&host=remote.example.com&port=8088&tls=false&token=abc";
        let err = parse_import_uri(uri).unwrap_err();
        assert_eq!(err, CmuxUriError::CleartextNonLoopbackForbidden);
    }

    #[test]
    fn test_token_masking_in_debug_and_helper() {
        let payload = CmuxImportPayload {
            version: "1".to_string(),
            host: "test.example.com".to_string(),
            port: 443,
            tls: true,
            token: "supersecrettokenvalue123456".to_string(),
            name: "Test".to_string(),
            is_loopback: false,
        };
        let debug_str = format!("{:?}", payload);
        assert!(!debug_str.contains("supersecrettokenvalue123456"));
        assert!(debug_str.contains("supe••••3456"));
        assert_eq!(payload.masked_token(), "supe••••3456");
    }
}

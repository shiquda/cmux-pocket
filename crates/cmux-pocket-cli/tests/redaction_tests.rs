use cmux_pocket_cli::output::{mask_secret, token_fingerprint, JsonEnvelope};
use cmux_pocket_macos::GatewayConfig;
use serde_json::json;

#[test]
fn test_token_fingerprint_format_and_properties() {
    let token = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
    let fp = token_fingerprint(token);

    assert!(fp.starts_with("sha256:"));
    assert!(fp.contains("..."));
    assert_ne!(fp, token);
    assert!(!fp.contains(token));

    // Deterministic
    assert_eq!(fp, token_fingerprint(token));

    // Empty token
    assert_eq!(token_fingerprint(""), "none");
    assert_eq!(token_fingerprint("   "), "none");
}

#[test]
fn test_mask_secret() {
    let secret = "my_super_secret_token_12345";
    let masked = mask_secret(secret);

    assert!(!masked.contains(secret));
    assert!(masked.contains("••••••••"));
    assert!(masked.contains("sha256:"));
}

#[test]
fn test_json_envelope_serialization() {
    let envelope = JsonEnvelope::success(
        json!({
            "endpoint": "ws://127.0.0.1:8088",
            "fingerprint": "sha256:1234567890ab...cdef",
        }),
        "Setup successful",
    );

    let serialized = serde_json::to_string(&envelope).unwrap();
    assert!(serialized.contains("\"ok\":true"));
    assert!(serialized.contains("\"code\":0"));
    assert!(serialized.contains("\"message\":\"Setup successful\""));
    assert!(serialized.contains("\"endpoint\":\"ws://127.0.0.1:8088\""));
}

#[test]
fn test_config_redacted_toml() {
    let cfg = GatewayConfig::default();
    let toml_str = cfg.to_redacted_toml_string().unwrap();

    assert!(toml_str.contains("host = \"127.0.0.1\""));
    assert!(toml_str.contains("port = 8088"));
}

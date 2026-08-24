use cmux_pocket_protocol::*;
use serde_json::json;

#[test]
fn test_auth_request_serde_round_trip() {
    let req = AuthRequest::new("secret-token-123");
    let json_val = serde_json::to_value(&req).unwrap();

    assert_eq!(json_val["type"], "auth");
    assert_eq!(json_val["token"], "secret-token-123");
    assert_eq!(json_val["client_id"], "android-client");

    let deserialized: AuthRequest = serde_json::from_value(json_val).unwrap();
    assert_eq!(deserialized.token, "secret-token-123");
    assert_eq!(deserialized.client_id.as_deref(), Some("android-client"));
}

#[test]
fn test_auth_request_preserves_unknown_fields() {
    let raw = json!({
        "type": "auth",
        "token": "tok-456",
        "client_id": "custom-client",
        "device_info": "Pixel 8 Pro",
        "app_version": "1.2.3"
    });

    let req: AuthRequest = serde_json::from_value(raw).unwrap();
    assert_eq!(req.token, "tok-456");
    assert_eq!(req.client_id.as_deref(), Some("custom-client"));
    assert_eq!(req.extra.get("device_info").unwrap(), "Pixel 8 Pro");
    assert_eq!(req.extra.get("app_version").unwrap(), "1.2.3");

    let reencoded = serde_json::to_value(&req).unwrap();
    assert_eq!(reencoded["device_info"], "Pixel 8 Pro");
    assert_eq!(reencoded["app_version"], "1.2.3");
}

#[test]
fn test_auth_ok_default_and_custom_capabilities() {
    let ok = AuthOk::new("session-abc");
    let json_val = serde_json::to_value(&ok).unwrap();

    assert_eq!(json_val["type"], "auth_ok");
    assert_eq!(json_val["session_id"], "session-abc");
    assert_eq!(json_val["server_version"], PROTOCOL_SERVER_VERSION);
    let caps = json_val["capabilities"].as_array().unwrap();
    assert_eq!(caps.len(), ALL_CAPABILITIES.len());
    assert!(caps.iter().any(|c| c == CAP_RENDER_GRID));
    assert!(caps.iter().any(|c| c == CAP_INPUT_ORDERED));
    assert!(caps.iter().any(|c| c == CAP_MULTI_SURFACE));

    let custom = AuthOk::with_capabilities(
        "session-custom",
        "2.1.0",
        vec![CAP_RENDER_GRID.to_string(), "custom.cap".to_string()],
    );
    let custom_val = serde_json::to_value(&custom).unwrap();
    assert_eq!(custom_val["server_version"], "2.1.0");
    assert_eq!(custom_val["capabilities"].as_array().unwrap().len(), 2);
}

#[test]
fn test_auth_error_reasons_and_extra_data() {
    let err_invalid = AuthError::invalid_token();
    let val_invalid = serde_json::to_value(&err_invalid).unwrap();
    assert_eq!(val_invalid["type"], "auth_error");
    assert_eq!(val_invalid["reason"], AUTH_REASON_INVALID_TOKEN);

    let err_unauth = AuthError::unauthenticated();
    let val_unauth = serde_json::to_value(&err_unauth).unwrap();
    assert_eq!(val_unauth["type"], "auth_error");
    assert_eq!(val_unauth["reason"], AUTH_REASON_UNAUTHENTICATED);

    // Auth error with extra diagnostic data
    let raw = json!({
        "type": "auth_error",
        "reason": "invalid_token",
        "client_ip": "127.0.0.1",
        "retry_after_sec": 5
    });
    let parsed: AuthError = serde_json::from_value(raw).unwrap();
    assert_eq!(parsed.reason, "invalid_token");
    assert_eq!(parsed.extra.get("client_ip").unwrap(), "127.0.0.1");
    assert_eq!(parsed.extra.get("retry_after_sec").unwrap(), 5);
}

#[test]
fn test_auth_response_polymorphism() {
    let ok_json = json!({
        "type": "auth_ok",
        "session_id": "sess-999",
        "server_version": "2.0.0",
        "capabilities": ["terminal.render_grid.v1"]
    });

    let resp_ok: AuthResponse = serde_json::from_value(ok_json).unwrap();
    assert!(resp_ok.is_ok());
    assert_eq!(resp_ok.session_id(), Some("sess-999"));
    assert_eq!(resp_ok.reason(), None);

    let err_json = json!({
        "type": "auth_error",
        "reason": "invalid_token"
    });

    let resp_err: AuthResponse = serde_json::from_value(err_json).unwrap();
    assert!(!resp_err.is_ok());
    assert_eq!(resp_err.session_id(), None);
    assert_eq!(resp_err.reason(), Some("invalid_token"));
}

#[test]
fn test_auth_constants() {
    assert_eq!(WS_CLOSE_AUTH_FAILED, 1008);
    assert_eq!(PROTOCOL_SERVER_VERSION, "2.0.0");
    assert_eq!(AUTH_REASON_INVALID_TOKEN, "invalid_token");
    assert_eq!(AUTH_REASON_UNAUTHENTICATED, "unauthenticated");
}

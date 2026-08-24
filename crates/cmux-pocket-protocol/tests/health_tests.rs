use cmux_pocket_protocol::*;
use serde_json::json;

#[test]
fn test_backend_health_serde_and_predicates() {
    let healthy = BackendHealth::healthy();
    assert!(healthy.is_healthy());
    assert!(!healthy.is_unhealthy());
    assert!(!healthy.is_recovering());
    assert_eq!(healthy.reason(), None);
    assert_eq!(healthy.as_status_str(), "healthy");

    let healthy_json = serde_json::to_value(&healthy).unwrap();
    assert_eq!(healthy_json, json!({"status": "healthy"}));

    let unhealthy = BackendHealth::unhealthy("cmux socket /tmp/cmux.sock not found");
    assert!(!unhealthy.is_healthy());
    assert!(unhealthy.is_unhealthy());
    assert_eq!(
        unhealthy.reason(),
        Some("cmux socket /tmp/cmux.sock not found")
    );
    assert_eq!(unhealthy.as_status_str(), "unhealthy");

    let unhealthy_json = serde_json::to_value(&unhealthy).unwrap();
    assert_eq!(
        unhealthy_json,
        json!({"status": "unhealthy", "reason": "cmux socket /tmp/cmux.sock not found"})
    );

    let recovering = BackendHealth::recovering();
    assert!(recovering.is_recovering());
    assert_eq!(recovering.as_status_str(), "recovering");

    let recovering_json = serde_json::to_value(&recovering).unwrap();
    assert_eq!(recovering_json, json!({"status": "recovering"}));

    // Round-trip deserialization
    let parsed_h: BackendHealth = serde_json::from_value(healthy_json).unwrap();
    assert_eq!(parsed_h, BackendHealth::Healthy);

    let parsed_u: BackendHealth = serde_json::from_value(unhealthy_json).unwrap();
    assert_eq!(
        parsed_u,
        BackendHealth::Unhealthy {
            reason: "cmux socket /tmp/cmux.sock not found".to_string()
        }
    );
}

#[test]
fn test_protocol_error_formatting() {
    let err_auth = ProtocolError::AuthFailed("invalid token".to_string());
    assert_eq!(err_auth.to_string(), "Authentication failed: invalid token");

    let err_method = ProtocolError::MethodNotFound("foo.bar".to_string());
    assert_eq!(err_method.to_string(), "Method not found: foo.bar");

    let err_backend = ProtocolError::BackendUnavailable("cmux crashed".to_string());
    assert_eq!(err_backend.to_string(), "Backend unavailable: cmux crashed");
}

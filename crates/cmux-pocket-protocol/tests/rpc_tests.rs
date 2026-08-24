use cmux_pocket_protocol::*;
use serde_json::json;

#[test]
fn test_request_id_variants_and_display() {
    let str_id: RequestId = "req-42".into();
    assert_eq!(str_id.as_str(), "req-42");
    assert_eq!(str_id.to_string(), "req-42");
    let val_str = serde_json::to_value(&str_id).unwrap();
    assert_eq!(val_str, "req-42");

    let num_id: RequestId = 100i64.into();
    assert_eq!(num_id.as_str(), "100");
    assert_eq!(num_id.to_string(), "100");
    let val_num = serde_json::to_value(&num_id).unwrap();
    assert_eq!(val_num, 100);

    let parsed_str: RequestId = serde_json::from_str("\"alpha-1\"").unwrap();
    assert_eq!(parsed_str, RequestId::String("alpha-1".to_string()));

    let parsed_num: RequestId = serde_json::from_str("999").unwrap();
    assert_eq!(parsed_num, RequestId::Number(999));
}

#[test]
fn test_json_rpc_request_serde_and_unknown_fields() {
    let req = JsonRpcRequest::new(
        "req-1",
        "mobile.workspace.list",
        json!({"include_surfaces": true}),
    );
    let val = serde_json::to_value(&req).unwrap();
    assert_eq!(val["id"], "req-1");
    assert_eq!(val["method"], "mobile.workspace.list");
    assert_eq!(val["params"]["include_surfaces"], true);

    // Deserializing with extra unknown fields
    let raw = json!({
        "id": 1234,
        "method": "mobile.terminal.input",
        "params": {"text": "ls\n"},
        "client_timestamp_ms": 1724000000000_u64,
        "trace_id": "tr-abc"
    });
    let parsed: JsonRpcRequest = serde_json::from_value(raw).unwrap();
    assert_eq!(parsed.id, RequestId::Number(1234));
    assert_eq!(parsed.method, "mobile.terminal.input");
    assert_eq!(parsed.params["text"], "ls\n");
    assert_eq!(
        parsed.extra.get("client_timestamp_ms").unwrap(),
        1724000000000_u64
    );
    assert_eq!(parsed.extra.get("trace_id").unwrap(), "tr-abc");
}

#[test]
fn test_json_rpc_response_result_error_and_event() {
    // 1. Success result
    let result_resp = JsonRpcResponse::result("req-1", json!({"status": "ok"})).unwrap();
    assert!(result_resp.is_result());
    assert!(!result_resp.is_error());
    assert!(!result_resp.is_event());

    let res_val = serde_json::to_value(&result_resp).unwrap();
    assert_eq!(res_val["id"], "req-1");
    assert_eq!(res_val["result"]["status"], "ok");
    assert!(res_val.get("error").is_none());
    assert!(res_val.get("event").is_none());

    // 2. Error response
    let err_resp = JsonRpcResponse::error(
        Some("req-2".into()),
        JsonRpcError::method_not_found("unknown.foo"),
    );
    assert!(err_resp.is_error());
    let err_val = serde_json::to_value(&err_resp).unwrap();
    assert_eq!(err_val["id"], "req-2");
    assert_eq!(err_val["error"]["code"], CODE_METHOD_NOT_FOUND);
    assert_eq!(
        err_val["error"]["message"],
        "Method 'unknown.foo' not implemented in gateway"
    );
    assert!(err_val.get("result").is_none());

    // 3. Server event
    let event_resp = JsonRpcResponse::event(
        "workspace.tree",
        json!({"action": "sync", "workspaces": []}),
    )
    .unwrap();
    assert!(event_resp.is_event());
    let ev_val = serde_json::to_value(&event_resp).unwrap();
    assert!(ev_val.get("id").is_none());
    assert_eq!(ev_val["event"], "workspace.tree");
    assert_eq!(ev_val["data"]["action"], "sync");
}

#[test]
fn test_json_rpc_error_data_payload() {
    let error_with_data = JsonRpcError::with_data(
        CODE_INVALID_PARAMS,
        "Invalid viewport dimensions",
        json!({"columns": -1, "rows": 0}),
    )
    .unwrap();

    let val = serde_json::to_value(&error_with_data).unwrap();
    assert_eq!(val["code"], CODE_INVALID_PARAMS);
    assert_eq!(val["message"], "Invalid viewport dimensions");
    assert_eq!(val["data"]["columns"], -1);
    assert_eq!(val["data"]["rows"], 0);

    let parsed: JsonRpcError = serde_json::from_value(val).unwrap();
    assert_eq!(parsed.code, CODE_INVALID_PARAMS);
    assert_eq!(parsed.data.unwrap()["columns"], -1);
}

#[test]
fn test_rpc_methods_and_aliases() {
    let methods = [
        ("mobile.host.status", RpcMethod::HostStatus, false),
        ("mobile.workspace.list", RpcMethod::WorkspaceList, false),
        ("workspace.list", RpcMethod::WorkspaceList, true),
        ("mobile.workspace.create", RpcMethod::WorkspaceCreate, false),
        ("mobile.workspace.select", RpcMethod::WorkspaceSelect, false),
        ("mobile.surface.create", RpcMethod::SurfaceCreate, false),
        ("mobile.surface.close", RpcMethod::SurfaceClose, false),
        ("mobile.surface.focus", RpcMethod::SurfaceFocus, false),
        ("mobile.events.subscribe", RpcMethod::EventsSubscribe, false),
        ("mobile.terminal.input", RpcMethod::TerminalInput, false),
        ("terminal.input", RpcMethod::TerminalInput, true),
        ("mobile.terminal.scroll", RpcMethod::TerminalScroll, false),
        ("terminal.scroll", RpcMethod::TerminalScroll, true),
        ("mobile.terminal.replay", RpcMethod::TerminalReplay, false),
        ("terminal.replay", RpcMethod::TerminalReplay, true),
        (
            "mobile.terminal.viewport",
            RpcMethod::TerminalViewport,
            false,
        ),
        ("terminal.viewport", RpcMethod::TerminalViewport, true),
    ];

    for (name, expected_variant, is_alias) in methods {
        let resolved = RpcMethod::from_name(name);
        assert_eq!(
            resolved,
            Some(expected_variant),
            "Failed resolving method '{name}'"
        );
        assert_eq!(
            RpcMethod::is_alias(name),
            is_alias,
            "is_alias mismatch for '{name}'"
        );
        if !is_alias {
            assert_eq!(expected_variant.canonical_name(), name);
        }
    }

    // Unknown method returns None
    assert_eq!(RpcMethod::from_name("unknown.method"), None);
    assert_eq!(RpcMethod::from_name("mobile.invalid"), None);
    assert_eq!(RpcMethod::from_name(""), None);
}

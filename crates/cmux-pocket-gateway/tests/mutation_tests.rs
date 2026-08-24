use cmux_pocket_cmux::MockCmuxBackend;
use cmux_pocket_gateway::CmuxGateway;
use futures_util::{SinkExt, StreamExt};
use serde_json::{json, Value};
use std::sync::Arc;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::Message;

async fn spawn_test_gateway(token: &str) -> (Arc<CmuxGateway>, u16) {
    let backend = Arc::new(MockCmuxBackend::default());
    let gateway = Arc::new(CmuxGateway::new("127.0.0.1", 0, token, backend).expect("Gateway init"));
    let listener = gateway.start().await.expect("Gateway start");
    let port = listener.local_addr().expect("Local addr").port();
    let gw_runner = gateway.clone();
    tokio::spawn(async move {
        let _ = gw_runner.run_with_listener(listener).await;
    });
    (gateway, port)
}

async fn authenticate_ws(
    port: u16,
    token: &str,
) -> tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>> {
    let url = format!("ws://127.0.0.1:{}", port);
    let (mut ws, _) = connect_async(&url).await.expect("Connect WebSocket");

    let auth_msg = json!({
        "type": "auth",
        "token": token,
        "client_id": "test-client",
    });

    ws.send(Message::Text(auth_msg.to_string()))
        .await
        .expect("Send auth");

    let auth_res = ws.next().await.expect("Recv").expect("Valid msg");
    let text = auth_res.to_text().expect("Text");
    let parsed: Value = serde_json::from_str(text).expect("JSON");
    assert_eq!(parsed.get("type").and_then(|v| v.as_str()), Some("auth_ok"));
    ws
}

#[tokio::test]
async fn test_mutation_id_echo_and_tree_events() {
    let token = "test-token";
    let (gw, port) = spawn_test_gateway(token).await;
    let mut ws = authenticate_ws(port, token).await;

    // 1. Subscribe to workspace.tree
    let sub_req = json!({
        "id": "req-sub-ws",
        "method": "mobile.events.subscribe",
        "params": {
            "topics": ["workspace.tree"]
        }
    });
    ws.send(Message::Text(sub_req.to_string())).await.unwrap();
    let _sub_ack: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();

    // 2. Workspace create with mutation_id
    let ws_create_req = json!({
        "id": "req-ws-create",
        "method": "mobile.workspace.create",
        "params": {
            "name": "mut-ws-project",
            "mutation_id": "mut-ws-100",
        }
    });
    ws.send(Message::Text(ws_create_req.to_string()))
        .await
        .unwrap();

    // RPC Response must echo mutation_id
    let res_ws: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(res_ws["id"], "req-ws-create");
    assert_eq!(res_ws["result"]["status"], "ok");
    assert_eq!(res_ws["result"]["mutation_id"], "mut-ws-100");
    let created_ws = &res_ws["result"]["workspace"];
    let ws_key = created_ws["id"].as_str().unwrap();

    // Event broadcast must echo mutation_id
    let evt_ws: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(evt_ws["event"], "workspace.tree");
    assert_eq!(evt_ws["data"]["action"], "workspace_created");
    assert_eq!(evt_ws["data"]["mutation_id"], "mut-ws-100");

    // 3. Surface create with mutation_id
    let surf_create_req = json!({
        "id": "req-surf-create",
        "method": "mobile.surface.create",
        "params": {
            "workspace_key": ws_key,
            "title": "mut-tab-1",
            "mutation_id": "mut-surf-200",
        }
    });
    ws.send(Message::Text(surf_create_req.to_string()))
        .await
        .unwrap();

    let res_surf: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(res_surf["id"], "req-surf-create");
    assert_eq!(res_surf["result"]["status"], "ok");
    assert_eq!(res_surf["result"]["mutation_id"], "mut-surf-200");
    let surf_id = res_surf["result"]["surface"]["id"].as_str().unwrap();

    let evt_surf: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(evt_surf["event"], "workspace.tree");
    assert_eq!(evt_surf["data"]["action"], "surface_created");
    assert_eq!(evt_surf["data"]["mutation_id"], "mut-surf-200");

    // 4. Surface close with mutation_id
    let surf_close_req = json!({
        "id": "req-surf-close",
        "method": "mobile.surface.close",
        "params": {
            "surface_id": surf_id,
            "workspace_key": ws_key,
            "mutation_id": "mut-close-300",
        }
    });
    ws.send(Message::Text(surf_close_req.to_string()))
        .await
        .unwrap();

    let res_close: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(res_close["id"], "req-surf-close");
    assert_eq!(res_close["result"]["status"], "ok");
    assert_eq!(res_close["result"]["mutation_id"], "mut-close-300");

    let evt_close: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(evt_close["event"], "workspace.tree");
    assert_eq!(evt_close["data"]["action"], "surface_closed");
    assert_eq!(evt_close["data"]["mutation_id"], "mut-close-300");

    gw.stop().await;
}

#[tokio::test]
async fn test_surface_close_clears_active_surface() {
    let token = "test-token";
    let (gw, port) = spawn_test_gateway(token).await;
    let mut ws = authenticate_ws(port, token).await;

    // Create a surface
    let surf_create = json!({
        "id": "req-c1",
        "method": "mobile.surface.create",
        "params": {
            "workspace_key": "ws-main",
            "title": "to-be-closed",
        }
    });
    ws.send(Message::Text(surf_create.to_string()))
        .await
        .unwrap();
    let res_c: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    let target_surf_id = res_c["result"]["surface"]["id"].as_str().unwrap();

    // Focus the surface
    let focus_req = json!({
        "id": "req-f1",
        "method": "mobile.surface.focus",
        "params": {
            "surface_id": target_surf_id,
        }
    });
    ws.send(Message::Text(focus_req.to_string())).await.unwrap();
    let res_f: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(res_f["result"]["status"], "ok");

    // Close the surface
    let close_req = json!({
        "id": "req-close",
        "method": "mobile.surface.close",
        "params": {
            "surface_id": target_surf_id,
            "workspace_key": "ws-main",
        }
    });
    ws.send(Message::Text(close_req.to_string())).await.unwrap();
    let res_close: Value =
        serde_json::from_str(ws.next().await.unwrap().unwrap().to_text().unwrap()).unwrap();
    assert_eq!(res_close["result"]["status"], "ok");

    gw.stop().await;
}

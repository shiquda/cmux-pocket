//! Read-only WebSocket probe against running Gateway instance.

use crate::error::CliError;
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::time::{Duration, Instant};
use tokio::time::timeout;
use tokio_tungstenite::connect_async;
use tokio_tungstenite::tungstenite::Message;

/// Default timeout for probe network interactions.
pub const DEFAULT_PROBE_TIMEOUT: Duration = Duration::from_secs(4);

/// Structured results from probing a running cmux-pocket Gateway.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ProbeReport {
    pub connected: bool,
    pub authenticated: bool,
    pub host: String,
    pub port: u16,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub server_version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub session_id: Option<String>,
    pub capabilities: Vec<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub host_status: Option<Value>,
    pub latency_ms: u64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub backend_health: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

/// Connects to a running Gateway over loopback WebSocket, performs authentication,
/// and executes read-only `mobile.host.status`.
pub async fn probe_gateway(
    host: &str,
    port: u16,
    token: &str,
    timeout_duration: Duration,
) -> Result<ProbeReport, CliError> {
    let ws_url = format!("ws://{}:{}/", host, port);
    let start_time = Instant::now();

    let connect_fut = connect_async(&ws_url);
    let (ws_stream, _) = match timeout(timeout_duration, connect_fut).await {
        Ok(Ok(stream)) => stream,
        Ok(Err(e)) => {
            return Err(CliError::DependencyUnavailable(format!(
                "Failed to connect to Gateway at {}: {}",
                ws_url, e
            )));
        }
        Err(_) => {
            return Err(CliError::DependencyUnavailable(format!(
                "Timed out connecting to Gateway at {}",
                ws_url
            )));
        }
    };

    let (mut write, mut read) = ws_stream.split();

    // 1. Send auth frame
    let auth_msg = json!({
        "type": "auth",
        "token": token,
        "client_id": "cmux-pocket-cli-probe",
    });

    if let Err(e) = write.send(Message::Text(auth_msg.to_string())).await {
        return Err(CliError::RuntimeFailure(format!(
            "Failed to send auth frame to Gateway: {}",
            e
        )));
    }

    // 2. Receive auth response
    let auth_res = timeout(timeout_duration, read.next()).await;
    let (session_id, server_version, capabilities) = match auth_res {
        Ok(Some(Ok(msg))) => match msg {
            Message::Text(text) => {
                let parsed: Value = serde_json::from_str(&text).map_err(|e| {
                    CliError::RuntimeFailure(format!("Invalid auth response JSON: {}", e))
                })?;

                let msg_type = parsed.get("type").and_then(|v| v.as_str()).unwrap_or("");
                if msg_type == "auth_ok" {
                    let sid = parsed
                        .get("session_id")
                        .and_then(|v| v.as_str())
                        .map(String::from);
                    let s_ver = parsed
                        .get("server_version")
                        .and_then(|v| v.as_str())
                        .map(String::from);
                    let caps = parsed
                        .get("capabilities")
                        .and_then(|v| v.as_array())
                        .map(|arr| {
                            arr.iter()
                                .filter_map(|c| c.as_str().map(String::from))
                                .collect()
                        })
                        .unwrap_or_default();
                    (sid, s_ver, caps)
                } else if msg_type == "auth_error" {
                    let reason = parsed
                        .get("reason")
                        .and_then(|v| v.as_str())
                        .unwrap_or("authentication rejected");
                    return Err(CliError::ConfigOrToken(format!(
                        "Gateway authentication rejected: {}",
                        reason
                    )));
                } else {
                    return Err(CliError::RuntimeFailure(format!(
                        "Unexpected response during auth: {}",
                        text
                    )));
                }
            }
            Message::Close(frame) => {
                let code = frame.as_ref().map(|f| f.code.into()).unwrap_or(0);
                if code == 1008 {
                    return Err(CliError::ConfigOrToken(
                        "Gateway closed connection with code 1008 (authentication failed)"
                            .to_string(),
                    ));
                }
                return Err(CliError::DependencyUnavailable(format!(
                    "Gateway closed connection during auth: {:?}",
                    frame
                )));
            }
            _ => {
                return Err(CliError::RuntimeFailure(
                    "Unexpected binary/non-text frame from Gateway during auth".to_string(),
                ));
            }
        },
        Ok(Some(Err(e))) => {
            return Err(CliError::RuntimeFailure(format!(
                "Read error during auth: {}",
                e
            )));
        }
        Ok(None) => {
            return Err(CliError::DependencyUnavailable(
                "Gateway closed connection immediately".to_string(),
            ));
        }
        Err(_) => {
            return Err(CliError::DependencyUnavailable(
                "Timed out waiting for auth response from Gateway".to_string(),
            ));
        }
    };

    // 3. Send read-only mobile.host.status RPC
    let rpc_req = json!({
        "id": "probe-status-1",
        "method": "mobile.host.status",
        "params": {},
    });

    if let Err(e) = write.send(Message::Text(rpc_req.to_string())).await {
        return Err(CliError::RuntimeFailure(format!(
            "Failed to send RPC request: {}",
            e
        )));
    }

    let mut host_status = None;
    let mut backend_health = None;

    let rpc_res = timeout(timeout_duration, read.next()).await;
    match rpc_res {
        Ok(Some(Ok(msg))) => match msg {
            Message::Text(text) => {
                let parsed: Value = serde_json::from_str(&text).map_err(|e| {
                    CliError::RuntimeFailure(format!("Invalid RPC response JSON: {}", e))
                })?;

                if let Some(err) = parsed.get("error") {
                    let err_msg = err
                        .get("message")
                        .and_then(|v| v.as_str())
                        .unwrap_or("RPC error");
                    return Err(CliError::DependencyUnavailable(format!(
                        "Gateway returned RPC error: {}",
                        err_msg
                    )));
                }

                if let Some(res) = parsed.get("result") {
                    host_status = Some(res.clone());
                    if let Some(health_obj) = res.get("backend_health") {
                        if let Some(st) = health_obj.get("status").and_then(|s| s.as_str()) {
                            backend_health = Some(st.to_string());
                        }
                    }
                }
            }
            Message::Close(frame) => {
                return Err(CliError::DependencyUnavailable(format!(
                    "Gateway closed connection during RPC call: {:?}",
                    frame
                )));
            }
            _ => {}
        },
        Ok(Some(Err(e))) => {
            return Err(CliError::RuntimeFailure(format!(
                "Read error during RPC: {}",
                e
            )));
        }
        Ok(None) => {
            return Err(CliError::DependencyUnavailable(
                "Gateway disconnected during RPC".to_string(),
            ));
        }
        Err(_) => {
            return Err(CliError::DependencyUnavailable(
                "Timed out waiting for mobile.host.status response from Gateway".to_string(),
            ));
        }
    }

    // 4. Send clean close
    let _ = write.send(Message::Close(None)).await;

    let elapsed = start_time.elapsed().as_millis() as u64;

    Ok(ProbeReport {
        connected: true,
        authenticated: true,
        host: host.to_string(),
        port,
        server_version,
        session_id,
        capabilities,
        host_status,
        latency_ms: elapsed,
        backend_health,
        error: None,
    })
}

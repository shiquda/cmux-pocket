use async_trait::async_trait;
use cmux_pocket_cmux::CmuxBackend;
use cmux_pocket_macos::loopback::validate_loopback_host;
use futures_util::StreamExt;
use parking_lot::Mutex;
use serde_json::{json, Value};
use std::collections::HashSet;
use std::net::SocketAddr;
use std::sync::Arc;
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::Notify;
use tokio::task::JoinHandle;
use tokio_tungstenite::tungstenite::Message;
use tracing::{debug, info, warn};

use crate::agent::AgentEventSupervisor;
use crate::auth::{
    build_auth_error_invalid_token, build_auth_error_unauthenticated, build_auth_ok, verify_token,
};
use crate::dispatch::{dispatch_rpc, DispatchContext, GatewayCallbacks};
use crate::error::GatewayError;
use crate::health::HealthTracker;
use crate::poller::{ScreenPoller, TreePoller};
use crate::session::ClientSession;
use crate::surface_locks::SurfaceLockManager;

/// Core Cmux Gateway server instance managing WebSocket listener, sessions, and background pollers.
pub struct CmuxGateway {
    pub host: String,
    pub port: u16,
    pub auth_token: String,
    pub backend: Arc<dyn CmuxBackend>,
    pub health: HealthTracker,
    pub surface_locks: SurfaceLockManager,
    clients: Arc<Mutex<Vec<ClientSession>>>,
    priority_surfaces: Arc<Mutex<HashSet<String>>>,
    refresh_trigger: Arc<Notify>,
    shutdown_notify: Arc<Notify>,
    tasks: Arc<Mutex<Vec<JoinHandle<()>>>>,
}

#[async_trait]
impl GatewayCallbacks for CmuxGateway {
    async fn broadcast(&self, event: &str, data: Value) {
        self.broadcast_event(event, data).await;
    }

    fn request_priority_refresh(&self, surface_id: String) {
        self.request_priority_refresh(surface_id);
    }
}

impl CmuxGateway {
    /// Creates a new `CmuxGateway` after enforcing strict loopback-only bind host validation.
    #[allow(clippy::result_large_err)]
    pub fn new(
        host: impl Into<String>,
        port: u16,
        auth_token: impl Into<String>,
        backend: Arc<dyn CmuxBackend>,
    ) -> Result<Self, GatewayError> {
        let h = host.into();
        let token = auth_token.into();

        validate_loopback_host(&h)?;

        if token.trim().is_empty() {
            return Err(GatewayError::AuthFailed(
                "auth_token must not be empty".to_string(),
            ));
        }

        Ok(Self {
            host: h,
            port,
            auth_token: token,
            backend,
            health: HealthTracker::new(),
            surface_locks: SurfaceLockManager::new(),
            clients: Arc::new(Mutex::new(Vec::new())),
            priority_surfaces: Arc::new(Mutex::new(HashSet::new())),
            refresh_trigger: Arc::new(Notify::new()),
            shutdown_notify: Arc::new(Notify::new()),
            tasks: Arc::new(Mutex::new(Vec::new())),
        })
    }

    /// Returns the configured bind address formatted as host:port.
    pub fn bind_addr(&self) -> String {
        format!("{}:{}", self.host, self.port)
    }

    /// Triggers an immediate priority refresh for a specific surface.
    pub fn request_priority_refresh(&self, surface_id: String) {
        if !surface_id.is_empty() {
            let mut guard = self.priority_surfaces.lock();
            guard.insert(surface_id);
            drop(guard);
            self.refresh_trigger.notify_one();
        }
    }

    /// Broadcasts an event to all connected and authenticated clients subscribed to `event_name`.
    pub async fn broadcast_event(&self, event_name: &str, data: Value) {
        let client_list: Vec<ClientSession> = {
            let guard = self.clients.lock();
            guard.clone()
        };

        let event_msg = json!({
            "event": event_name,
            "data": data,
        });

        for client in client_list {
            if client.is_authenticated() && client.is_subscribed_to(event_name) {
                let _ = client.send_json(event_msg.clone()).await;
            }
        }
    }

    /// Fetches a surface snapshot while holding the per-surface lock.
    pub async fn get_surface_snapshot(
        &self,
        surface_id: &str,
        max_scrollback_rows: usize,
    ) -> Result<Value, GatewayError> {
        let lock = self.surface_locks.get_surface_mutex(surface_id);
        let _guard = lock.lock().await;
        let snapshot = self
            .backend
            .get_snapshot(surface_id, max_scrollback_rows)
            .await?;
        self.health.mark_healthy();
        Ok(serde_json::to_value(snapshot)?)
    }

    /// Handles a terminal scroll operation under the per-surface lock.
    pub async fn handle_surface_scroll(
        &self,
        surface_id: &str,
        delta_lines: f64,
        col: usize,
        row: usize,
    ) -> Result<Value, GatewayError> {
        let lock = self.surface_locks.get_surface_mutex(surface_id);
        let _guard = lock.lock().await;
        let frame = self
            .backend
            .handle_scroll(surface_id, delta_lines, col, row)
            .await?;
        self.health.mark_healthy();
        Ok(serde_json::to_value(frame)?)
    }

    /// Handles a single incoming TCP connection.
    pub async fn handle_connection(self: Arc<Self>, stream: TcpStream, peer_addr: SocketAddr) {
        let ws_stream = match tokio_tungstenite::accept_async(stream).await {
            Ok(ws) => ws,
            Err(e) => {
                debug!("WebSocket handshake failed from {}: {}", peer_addr, e);
                return;
            }
        };

        let session_id = uuid::Uuid::new_v4().to_string();
        let (session, control_rx) = ClientSession::new(session_id.clone());

        {
            let mut guard = self.clients.lock();
            guard.push(session.clone());
        }

        info!(
            "Client connected: session_id={} from {}",
            session_id, peer_addr
        );

        let (mut ws_sink, mut ws_stream) = ws_stream.split();

        // Spawn writer task
        let session_writer = session.clone();
        let writer_handle = tokio::spawn(async move {
            session_writer.run_writer(&mut ws_sink, control_rx).await;
        });

        // Reader loop
        while let Some(msg_res) = ws_stream.next().await {
            let msg = match msg_res {
                Ok(m) => m,
                Err(e) => {
                    debug!("WebSocket read error for {}: {}", session_id, e);
                    break;
                }
            };

            let text = match msg {
                Message::Text(t) => t.to_string(),
                Message::Binary(b) => match String::from_utf8(b) {
                    Ok(s) => s,
                    Err(_) => {
                        let _ = session
                            .send_json(json!({
                                "error": "invalid_utf8",
                                "detail": "Binary frame was not valid UTF-8",
                            }))
                            .await;
                        continue;
                    }
                },
                Message::Ping(_) | Message::Pong(_) => continue,
                Message::Close(_) => break,
                Message::Frame(_) => continue,
            };

            let parsed: Value = match serde_json::from_str(&text) {
                Ok(v) => v,
                Err(e) => {
                    let _ = session
                        .send_json(json!({
                            "error": "invalid_json",
                            "detail": e.to_string(),
                        }))
                        .await;
                    continue;
                }
            };

            // 1. Unauthenticated client handshake check
            if !session.is_authenticated() {
                let msg_type = parsed.get("type").and_then(|v| v.as_str());
                let has_token = parsed.get("token").is_some();

                if msg_type == Some("auth") || has_token {
                    let token = parsed.get("token").and_then(|v| v.as_str()).unwrap_or("");
                    if verify_token(&self.auth_token, token) {
                        session.set_authenticated(true);
                        info!("Client {} authenticated successfully", session_id);
                        let auth_ok_payload = build_auth_ok(&session_id);
                        let _ = session
                            .send_json(serde_json::to_value(&auth_ok_payload).unwrap())
                            .await;
                    } else {
                        warn!(
                            "Client {} authentication rejected: invalid token",
                            session_id
                        );
                        let auth_err_payload = build_auth_error_invalid_token();
                        // auth_error must happen-before close frame 1008
                        let _ = session
                            .send_json(serde_json::to_value(&auth_err_payload).unwrap())
                            .await;
                        let _ = session.send_close(1008, "Auth failed").await;
                        session.close();
                        break;
                    }
                } else {
                    warn!(
                        "Client {} authentication rejected: missing auth frame",
                        session_id
                    );
                    let auth_err_payload = build_auth_error_unauthenticated();
                    let _ = session
                        .send_json(serde_json::to_value(&auth_err_payload).unwrap())
                        .await;
                    let _ = session.send_close(1008, "Unauthenticated").await;
                    session.close();
                    break;
                }
                continue;
            }

            // 2. Authenticated JSON-RPC dispatch
            let req_id = parsed.get("id").cloned();
            let method = match parsed.get("method").and_then(|v| v.as_str()) {
                Some(m) => m,
                None => continue,
            };
            let params = parsed.get("params").cloned().unwrap_or_else(|| json!({}));

            let ctx = DispatchContext {
                session: &session,
                backend: &self.backend,
                health: &self.health,
                surface_locks: &self.surface_locks,
                callbacks: self.as_ref(),
            };

            if let Err(e) = dispatch_rpc(ctx, req_id, method, params).await {
                debug!("RPC dispatch error for {}: {}", session_id, e);
            }
        }

        // Cleanup session
        session.close();
        let _ = writer_handle.await;

        let mut guard = self.clients.lock();
        guard.retain(|c| c.session_id != session_id);
        info!(
            "Client disconnected and cleaned up: session_id={}",
            session_id
        );
    }

    /// Spawns background pollers (screen poller, tree poller, and agent event supervisor).
    pub fn spawn_background_tasks(self: &Arc<Self>) {
        let self_screen = self.clone();
        let screen_handle = tokio::spawn(async move {
            ScreenPoller::run(
                self_screen.backend.clone(),
                self_screen.surface_locks.clone(),
                self_screen.health.clone(),
                self_screen.clients.clone(),
                self_screen.priority_surfaces.clone(),
                self_screen.refresh_trigger.clone(),
            )
            .await;
        });

        let self_tree = self.clone();
        let tree_handle = tokio::spawn(async move {
            TreePoller::run(
                self_tree.backend.clone(),
                self_tree.health.clone(),
                move |event, data| {
                    let self_b = self_tree.clone();
                    async move {
                        self_b.broadcast_event(event, data).await;
                    }
                },
            )
            .await;
        });

        let self_agent = self.clone();
        let agent_handle = tokio::spawn(async move {
            AgentEventSupervisor::run(self_agent.backend.clone(), move |event, data| {
                let self_b = self_agent.clone();
                async move {
                    self_b.broadcast_event(event, data).await;
                }
            })
            .await;
        });

        let mut guard = self.tasks.lock();
        guard.push(screen_handle);
        guard.push(tree_handle);
        guard.push(agent_handle);
    }

    /// Starts the gateway TCP listener and background pollers, returning the bound `TcpListener`.
    pub async fn start(self: &Arc<Self>) -> Result<TcpListener, GatewayError> {
        let addr = self.bind_addr();
        let listener = TcpListener::bind(&addr).await?;
        info!("cmux WebSocket Gateway listening on ws://{}", addr);

        self.spawn_background_tasks();
        Ok(listener)
    }

    /// Runs the listener accept loop until a shutdown signal or cancellation.
    pub async fn run_with_listener(
        self: Arc<Self>,
        listener: TcpListener,
    ) -> Result<(), GatewayError> {
        loop {
            tokio::select! {
                _ = self.shutdown_notify.notified() => {
                    info!("Gateway received shutdown notification");
                    break;
                }
                accept_res = listener.accept() => {
                    match accept_res {
                        Ok((stream, peer_addr)) => {
                            let self_conn = self.clone();
                            tokio::spawn(async move {
                                self_conn.handle_connection(stream, peer_addr).await;
                            });
                        }
                        Err(e) => {
                            warn!("Accept error: {}", e);
                        }
                    }
                }
            }
        }

        self.stop().await;
        Ok(())
    }

    /// Stops all background tasks and active client sessions gracefully.
    pub async fn stop(&self) {
        self.shutdown_notify.notify_waiters();

        // Abort background tasks
        let mut tasks = self.tasks.lock();
        for handle in tasks.drain(..) {
            handle.abort();
        }

        // Close all client sessions
        let clients = {
            let mut guard = self.clients.lock();
            let c = guard.clone();
            guard.clear();
            c
        };

        for client in clients {
            client.close();
        }

        info!("Gateway stopped");
    }
}

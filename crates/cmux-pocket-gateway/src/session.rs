use futures_util::SinkExt;
use parking_lot::Mutex;
use serde_json::{json, Value};
use std::collections::HashSet;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use tokio::sync::{mpsc, oneshot, Notify};
use tokio_tungstenite::tungstenite::protocol::frame::coding::CloseCode;
use tokio_tungstenite::tungstenite::protocol::CloseFrame;
use tokio_tungstenite::tungstenite::Message;
use tracing::{error, warn};

use crate::error::GatewayError;

/// Type of control message to be delivered via the FIFO control lane.
pub enum ControlItem {
    Json(Value),
    Close { code: u16, reason: String },
}

/// Structure representing a control message to be delivered via the FIFO control lane.
pub struct ControlMessage {
    pub item: ControlItem,
    pub ack_sender: Option<oneshot::Sender<Result<(), GatewayError>>>,
}

/// Structure representing a coalesced latest-wins render frame slot.
#[derive(Debug, Clone)]
pub struct RenderSlot {
    pub surface_id: String,
    pub focus_generation: u64,
    pub frame: Value,
}

/// Thread-safe interior session state.
#[derive(Debug, Default)]
pub struct SessionState {
    pub active_surface_id: Option<String>,
    pub focus_generation: u64,
    pub subscribed_topics: HashSet<String>,
    pub latest_render_frame: Option<RenderSlot>,
}

/// Manages one connected client session with dual-lane WebSocket scheduling:
/// 1. FIFO control lane for RPC responses, errors, mutations, explicit replays, and close frames.
/// 2. Bounded latest-wins slot for periodic render grid frames.
#[derive(Clone)]
pub struct ClientSession {
    pub session_id: String,
    pub authenticated: Arc<AtomicBool>,
    pub closed: Arc<AtomicBool>,
    state: Arc<Mutex<SessionState>>,
    control_tx: mpsc::UnboundedSender<ControlMessage>,
    notify: Arc<Notify>,
}

impl ClientSession {
    /// Creates a new `ClientSession` and returns both the handle and receiver for the writer loop.
    pub fn new(session_id: String) -> (Self, mpsc::UnboundedReceiver<ControlMessage>) {
        let (control_tx, control_rx) = mpsc::unbounded_channel();
        let notify = Arc::new(Notify::new());
        let session = Self {
            session_id,
            authenticated: Arc::new(AtomicBool::new(false)),
            closed: Arc::new(AtomicBool::new(false)),
            state: Arc::new(Mutex::new(SessionState::default())),
            control_tx,
            notify,
        };
        (session, control_rx)
    }

    /// Checks if the session is authenticated.
    pub fn is_authenticated(&self) -> bool {
        self.authenticated.load(Ordering::SeqCst)
    }

    /// Sets the authentication status of the session.
    pub fn set_authenticated(&self, auth: bool) {
        self.authenticated.store(auth, Ordering::SeqCst);
    }

    /// Checks if the session is closed.
    pub fn is_closed(&self) -> bool {
        self.closed.load(Ordering::SeqCst)
    }

    /// Retrieves the current active surface ID.
    pub fn active_surface_id(&self) -> Option<String> {
        let guard = self.state.lock();
        guard.active_surface_id.clone()
    }

    /// Retrieves the current focus generation counter.
    pub fn focus_generation(&self) -> u64 {
        let guard = self.state.lock();
        guard.focus_generation
    }

    /// Sets the active surface ID. If the active surface changes, increments the focus generation
    /// and clears any pending render frame from previous focus.
    pub fn set_active_surface(&self, surface_id: Option<String>) {
        let mut guard = self.state.lock();
        if guard.active_surface_id != surface_id {
            guard.active_surface_id = surface_id;
            guard.focus_generation = guard.focus_generation.wrapping_add(1);
            guard.latest_render_frame = None;
        }
    }

    /// Adds topics to the client's subscription set.
    pub fn subscribe_topics(&self, topics: &[String]) {
        let mut guard = self.state.lock();
        for t in topics {
            guard.subscribed_topics.insert(t.clone());
        }
    }

    /// Checks if a client is subscribed to a specific topic.
    pub fn is_subscribed_to(&self, topic: &str) -> bool {
        let guard = self.state.lock();
        guard.subscribed_topics.contains(topic)
    }

    /// Retrieves a copy of all currently subscribed topics.
    pub fn subscribed_topics(&self) -> HashSet<String> {
        let guard = self.state.lock();
        guard.subscribed_topics.clone()
    }

    /// Enqueues a JSON message to the FIFO control queue.
    /// If `wait` is true, awaits actual transmission over the WebSocket wire.
    pub async fn enqueue_control(&self, payload: Value, wait: bool) -> Result<(), GatewayError> {
        if self.is_closed() {
            return Err(GatewayError::SessionClosed);
        }

        let (ack_sender, ack_receiver) = if wait {
            let (tx, rx) = oneshot::channel();
            (Some(tx), Some(rx))
        } else {
            (None, None)
        };

        self.control_tx
            .send(ControlMessage {
                item: ControlItem::Json(payload),
                ack_sender,
            })
            .map_err(|_| GatewayError::SessionClosed)?;

        self.notify.notify_one();

        if let Some(rx) = ack_receiver {
            match rx.await {
                Ok(res) => res,
                Err(_) => Err(GatewayError::SessionClosed),
            }
        } else {
            Ok(())
        }
    }

    /// Convenience wrapper for sending JSON messages with transmission guarantee.
    pub async fn send_json(&self, payload: Value) -> Result<(), GatewayError> {
        self.enqueue_control(payload, true).await
    }

    /// Enqueues a WebSocket close frame to the FIFO control queue and awaits its delivery.
    pub async fn send_close(
        &self,
        code: u16,
        reason: impl Into<String>,
    ) -> Result<(), GatewayError> {
        if self.is_closed() {
            return Ok(());
        }

        let (tx, rx) = oneshot::channel();
        let msg = ControlMessage {
            item: ControlItem::Close {
                code,
                reason: reason.into(),
            },
            ack_sender: Some(tx),
        };

        self.control_tx
            .send(msg)
            .map_err(|_| GatewayError::SessionClosed)?;

        self.notify.notify_one();
        let _ = rx.await;
        Ok(())
    }

    /// Enqueues or coalesces a render frame.
    /// Full frames coalesce in the bounded latest-wins slot.
    /// Non-full/delta frames return `false` indicating priority refresh recovery is required.
    pub fn enqueue_render_frame(&self, surface_id: &str, focus_gen: u64, frame: Value) -> bool {
        if self.is_closed() {
            return true;
        }

        let mut guard = self.state.lock();
        // Discard frame if active surface or focus generation does not match
        if guard.active_surface_id.as_deref() != Some(surface_id)
            || guard.focus_generation != focus_gen
        {
            return true;
        }

        let is_full = frame.get("full").and_then(|v| v.as_bool()).unwrap_or(true);

        if !is_full {
            // Delta frame cannot be safely buffered in unbounded queue; caller should trigger recovery
            return false;
        }

        guard.latest_render_frame = Some(RenderSlot {
            surface_id: surface_id.to_string(),
            focus_generation: focus_gen,
            frame,
        });

        drop(guard);
        self.notify.notify_one();
        true
    }

    /// Returns the currently pending latest render frame, if any.
    pub fn get_latest_render_frame(&self) -> Option<RenderSlot> {
        let guard = self.state.lock();
        guard.latest_render_frame.clone()
    }

    /// Closes the session and unblocks any waiting callers.
    pub fn close(&self) {
        if !self.closed.swap(true, Ordering::SeqCst) {
            let mut guard = self.state.lock();
            guard.latest_render_frame = None;
            drop(guard);
            self.notify.notify_waiters();
        }
    }

    /// Runs the writer loop on the split WebSocket sink until session close.
    pub async fn run_writer<S>(
        self,
        mut sink: S,
        mut control_rx: mpsc::UnboundedReceiver<ControlMessage>,
    ) where
        S: futures_util::Sink<Message, Error = tokio_tungstenite::tungstenite::Error> + Unpin,
    {
        while !self.is_closed() {
            // Check if there are messages to send
            let mut has_control = false;
            let mut render_to_send: Option<RenderSlot> = None;

            {
                let mut guard = self.state.lock();
                // Check render slot
                if let Some(slot) = guard.latest_render_frame.take() {
                    if guard.active_surface_id.as_deref() == Some(&slot.surface_id)
                        && guard.focus_generation == slot.focus_generation
                    {
                        render_to_send = Some(slot);
                    }
                }
            }

            // 1. Drain control queue
            while let Ok(msg) = control_rx.try_recv() {
                has_control = true;
                match msg.item {
                    ControlItem::Json(val) => {
                        let raw = match serde_json::to_string(&val) {
                            Ok(s) => s,
                            Err(e) => {
                                error!("Failed to serialize control message: {}", e);
                                if let Some(ack) = msg.ack_sender {
                                    let _ = ack.send(Err(GatewayError::Json(e)));
                                }
                                continue;
                            }
                        };

                        let send_res = sink.send(Message::Text(raw)).await;
                        match send_res {
                            Ok(()) => {
                                if let Some(ack) = msg.ack_sender {
                                    let _ = ack.send(Ok(()));
                                }
                            }
                            Err(e) => {
                                warn!("WebSocket send error: {}", e);
                                if let Some(ack) = msg.ack_sender {
                                    let _ = ack.send(Err(GatewayError::WebSocket(Box::new(e))));
                                }
                                self.close();
                                break;
                            }
                        }
                    }
                    ControlItem::Close { code, reason } => {
                        let close_msg = Message::Close(Some(CloseFrame {
                            code: CloseCode::from(code),
                            reason: reason.into(),
                        }));
                        let _ = sink.send(close_msg).await;
                        if let Some(ack) = msg.ack_sender {
                            let _ = ack.send(Ok(()));
                        }
                        self.close();
                        break;
                    }
                }
            }

            // If we broke due to send error or close frame, exit
            if self.is_closed() {
                break;
            }

            // 2. Deliver latest render frame if valid
            if let Some(slot) = render_to_send.take() {
                let frame_msg = json!({
                    "event": "terminal.render_grid",
                    "data": slot.frame,
                });
                if let Ok(raw) = serde_json::to_string(&frame_msg) {
                    if let Err(e) = sink.send(Message::Text(raw)).await {
                        warn!("WebSocket render frame send error: {}", e);
                        self.close();
                        break;
                    }
                }
            }

            // 3. Wait for new work if nothing was processed
            if !has_control && render_to_send.is_none() && !self.is_closed() {
                self.notify.notified().await;
            }
        }

        // Drain any remaining control messages with SessionClosed error
        while let Ok(msg) = control_rx.try_recv() {
            if let Some(ack) = msg.ack_sender {
                let _ = ack.send(Err(GatewayError::SessionClosed));
            }
        }
    }
}

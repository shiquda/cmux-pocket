use cmux_pocket_cmux::CmuxBackend;
use cmux_pocket_protocol::agent::{
    completion_notice_from_json, completion_notice_from_record, parse_agent_completion_event,
    select_completion_notice, AgentSessionCompleted, CompletionNotice,
};
use parking_lot::Mutex;
use serde_json::Value;
use std::collections::{HashMap, HashSet, VecDeque};
use std::future::Future;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::time::sleep;
use tracing::{debug, info};

use crate::identity::SurfaceIdentityMap;

const MAX_DEDUP_ENTRIES: usize = 2048;

/// How long one completion suppresses another completion for the same surface.
///
/// cmux publishes a turn's Stop hook twice — identical timestamp and session,
/// different event ids — and can also emit a completion notification for the
/// same turn. Event-id deduplication cannot see that, so the Gateway forwards at
/// most one completion per surface inside this window. A surface has a single
/// top-level agent session, so a genuinely distinct turn cannot finish twice
/// this fast. A specific reply may still replace a generic status inside the
/// window, using the original event id so the client updates one notification.
const COMPLETION_SURFACE_WINDOW: Duration = Duration::from_secs(3);

/// In-process bounded deduplication set for agent event IDs.
#[derive(Debug, Default)]
pub struct EventDedup {
    set: HashSet<String>,
    queue: VecDeque<String>,
}

impl EventDedup {
    pub fn new() -> Self {
        Self::default()
    }

    /// Checks if an event ID is already seen. If not, records it.
    /// Returns `true` if this is a NEW (unseen) event ID, `false` if already seen.
    pub fn check_and_record(&mut self, event_id: &str) -> bool {
        if self.set.contains(event_id) {
            return false;
        }

        if self.queue.len() >= MAX_DEDUP_ENTRIES {
            if let Some(oldest) = self.queue.pop_front() {
                self.set.remove(&oldest);
            }
        }

        self.set.insert(event_id.to_string());
        self.queue.push_back(event_id.to_string());
        true
    }
}

/// Collapses the several completion signals cmux emits for one finished turn,
/// while still letting a specific reply replace a generic status.
#[derive(Debug, Default)]
pub struct SurfaceCompletionGate {
    last_forwarded: HashMap<String, ForwardedCompletion>,
}

#[derive(Debug, Clone)]
struct ForwardedCompletion {
    at: Instant,
    event_id: String,
    has_reply: bool,
}

/// What the supervisor should do with one completion signal.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum CompletionForward {
    /// First signal for this surface inside the window.
    New,
    /// A reply arrived after a generic alert. Reuse `event_id` so the client
    /// updates that notification instead of posting a second one.
    Replace { event_id: String },
    /// Duplicate, or a generic status that must not cover a reply.
    Drop,
}

impl SurfaceCompletionGate {
    pub fn new() -> Self {
        Self::default()
    }

    /// Returns true when a completion for `surface_id` should be forwarded.
    pub fn accept(&mut self, surface_id: &str, now: Instant) -> bool {
        matches!(
            self.decide(surface_id, now, false, ""),
            CompletionForward::New
        )
    }

    pub fn decide(
        &mut self,
        surface_id: &str,
        now: Instant,
        has_reply: bool,
        event_id: &str,
    ) -> CompletionForward {
        self.last_forwarded.retain(|_, forwarded| {
            now.saturating_duration_since(forwarded.at) < COMPLETION_SURFACE_WINDOW
        });
        if let Some(previous) = self.last_forwarded.get(surface_id) {
            if has_reply && !previous.has_reply {
                let event_id = previous.event_id.clone();
                self.last_forwarded.insert(
                    surface_id.to_string(),
                    ForwardedCompletion {
                        at: previous.at,
                        event_id: event_id.clone(),
                        has_reply: true,
                    },
                );
                return CompletionForward::Replace { event_id };
            }
            return CompletionForward::Drop;
        }
        self.last_forwarded.insert(
            surface_id.to_string(),
            ForwardedCompletion {
                at: now,
                event_id: event_id.to_string(),
                has_reply,
            },
        );
        CompletionForward::New
    }
}

pub struct AgentEventSupervisor;

impl AgentEventSupervisor {
    /// Watches cmux agent events and forwards completions to clients.
    ///
    /// cmux reports the durable surface UUID; clients address the short
    /// `surface:N` ref, so every completion is translated through `identity`
    /// before it is broadcast. An unmapped UUID is forwarded unchanged rather
    /// than attributed to another surface.
    pub async fn run<F, Fut>(
        backend: Arc<dyn CmuxBackend>,
        identity: SurfaceIdentityMap,
        broadcast_fn: F,
    ) where
        F: Fn(&'static str, Value) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = ()> + Send + 'static,
    {
        let dedup = Arc::new(Mutex::new(EventDedup::new()));
        let mut completion_gate = SurfaceCompletionGate::new();

        loop {
            match backend.spawn_events_stream().await {
                Ok(mut stream) => {
                    info!("Agent event stream attached");

                    while let Ok(Some(event)) = stream.next_event().await {
                        let occurred_at = event.get("occurred_at").and_then(|v| v.as_str());
                        let mut completion = completion_from_event(&event, &backend).await;

                        if let Some(comp) = completion.as_mut() {
                            if comp.body.is_none() {
                                let notices = load_completion_notices(backend.as_ref()).await;
                                if let Some(notice) = select_completion_notice(
                                    &notices,
                                    None,
                                    &comp.surface_id,
                                    occurred_at,
                                ) {
                                    comp.body = notice.reply.clone();
                                }
                            }
                            if let Some(surface_ref) = identity.resolve(&comp.surface_id) {
                                comp.surface_id = surface_ref;
                            }
                        }

                        if let Some(mut comp) = completion {
                            let incoming_id = comp.event_id.clone();
                            let fresh = if let Some(event_id) = &incoming_id {
                                dedup.lock().check_and_record(event_id)
                            } else {
                                true
                            };
                            if !fresh {
                                continue;
                            }

                            let decision = completion_gate.decide(
                                &comp.surface_id,
                                Instant::now(),
                                comp.body.is_some(),
                                incoming_id.as_deref().unwrap_or(""),
                            );
                            match decision {
                                CompletionForward::Drop => {}
                                CompletionForward::New => {
                                    if let Ok(comp_val) = serde_json::to_value(&comp) {
                                        broadcast_fn("agent.session.completed", comp_val).await;
                                    }
                                }
                                CompletionForward::Replace { event_id } => {
                                    comp.event_id = Some(event_id);
                                    comp.content_update = true;
                                    if let Ok(comp_val) = serde_json::to_value(&comp) {
                                        broadcast_fn("agent.session.completed", comp_val).await;
                                    }
                                }
                            }
                        }
                    }

                    debug!("Agent event stream ended, will reconnect");
                }
                Err(e) => {
                    debug!("Failed to spawn agent events stream: {}", e);
                }
            }

            sleep(Duration::from_secs(2)).await;
        }
    }
}

async fn completion_from_event(
    event: &Value,
    backend: &Arc<dyn CmuxBackend>,
) -> Option<AgentSessionCompleted> {
    if let Some(completion) = parse_agent_completion_event(event) {
        return Some(completion);
    }
    if event.get("name").and_then(|v| v.as_str()) != Some("notification.created") {
        return None;
    }
    let payload = event.get("payload");
    let notification_id = payload
        .and_then(|p| p.get("notification_id"))
        .and_then(|v| v.as_str())?;
    let surface_id = event
        .get("surface_id")
        .and_then(|v| v.as_str())
        .or_else(|| {
            payload
                .and_then(|p| p.get("surface_id"))
                .and_then(|v| v.as_str())
        })?;
    let notices = load_completion_notices(backend.as_ref()).await;
    let notice = select_completion_notice(&notices, Some(notification_id), surface_id, None)?;
    let event_id = event
        .get("id")
        .and_then(|v| v.as_str())
        .map(ToString::to_string)
        .or_else(|| Some(notification_id.to_string()));
    let workspace_id = event
        .get("workspace_id")
        .and_then(|v| v.as_str())
        .or_else(|| {
            payload
                .and_then(|p| p.get("workspace_id"))
                .and_then(|v| v.as_str())
        })
        .map(ToString::to_string)
        .or_else(|| notice.workspace_id.clone());
    let mut completion =
        AgentSessionCompleted::with_details(surface_id, event_id, workspace_id, None);
    completion.body = notice.reply.clone();
    Some(completion)
}

async fn load_completion_notices(backend: &dyn CmuxBackend) -> Vec<CompletionNotice> {
    if let Ok(details) = backend.list_notification_details().await {
        return details
            .iter()
            .filter_map(completion_notice_from_json)
            .collect();
    }
    match backend.list_notifications().await {
        Ok(records) => records
            .iter()
            .filter_map(|record| completion_notice_from_record(record))
            .collect(),
        Err(_) => Vec::new(),
    }
}

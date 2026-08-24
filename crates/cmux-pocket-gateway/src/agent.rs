use cmux_pocket_cmux::CmuxBackend;
use cmux_pocket_protocol::agent::{
    notification_record_is_completion, parse_agent_completion_event, AgentSessionCompleted,
};
use parking_lot::Mutex;
use serde_json::Value;
use std::collections::{HashSet, VecDeque};
use std::future::Future;
use std::sync::Arc;
use std::time::Duration;
use tokio::time::sleep;
use tracing::{debug, info};

const MAX_DEDUP_ENTRIES: usize = 2048;

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

pub struct AgentEventSupervisor;

impl AgentEventSupervisor {
    pub async fn run<F, Fut>(backend: Arc<dyn CmuxBackend>, broadcast_fn: F)
    where
        F: Fn(&'static str, Value) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = ()> + Send + 'static,
    {
        let dedup = Arc::new(Mutex::new(EventDedup::new()));

        loop {
            match backend.spawn_events_stream().await {
                Ok(mut stream) => {
                    info!("Agent event stream attached");

                    while let Ok(Some(event)) = stream.next_event().await {
                        let mut completion = parse_agent_completion_event(&event);

                        // Fallback for notification.created
                        if completion.is_none()
                            && event.get("name").and_then(|v| v.as_str())
                                == Some("notification.created")
                        {
                            let payload = event.get("payload");
                            let notification_id = payload
                                .and_then(|p| p.get("notification_id"))
                                .and_then(|v| v.as_str());
                            let surface_id = event
                                .get("surface_id")
                                .and_then(|v| v.as_str())
                                .or_else(|| {
                                    payload
                                        .and_then(|p| p.get("surface_id"))
                                        .and_then(|v| v.as_str())
                                });

                            if let (Some(notif_id), Some(surf_id)) = (notification_id, surface_id) {
                                if let Ok(records) = backend.list_notifications().await {
                                    let matched = records
                                        .iter()
                                        .any(|r| notification_record_is_completion(r, notif_id));
                                    if matched {
                                        let event_id = event
                                            .get("id")
                                            .and_then(|v| v.as_str())
                                            .map(|s| s.to_string())
                                            .or_else(|| Some(notif_id.to_string()));
                                        let ws_id = event
                                            .get("workspace_id")
                                            .and_then(|v| v.as_str())
                                            .or_else(|| {
                                                payload
                                                    .and_then(|p| p.get("workspace_id"))
                                                    .and_then(|v| v.as_str())
                                            })
                                            .map(|s| s.to_string());

                                        completion = Some(AgentSessionCompleted::with_details(
                                            surf_id, event_id, ws_id, None,
                                        ));
                                    }
                                }
                            }
                        }

                        if let Some(comp) = completion {
                            let should_forward = if let Some(e_id) = &comp.event_id {
                                let mut d_guard = dedup.lock();
                                d_guard.check_and_record(e_id)
                            } else {
                                true
                            };

                            if should_forward {
                                if let Ok(comp_val) = serde_json::to_value(&comp) {
                                    broadcast_fn("agent.session.completed", comp_val).await;
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

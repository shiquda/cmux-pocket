use cmux_pocket_cmux::CmuxBackend;
use cmux_pocket_protocol::workspace::workspace_tree_signature;
use parking_lot::Mutex;
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::future::Future;
use std::sync::Arc;
use std::time::Duration;
use tokio::sync::Notify;
use tokio::time::sleep;
use tracing::{debug, warn};

use crate::health::HealthTracker;
use crate::session::ClientSession;
use crate::surface_locks::SurfaceLockManager;

pub async fn fanout_screen_snapshots(
    clients: &[ClientSession],
    backend: &Arc<dyn CmuxBackend>,
    surface_locks: &SurfaceLockManager,
    health: &HealthTracker,
    priority_surfaces: &HashSet<String>,
) {
    // 1. Group authenticated clients subscribed to "terminal.render_grid" by surface_id
    let mut surface_map: HashMap<String, Vec<(ClientSession, u64)>> = HashMap::new();

    for client in clients {
        if client.is_authenticated() && client.is_subscribed_to("terminal.render_grid") {
            if let Some(surf_id) = client.active_surface_id() {
                let focus_gen = client.focus_generation();
                surface_map
                    .entry(surf_id)
                    .or_default()
                    .push((client.clone(), focus_gen));
            }
        }
    }

    if surface_map.is_empty() {
        return;
    }

    // Sort surfaces so priority surfaces are fetched first
    let mut sorted_surfaces: Vec<String> = surface_map.keys().cloned().collect();
    sorted_surfaces.sort_by_key(|sid| {
        if priority_surfaces.contains(sid) {
            0
        } else {
            1
        }
    });

    // Spawn concurrent tasks for each distinct surface
    let mut handles = Vec::new();
    for surf_id in sorted_surfaces {
        if let Some(target_clients) = surface_map.remove(&surf_id) {
            let backend_clone = backend.clone();
            let lock = surface_locks.get_surface_mutex(&surf_id);
            let health_clone = health.clone();
            let sid = surf_id.clone();

            handles.push(tokio::spawn(async move {
                let _guard = lock.lock().await;
                match backend_clone.get_snapshot(&sid, 0).await {
                    Ok(snapshot) => {
                        health_clone.mark_healthy();
                        if let Ok(frame_val) = serde_json::to_value(&snapshot) {
                            for (client, focus_gen) in target_clients {
                                client.enqueue_render_frame(&sid, focus_gen, frame_val.clone());
                            }
                        }
                    }
                    Err(e) => {
                        warn!("Snapshot fetch failed for surface {}: {}", sid, e);
                        health_clone.mark_unhealthy(e.to_string());
                    }
                }
            }));
        }
    }

    for handle in handles {
        let _ = handle.await;
    }
}

pub struct ScreenPoller;

impl ScreenPoller {
    pub async fn run(
        backend: Arc<dyn CmuxBackend>,
        surface_locks: SurfaceLockManager,
        health: HealthTracker,
        clients: Arc<Mutex<Vec<ClientSession>>>,
        priority_surfaces: Arc<Mutex<HashSet<String>>>,
        refresh_trigger: Arc<Notify>,
    ) {
        loop {
            // Wait for either refresh trigger or timeout (50ms)
            tokio::select! {
                _ = refresh_trigger.notified() => {},
                _ = sleep(Duration::from_millis(50)) => {},
            }

            let priority: HashSet<String> = {
                let mut guard = priority_surfaces.lock();
                let p = guard.clone();
                guard.clear();
                p
            };

            let client_list: Vec<ClientSession> = {
                let guard = clients.lock();
                guard.clone()
            };

            if !client_list.is_empty() {
                fanout_screen_snapshots(&client_list, &backend, &surface_locks, &health, &priority)
                    .await;
            }
        }
    }
}

pub struct TreePoller;

impl TreePoller {
    pub async fn run<F, Fut>(backend: Arc<dyn CmuxBackend>, health: HealthTracker, broadcast_fn: F)
    where
        F: Fn(&'static str, Value) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = ()> + Send + 'static,
    {
        let mut last_tree_sig: Option<String> = None;

        loop {
            sleep(Duration::from_secs(5)).await;

            match backend.list_workspaces().await {
                Ok(workspaces) => {
                    health.mark_healthy();
                    let sig = workspace_tree_signature(&workspaces);
                    if last_tree_sig.as_deref() != Some(&sig) {
                        last_tree_sig = Some(sig);
                        let broadcast_data = json!({
                            "action": "sync",
                            "workspaces": workspaces,
                        });
                        broadcast_fn("workspace.tree", broadcast_data).await;
                    }
                }
                Err(e) => {
                    debug!("Tree poller list_workspaces error: {}", e);
                    health.mark_unhealthy(e.to_string());
                }
            }
        }
    }
}

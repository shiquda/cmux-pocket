//! Lease-based ownership of the host's mobile viewport reports.
//!
//! A phone cannot own the PTY geometry outright: the cmux host keeps one grid
//! per surface, takes the smallest reported viewport across attached clients,
//! and lets the Mac pane constrain the result further. This module therefore
//! only *reports* a desired grid and never claims to know the applied one.
//!
//! Reports are sent as non-sticky piggybacks on `mobile.terminal.replay`
//! (the host expires them about five seconds after the last renewal). A sticky
//! report from the dedicated `mobile.terminal.viewport` call would survive a
//! Gateway crash, so it is used for `clear` only. While a lease is alive it is
//! renewed at a low frequency; stopping renewal (crash, power loss) lets the
//! host restore the laptop geometry by itself.

use std::collections::HashMap;
use std::future::Future;
use std::sync::Arc;
use std::time::Duration;

use cmux_pocket_cmux::{CmuxBackend, CmuxError};
use parking_lot::Mutex;
use tokio::sync::{Mutex as AsyncMutex, Notify};
use tokio::task::JoinHandle;
use tokio::time::{sleep, timeout};
use tracing::{debug, warn};

use crate::surface_locks::SurfaceLockManager;

/// Renewal cadence. The host TTL is five seconds with a one-second cleanup
/// timer, so a fifteen-hundred-millisecond renewal leaves room for a slow
/// `cmux` CLI call without letting the report lapse.
pub const VIEWPORT_LEASE_RENEW_INTERVAL: Duration = Duration::from_millis(1500);

/// Host clamps for a reported grid, mirroring `applyMobileViewportReport`.
pub const VIEWPORT_MIN_COLUMNS: u32 = 20;
pub const VIEWPORT_MAX_COLUMNS: u32 = 300;
pub const VIEWPORT_MIN_ROWS: u32 = 5;
pub const VIEWPORT_MAX_ROWS: u32 = 120;

const TRANSITION_RETRY_DELAY: Duration = Duration::from_millis(250);
const TRANSITION_RETRIES: usize = 4;

/// Result of a single viewport report.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ViewportReportOutcome {
    /// False when the report was fenced out by a newer generation.
    pub accepted: bool,
    /// True when the host accepted the report but its grid is still settling.
    pub pending: bool,
    pub requested_columns: u32,
    pub requested_rows: u32,
    /// Geometry captured from the authoritative frame after the host applied
    /// the report, when the frame was already settled.
    pub effective_columns: Option<u32>,
    pub effective_rows: Option<u32>,
    pub generation: u64,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
struct LeaseTarget {
    columns: u32,
    rows: u32,
    generation: u64,
}

struct Lease {
    key: String,
    session_id: String,
    client_id: String,
    surface_id: String,
    target: Mutex<LeaseTarget>,
    /// Last target whose geometry the host confirmed.
    applied: Mutex<Option<LeaseTarget>>,
    /// Serializes host calls for this lease so a stale in-flight report cannot
    /// land after a newer one.
    apply_lock: AsyncMutex<()>,
    cancel: Notify,
    wake: Notify,
    task: Mutex<Option<JoinHandle<()>>>,
}

struct Inner {
    backend: Arc<dyn CmuxBackend>,
    surface_locks: SurfaceLockManager,
    leases: Mutex<HashMap<String, Arc<Lease>>>,
}

/// Manages the lifetime of every client's viewport report.
#[derive(Clone)]
pub struct ViewportLeaseManager {
    inner: Arc<Inner>,
}

impl ViewportLeaseManager {
    pub fn new(backend: Arc<dyn CmuxBackend>, surface_locks: SurfaceLockManager) -> Self {
        Self {
            inner: Arc::new(Inner {
                backend,
                surface_locks,
                leases: Mutex::new(HashMap::new()),
            }),
        }
    }

    pub async fn supported(&self) -> bool {
        self.inner.backend.supports_viewport_resize().await
    }

    /// Records a client's desired grid, applies it once synchronously, and
    /// keeps renewing it until cleared or its session ends.
    pub async fn report(
        &self,
        session_id: &str,
        client_id: &str,
        surface_id: &str,
        columns: u32,
        rows: u32,
        generation: u64,
    ) -> Result<ViewportReportOutcome, CmuxError> {
        let columns = columns.clamp(VIEWPORT_MIN_COLUMNS, VIEWPORT_MAX_COLUMNS);
        let rows = rows.clamp(VIEWPORT_MIN_ROWS, VIEWPORT_MAX_ROWS);

        let lease = self.get_or_create_lease(session_id, client_id, surface_id);
        {
            let mut target = lease.target.lock();
            if generation < target.generation {
                return Ok(ViewportReportOutcome {
                    accepted: false,
                    pending: false,
                    requested_columns: columns,
                    requested_rows: rows,
                    effective_columns: None,
                    effective_rows: None,
                    generation,
                });
            }
            *target = LeaseTarget {
                columns,
                rows,
                generation,
            };
        }

        // Apply the newest target once under the lease lock. This call runs
        // inside the session's request loop, so a host that is still resizing
        // must not keep it here retrying: the response is immediately
        // `pending`, and the lease task retries and renews in the background.
        let _apply = lease.apply_lock.lock().await;
        let target = *lease.target.lock();
        let result = self.apply_target(&lease, target, false).await;
        if matches!(result, Ok(Some(_))) {
            *lease.applied.lock() = Some(target);
        }
        lease.wake.notify_one();
        result.map(|effective| ViewportReportOutcome {
            accepted: true,
            pending: effective.is_none(),
            requested_columns: target.columns,
            requested_rows: target.rows,
            effective_columns: effective.map(|(c, _)| c),
            effective_rows: effective.map(|(_, r)| r),
            generation: target.generation,
        })
    }

    /// Releases one client's report, then reads back the restored geometry.
    pub async fn clear(
        &self,
        session_id: &str,
        client_id: &str,
        surface_id: &str,
        generation: u64,
    ) -> Result<Option<(u32, u32)>, CmuxError> {
        let key = lease_key(session_id, client_id, surface_id);
        let lease = {
            let mut leases = self.inner.leases.lock();
            let existing = leases.get(&key).cloned();
            if let Some(lease) = &existing {
                if generation < lease.target.lock().generation {
                    // A newer report owns this lease; this clear is stale.
                    return Ok(None);
                }
            }
            leases.remove(&key);
            existing
        };
        if let Some(lease) = &lease {
            stop_task(lease).await;
        }

        let clear_result = self
            .with_surface_lock(surface_id, async {
                self.inner
                    .backend
                    .clear_viewport_report(surface_id, client_id, generation)
                    .await
            })
            .await;

        // The host clear is idempotent enough for our purposes; report a hard
        // failure so the client can retry, but still try to read the geometry.
        clear_result?;

        let snapshot = self
            .with_surface_lock(surface_id, async {
                self.inner.backend.get_snapshot(surface_id, 0).await
            })
            .await?;
        Ok(Some((snapshot.columns as u32, snapshot.rows as u32)))
    }

    /// Drops every lease owned by a closed WebSocket session.
    pub async fn release_session(&self, session_id: &str) {
        let keys: Vec<String> = {
            let leases = self.inner.leases.lock();
            leases
                .values()
                .filter(|lease| lease.session_id == session_id)
                .map(|lease| lease.key.clone())
                .collect()
        };
        for key in keys {
            let lease = { self.inner.leases.lock().remove(&key) };
            if let Some(lease) = lease {
                stop_task(&lease).await;
                let generation = lease.target.lock().generation;
                let result = self
                    .with_surface_lock(&lease.surface_id, async {
                        self.inner
                            .backend
                            .clear_viewport_report(&lease.surface_id, &lease.client_id, generation)
                            .await
                    })
                    .await;
                if let Err(e) = result {
                    warn!(
                        surface = %lease.surface_id,
                        "failed to clear viewport report on session close: {e}"
                    );
                }
            }
        }
    }

    /// Best-effort release of all leases during Gateway shutdown.
    pub async fn shutdown(&self) {
        let keys: Vec<String> = {
            let leases = self.inner.leases.lock();
            leases.keys().cloned().collect()
        };
        for key in keys {
            let lease = { self.inner.leases.lock().remove(&key) };
            if let Some(lease) = lease {
                stop_task(&lease).await;
                let generation = lease.target.lock().generation;
                let _ = timeout(
                    Duration::from_secs(2),
                    self.with_surface_lock(&lease.surface_id, async {
                        self.inner
                            .backend
                            .clear_viewport_report(&lease.surface_id, &lease.client_id, generation)
                            .await
                    }),
                )
                .await;
            }
        }
    }

    fn get_or_create_lease(
        &self,
        session_id: &str,
        client_id: &str,
        surface_id: &str,
    ) -> Arc<Lease> {
        let key = lease_key(session_id, client_id, surface_id);
        if let Some(lease) = self.inner.leases.lock().get(&key).cloned() {
            return lease;
        }

        let lease = Arc::new(Lease {
            key: key.clone(),
            session_id: session_id.to_string(),
            client_id: client_id.to_string(),
            surface_id: surface_id.to_string(),
            target: Mutex::new(LeaseTarget {
                columns: VIEWPORT_MIN_COLUMNS,
                rows: VIEWPORT_MIN_ROWS,
                generation: 0,
            }),
            applied: Mutex::new(None),
            apply_lock: AsyncMutex::new(()),
            cancel: Notify::new(),
            wake: Notify::new(),
            task: Mutex::new(None),
        });

        let mut leases = self.inner.leases.lock();
        if let Some(existing) = leases.get(&key).cloned() {
            return existing;
        }
        leases.insert(key, lease.clone());
        drop(leases);

        let inner = self.inner.clone();
        let task_lease = lease.clone();
        let handle = tokio::spawn(async move {
            run_lease_loop(inner, task_lease).await;
        });
        *lease.task.lock() = Some(handle);
        lease
    }

    async fn apply_target(
        &self,
        lease: &Lease,
        target: LeaseTarget,
        retry_transition: bool,
    ) -> Result<Option<(u32, u32)>, CmuxError> {
        let mut attempts = 0usize;
        loop {
            let result = self
                .with_surface_lock(&lease.surface_id, async {
                    self.inner
                        .backend
                        .apply_viewport_report(
                            &lease.surface_id,
                            target.columns,
                            target.rows,
                            &lease.client_id,
                            target.generation,
                        )
                        .await
                })
                .await;

            match result {
                Ok(frame) => {
                    debug!(
                        surface = %lease.surface_id,
                        requested = %format!("{}x{}", target.columns, target.rows),
                        effective = %format!("{}x{}", frame.columns, frame.rows),
                        "viewport report applied"
                    );
                    return Ok(Some((frame.columns as u32, frame.rows as u32)));
                }
                Err(e) if e.is_viewport_transition() => {
                    if !retry_transition || attempts >= TRANSITION_RETRIES {
                        return Ok(None);
                    }
                    attempts += 1;
                    sleep(TRANSITION_RETRY_DELAY).await;
                }
                Err(e) => return Err(e),
            }
        }
    }

    async fn with_surface_lock<F, T>(&self, surface_id: &str, future: F) -> T
    where
        F: Future<Output = T>,
    {
        let lock = self.inner.surface_locks.get_surface_mutex(surface_id);
        let _guard = lock.lock().await;
        future.await
    }
}

async fn stop_task(lease: &Arc<Lease>) {
    lease.cancel.notify_waiters();
    let handle = lease.task.lock().take();
    if let Some(handle) = handle {
        handle.abort();
        let _ = handle.await;
    }
    // Wait until a possibly in-flight host call leaves the lease.
    let _ = lease.apply_lock.lock().await;
}

fn lease_key(session_id: &str, client_id: &str, surface_id: &str) -> String {
    format!("{session_id}\u{1f}{client_id}\u{1f}{surface_id}")
}

async fn run_lease_loop(inner: Arc<Inner>, lease: Arc<Lease>) {
    loop {
        let target = *lease.target.lock();
        let last_applied = *lease.applied.lock();
        if target.generation != 0 && last_applied != Some(target) {
            let _apply = lease.apply_lock.lock().await;
            // Re-read: a newer report may have landed while waiting.
            let current = *lease.target.lock();
            if *lease.applied.lock() != Some(current) {
                let mut attempts = 0usize;
                loop {
                    let result = {
                        let lock = inner.surface_locks.get_surface_mutex(&lease.surface_id);
                        let _guard = lock.lock().await;
                        inner
                            .backend
                            .apply_viewport_report(
                                &lease.surface_id,
                                current.columns,
                                current.rows,
                                &lease.client_id,
                                current.generation,
                            )
                            .await
                    };
                    match result {
                        Ok(frame) => {
                            debug!(
                                surface = %lease.surface_id,
                                effective = %format!("{}x{}", frame.columns, frame.rows),
                                "viewport lease renewed"
                            );
                            *lease.applied.lock() = Some(current);
                            break;
                        }
                        Err(e) if e.is_viewport_transition() => {
                            if attempts >= TRANSITION_RETRIES {
                                // Keep the lease alive; the next renewal retries.
                                break;
                            }
                            attempts += 1;
                            sleep(TRANSITION_RETRY_DELAY).await;
                        }
                        Err(e) => {
                            warn!(
                                surface = %lease.surface_id,
                                "viewport lease renewal failed: {e}"
                            );
                            break;
                        }
                    }
                }
            }
        }

        tokio::select! {
            _ = lease.cancel.notified() => break,
            // A newer target arrived: loop and apply only if it differs from
            // the last confirmed one.
            _ = lease.wake.notified() => {},
            // Periodic renewal: force one report even when nothing changed so
            // the host's non-sticky report never expires while we are attached.
            _ = sleep(VIEWPORT_LEASE_RENEW_INTERVAL) => {
                *lease.applied.lock() = None;
            },
        }
    }
}

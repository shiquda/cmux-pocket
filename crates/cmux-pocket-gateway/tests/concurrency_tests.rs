use async_trait::async_trait;
use cmux_pocket_cmux::error::CmuxError;
use cmux_pocket_cmux::events::CmuxEventStream;
use cmux_pocket_cmux::{CmuxBackend, MockCmuxBackend};
use cmux_pocket_gateway::CmuxGateway;
use cmux_pocket_protocol::{BackendHealth, RenderGridFrame, SurfaceInfo, WorkspaceInfo};
use parking_lot::Mutex;
use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::time::sleep;

type Timeline = Arc<Mutex<Vec<(&'static str, String, usize, Instant)>>>;
struct ConcurrencyTestBackend {
    inner: MockCmuxBackend,
    timeline: Timeline,
}

impl ConcurrencyTestBackend {
    fn new(timeline: Timeline) -> Self {
        Self {
            inner: MockCmuxBackend::default(),
            timeline,
        }
    }
}

#[async_trait]
impl CmuxBackend for ConcurrencyTestBackend {
    async fn ping(&self) -> Result<(), CmuxError> {
        self.inner.ping().await
    }
    async fn health(&self) -> Result<BackendHealth, CmuxError> {
        self.inner.health().await
    }
    async fn list_workspaces(&self) -> Result<Vec<WorkspaceInfo>, CmuxError> {
        self.inner.list_workspaces().await
    }
    async fn create_workspace(&self, name: &str) -> Result<WorkspaceInfo, CmuxError> {
        self.inner.create_workspace(name).await
    }
    async fn select_workspace(&self, key: &str) -> Result<(), CmuxError> {
        self.inner.select_workspace(key).await
    }
    async fn create_surface(
        &self,
        key: &str,
        title: Option<&str>,
        surf_type: Option<&str>,
    ) -> Result<SurfaceInfo, CmuxError> {
        self.inner.create_surface(key, title, surf_type).await
    }
    async fn close_surface(&self, surf_id: &str, key: Option<&str>) -> Result<bool, CmuxError> {
        self.inner.close_surface(surf_id, key).await
    }
    async fn send_input(&self, surf_id: &str, text: &str) -> Result<(), CmuxError> {
        self.inner.send_input(surf_id, text).await
    }
    async fn handle_scroll(
        &self,
        surf_id: &str,
        delta: f64,
        col: usize,
        row: usize,
    ) -> Result<RenderGridFrame, CmuxError> {
        self.inner.handle_scroll(surf_id, delta, col, row).await
    }
    async fn get_snapshot(
        &self,
        surf_id: &str,
        max_sb: usize,
    ) -> Result<RenderGridFrame, CmuxError> {
        let t_start = Instant::now();
        {
            self.timeline
                .lock()
                .push(("start", surf_id.to_string(), max_sb, t_start));
        }

        if surf_id == "surf-slow" {
            sleep(Duration::from_millis(150)).await;
        } else if surf_id == "surf-fast-diff" {
            sleep(Duration::from_millis(10)).await;
        }

        let res = self.inner.get_snapshot("surf-main-1", max_sb).await;

        let t_end = Instant::now();
        {
            self.timeline
                .lock()
                .push(("end", surf_id.to_string(), max_sb, t_end));
        }

        res
    }
    async fn read_screen_fallback(&self, surf_id: &str) -> Result<RenderGridFrame, CmuxError> {
        self.inner.read_screen_fallback(surf_id).await
    }
    async fn list_notifications(&self) -> Result<Vec<String>, CmuxError> {
        self.inner.list_notifications().await
    }
    async fn spawn_events_stream(&self) -> Result<CmuxEventStream, CmuxError> {
        self.inner.spawn_events_stream().await
    }
}

#[tokio::test]
async fn test_per_surface_serialization_and_multi_surface_concurrency() {
    let timeline = Arc::new(Mutex::new(Vec::new()));
    let backend = Arc::new(ConcurrencyTestBackend::new(timeline.clone()));
    let gateway = Arc::new(CmuxGateway::new("127.0.0.1", 0, "token", backend).unwrap());

    let t0 = Instant::now();

    let gw1 = gateway.clone();
    let gw2 = gateway.clone();
    let gw3 = gateway.clone();

    let task_replay_a =
        tokio::spawn(async move { gw1.get_surface_snapshot("surf-slow", 500).await });
    let task_poll_a = tokio::spawn(async move { gw2.get_surface_snapshot("surf-slow", 0).await });
    let task_diff_b =
        tokio::spawn(async move { gw3.get_surface_snapshot("surf-fast-diff", 0).await });

    let res_diff_b = task_diff_b.await.unwrap();
    let t_diff_done = Instant::now();

    let res_replay_a = task_replay_a.await.unwrap();
    let res_poll_a = task_poll_a.await.unwrap();

    assert!(res_diff_b.is_ok());
    assert!(res_replay_a.is_ok());
    assert!(res_poll_a.is_ok());

    // 1. Different surface (surf-fast-diff) finishes concurrently well before surf-slow
    let diff_duration = t_diff_done.duration_since(t0);
    assert!(
        diff_duration < Duration::from_millis(80),
        "Different surface took {:?}",
        diff_duration
    );

    // 2. Same-surface calls on surf-slow are serialized in order (500 then 0)
    let events = timeline.lock().clone();
    let slow_end_sb500 = events
        .iter()
        .find(|(evt, sid, sb, _)| *evt == "end" && sid == "surf-slow" && *sb == 500)
        .map(|(_, _, _, t)| *t)
        .expect("slow_end_sb500");

    let slow_start_sb0 = events
        .iter()
        .find(|(evt, sid, sb, _)| *evt == "start" && sid == "surf-slow" && *sb == 0)
        .map(|(_, _, _, t)| *t)
        .expect("slow_start_sb0");

    assert!(
        slow_start_sb0 >= slow_end_sb500,
        "Second call on same surface must not start before first call ends: start={:?}, end={:?}",
        slow_start_sb0,
        slow_end_sb500
    );
}

use cmux_pocket_cmux::{CmuxBackend, MockCmuxBackend};
use cmux_pocket_gateway::{SurfaceLockManager, ViewportLeaseManager};
use std::sync::Arc;

fn manager() -> (Arc<MockCmuxBackend>, ViewportLeaseManager) {
    let backend = Arc::new(MockCmuxBackend::new());
    let leases = ViewportLeaseManager::new(backend.clone(), SurfaceLockManager::new());
    (backend, leases)
}

#[tokio::test]
async fn report_changes_the_host_grid_and_clear_restores_it() {
    let (backend, leases) = manager();

    let outcome = leases
        .report("session-a", "phone-a", "surf-main-1", 44, 30, 10)
        .await
        .unwrap();
    assert!(outcome.accepted);
    assert!(!outcome.pending);
    assert_eq!(outcome.effective_columns, Some(44));
    assert_eq!(outcome.effective_rows, Some(30));

    let snapshot = backend.get_snapshot("surf-main-1", 0).await.unwrap();
    assert_eq!((snapshot.columns, snapshot.rows), (44, 30));

    let restored = leases
        .clear("session-a", "phone-a", "surf-main-1", 11)
        .await
        .unwrap();
    assert_eq!(restored, Some((80, 24)));
    assert!(backend.viewport_reports_for("surf-main-1").is_empty());
}

#[tokio::test]
async fn smallest_report_wins_and_only_the_owner_is_released() {
    let (backend, leases) = manager();

    leases
        .report("session-a", "phone-a", "surf-main-1", 44, 30, 10)
        .await
        .unwrap();
    leases
        .report("session-b", "phone-b", "surf-main-1", 100, 40, 10)
        .await
        .unwrap();
    let snapshot = backend.get_snapshot("surf-main-1", 0).await.unwrap();
    assert_eq!((snapshot.columns, snapshot.rows), (44, 30));

    leases.release_session("session-b").await;
    let snapshot = backend.get_snapshot("surf-main-1", 0).await.unwrap();
    assert_eq!((snapshot.columns, snapshot.rows), (44, 30));
    assert_eq!(backend.viewport_reports_for("surf-main-1").len(), 1);

    leases.release_session("session-a").await;
    let snapshot = backend.get_snapshot("surf-main-1", 0).await.unwrap();
    assert_eq!((snapshot.columns, snapshot.rows), (80, 24));
}

#[tokio::test]
async fn stale_generation_cannot_override_a_newer_report() {
    let (_backend, leases) = manager();

    leases
        .report("session-a", "phone-a", "surf-main-1", 44, 30, 20)
        .await
        .unwrap();
    let stale = leases
        .report("session-a", "phone-a", "surf-main-1", 120, 40, 19)
        .await
        .unwrap();

    assert!(!stale.accepted);
    assert_eq!(stale.generation, 19);
}

#[tokio::test]
async fn requested_grid_is_clamped_to_the_host_range() {
    let (backend, leases) = manager();

    let outcome = leases
        .report("session-a", "phone-a", "surf-main-1", 2, 500, 1)
        .await
        .unwrap();

    assert!(outcome.accepted);
    assert_eq!(outcome.requested_columns, 20);
    assert_eq!(outcome.requested_rows, 120);
    let snapshot = backend.get_snapshot("surf-main-1", 0).await.unwrap();
    assert_eq!((snapshot.columns, snapshot.rows), (20, 120));
}

#[tokio::test]
async fn an_active_lease_keeps_renewing_its_report() {
    let (backend, leases) = manager();

    leases
        .report("session-a", "phone-a", "surf-main-1", 44, 30, 10)
        .await
        .unwrap();
    let after_report = backend.viewport_report_calls();
    assert!(after_report >= 1);

    // Renewal cadence is well below the host's five-second report TTL.
    tokio::time::sleep(std::time::Duration::from_millis(3400)).await;
    assert!(backend.viewport_report_calls() > after_report);

    leases.release_session("session-a").await;
    let after_release = backend.viewport_report_calls();
    tokio::time::sleep(std::time::Duration::from_millis(1800)).await;
    assert_eq!(backend.viewport_report_calls(), after_release);
}

use cmux_pocket_cmux::LiveCmuxBackend;
use std::path::Path;
use std::time::Duration;

#[test]
fn test_default_live_backend_configuration() {
    let backend = LiveCmuxBackend::new();
    assert_eq!(backend.cmux_path(), Path::new("cmux"));
}

#[test]
fn test_custom_path_and_timeout_construction() {
    let custom_path = "/usr/local/bin/cmux-custom";
    let custom_timeout = Duration::from_secs(10);

    let backend = LiveCmuxBackend::with_path(custom_path);
    assert_eq!(backend.cmux_path(), Path::new(custom_path));

    let backend_with_timeout = LiveCmuxBackend::with_path_and_timeout(custom_path, custom_timeout);
    assert_eq!(backend_with_timeout.cmux_path(), Path::new(custom_path));
}

#[test]
fn test_monotonic_state_sequences_per_surface() {
    let backend = LiveCmuxBackend::new();

    let seq1_s1 = backend.next_state_seq("surface:1");
    let seq2_s1 = backend.next_state_seq("surface:1");
    let seq3_s1 = backend.next_state_seq("surface:1");

    assert_eq!(seq1_s1, 1);
    assert_eq!(seq2_s1, 2);
    assert_eq!(seq3_s1, 3);

    // Independent surface begins at 1
    let seq1_s2 = backend.next_state_seq("surface:2");
    assert_eq!(seq1_s2, 1);

    let seq4_s1 = backend.next_state_seq("surface:1");
    assert_eq!(seq4_s1, 4);
}

#[test]
fn test_render_epoch_stability_and_uniqueness() {
    let backend = LiveCmuxBackend::new();

    let epoch_s1_a = backend.get_or_create_render_epoch("surface:1");
    let epoch_s1_b = backend.get_or_create_render_epoch("surface:1");
    assert_eq!(epoch_s1_a, epoch_s1_b);

    let epoch_s2 = backend.get_or_create_render_epoch("surface:2");
    assert_ne!(epoch_s1_a, epoch_s2);
}

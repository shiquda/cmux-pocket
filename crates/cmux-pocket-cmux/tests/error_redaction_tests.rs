use cmux_pocket_cmux::CmuxError;
use std::time::Duration;

#[test]
fn test_nonzero_exit_display_and_redaction() {
    let err = CmuxError::non_zero_exit("send", Some(127));
    let msg = err.to_string();

    assert_eq!(msg, "cmux send failed with exit code 127");
    assert!(!msg.contains("secret"));
    assert!(!msg.contains("surface:"));
}

#[test]
fn test_nonzero_exit_unknown_code() {
    let err = CmuxError::non_zero_exit("new-workspace", None);
    let msg = err.to_string();

    assert_eq!(msg, "cmux new-workspace failed with exit code unknown");
}

#[test]
fn test_timeout_error_classification_and_display() {
    let err = CmuxError::timeout("ping", Duration::from_secs(2));
    let msg = err.to_string();

    assert_eq!(msg, "cmux ping timed out after 2s");
    assert!(err.is_timeout());
    assert!(!err.is_unavailable());
}

#[test]
fn test_unavailable_error_classification_and_display() {
    let err = CmuxError::unavailable("socket not found");
    let msg = err.to_string();

    assert_eq!(msg, "cmux is unavailable: socket not found");
    assert!(err.is_unavailable());
    assert!(!err.is_timeout());
}

#[test]
fn test_parse_error_display() {
    let err = CmuxError::parse_error("unexpected EOF");
    let msg = err.to_string();

    assert_eq!(msg, "cmux output parse error: unexpected EOF");
}

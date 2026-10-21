use cmux_pocket_macos::{CmuxLauncher, OPEN_PROGRAM};
use std::path::Path;
use std::time::Duration;

#[test]
fn wake_command_uses_launch_services_and_background_flag() {
    let launcher = CmuxLauncher::new();
    let spec = launcher.command_spec();

    assert_eq!(
        spec.to_argv(),
        vec![
            OPEN_PROGRAM.to_string(),
            "-g".to_string(),
            "-b".to_string(),
            "com.cmuxterm.app".to_string(),
        ]
    );
    assert_eq!(spec.program, "/usr/bin/open");
    assert!(spec.args.iter().all(|arg| !arg.contains('\'')));
}

#[test]
fn repeated_wake_requests_inside_the_debounce_window_are_absorbed() {
    let launcher = CmuxLauncher::with_program("/usr/bin/open", Duration::from_secs(10));

    assert!(launcher.try_begin_wake_at(1_000));
    assert!(!launcher.try_begin_wake_at(1_001));
    assert!(!launcher.try_begin_wake_at(10_999));
    assert!(launcher.try_begin_wake_at(11_000));
}

#[test]
fn executable_resolution_checks_direct_paths() {
    assert_eq!(
        cmux_pocket_macos::resolve_executable(Path::new("/bin/sh")),
        Some(Path::new("/bin/sh").to_path_buf())
    );
    assert_eq!(
        cmux_pocket_macos::resolve_executable(Path::new("/definitely/not/here")),
        None
    );
}

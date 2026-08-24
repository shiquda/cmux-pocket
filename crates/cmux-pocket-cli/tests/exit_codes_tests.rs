use cmux_pocket_cli::error::{CliError, CliExitCode};
use cmux_pocket_cmux::CmuxError;
use cmux_pocket_gateway::GatewayError;
use cmux_pocket_macos::{LoopbackError, MacOsError};
use std::path::PathBuf;

#[test]
fn test_exit_code_values() {
    assert_eq!(CliExitCode::Success.as_i32(), 0);
    assert_eq!(CliExitCode::InvalidUsage.as_i32(), 2);
    assert_eq!(CliExitCode::ConfigOrTokenError.as_i32(), 3);
    assert_eq!(CliExitCode::DependencyUnavailable.as_i32(), 4);
    assert_eq!(CliExitCode::RuntimeFailure.as_i32(), 5);
}

#[test]
fn test_error_exit_code_mappings() {
    // 2: InvalidUsage
    let err = CliError::InvalidUsage("bad flag".to_string());
    assert_eq!(err.exit_code(), CliExitCode::InvalidUsage);

    let err = CliError::Loopback(LoopbackError::NonLoopbackHost("0.0.0.0".to_string()));
    assert_eq!(err.exit_code(), CliExitCode::InvalidUsage);

    let err = CliError::MacOs(MacOsError::Loopback(LoopbackError::WildcardBindForbidden(
        "0.0.0.0".to_string(),
    )));
    assert_eq!(err.exit_code(), CliExitCode::InvalidUsage);

    // 3: ConfigOrTokenError
    let err = CliError::ConfigOrToken("missing token".to_string());
    assert_eq!(err.exit_code(), CliExitCode::ConfigOrTokenError);

    let err = CliError::MacOs(MacOsError::TokenNotFound(PathBuf::from("/path/token")));
    assert_eq!(err.exit_code(), CliExitCode::ConfigOrTokenError);

    let err = CliError::MacOs(MacOsError::TokenEmpty(PathBuf::from("/path/token")));
    assert_eq!(err.exit_code(), CliExitCode::ConfigOrTokenError);

    let err = CliError::MacOs(MacOsError::InsecurePermissions {
        path: PathBuf::from("/path/token"),
        mode: 0o644,
        expected_mask: 0o077,
    });
    assert_eq!(err.exit_code(), CliExitCode::ConfigOrTokenError);

    let err = CliError::MacOs(MacOsError::CellarPathForbidden {
        name: "token".to_string(),
        path: PathBuf::from("/opt/homebrew/Cellar/cmux-pocket/token"),
    });
    assert_eq!(err.exit_code(), CliExitCode::ConfigOrTokenError);

    // 4: DependencyUnavailable
    let err = CliError::DependencyUnavailable("cmux daemon unreachable".to_string());
    assert_eq!(err.exit_code(), CliExitCode::DependencyUnavailable);

    let err = CliError::Cmux(CmuxError::unavailable("not running"));
    assert_eq!(err.exit_code(), CliExitCode::DependencyUnavailable);

    let err = CliError::Cmux(CmuxError::timeout(
        "ping",
        std::time::Duration::from_secs(3),
    ));
    assert_eq!(err.exit_code(), CliExitCode::DependencyUnavailable);

    let err = CliError::Gateway(GatewayError::BackendUnavailable("cmux down".to_string()));
    assert_eq!(err.exit_code(), CliExitCode::DependencyUnavailable);

    let err = CliError::Io(std::io::Error::new(
        std::io::ErrorKind::ConnectionRefused,
        "connection refused",
    ));
    assert_eq!(err.exit_code(), CliExitCode::DependencyUnavailable);

    let err = CliError::Io(std::io::Error::new(
        std::io::ErrorKind::AddrInUse,
        "address in use",
    ));
    assert_eq!(err.exit_code(), CliExitCode::DependencyUnavailable);

    // 5: RuntimeFailure
    let err = CliError::RuntimeFailure("unexpected panic".to_string());
    assert_eq!(err.exit_code(), CliExitCode::RuntimeFailure);
}

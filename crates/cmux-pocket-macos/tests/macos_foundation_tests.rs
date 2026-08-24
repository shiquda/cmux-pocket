//! Comprehensive package-local tests for cmux-pocket-macos.

use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::{Path, PathBuf};
use tempfile::tempdir;

use cmux_pocket_macos::config::{
    ensure_config, load_config, save_config, DEFAULT_GATEWAY_HOST, DEFAULT_GATEWAY_PORT,
};
use cmux_pocket_macos::launchd::{
    generate_launchd_plist, launchctl_bootout_plist_cmd, launchctl_bootout_service_cmd,
    launchctl_bootstrap_cmd, launchctl_disable_service_cmd, launchctl_enable_service_cmd,
    launchctl_kickstart_cmd, launchctl_print_service_cmd, save_launchd_plist, LaunchdPlistConfig,
    LaunchdTarget,
};
use cmux_pocket_macos::loopback::{
    is_loopback_host, validate_loopback_host, validate_loopback_url,
};
use cmux_pocket_macos::paths::{
    is_cellar_path, resolve_stable_executable_path, validate_outside_cellar, PocketPaths,
};
use cmux_pocket_macos::permissions::{set_user_only_file_permissions, USER_ONLY_FILE_MODE};
use cmux_pocket_macos::token::{
    ensure_token, load_token, rotate_token, RedactedToken, TokenFingerprint, TOKEN_BYTE_LEN,
};

#[test]
fn test_loopback_allow_and_deny_matrix() {
    // Allowed loopback hosts / IPs
    let allowed = [
        "127.0.0.1",
        "127.0.0.2",
        "127.1.2.3",
        "127.255.255.254",
        "::1",
        "[::1]",
        "::ffff:127.0.0.1",
        "localhost",
        "LOCALHOST",
        "LocalHost",
    ];

    for host in &allowed {
        assert!(
            is_loopback_host(host),
            "Expected '{host}' to be recognized as loopback"
        );
        assert!(
            validate_loopback_host(host).is_ok(),
            "Expected '{host}' to validate as loopback host"
        );
    }

    // Denied non-loopback hosts / IPs
    let denied = [
        "0.0.0.0",
        "::",
        "192.168.1.1",
        "192.168.0.100",
        "10.0.0.1",
        "172.16.0.1",
        "8.8.8.8",
        "1.1.1.1",
        "example.com",
        "cmux.local",
        "router.lan",
        "",
        "   ",
    ];

    for host in &denied {
        assert!(
            !is_loopback_host(host),
            "Expected '{host}' to be denied as non-loopback"
        );
        assert!(
            validate_loopback_host(host).is_err(),
            "Expected '{host}' validation to fail"
        );
    }
}

#[test]
fn test_loopback_url_validation() {
    assert!(validate_loopback_url("ws://127.0.0.1:8088").is_ok());
    assert!(validate_loopback_url("ws://127.0.0.1:8088/ws").is_ok());
    assert!(validate_loopback_url("ws://localhost:8088/ws?token=test").is_ok());
    assert!(validate_loopback_url("http://127.0.0.1:8088/status").is_ok());
    assert!(validate_loopback_url("ws://[::1]:8088/ws").is_ok());

    assert!(validate_loopback_url("ws://0.0.0.0:8088").is_err());
    assert!(validate_loopback_url("ws://192.168.1.50:8088").is_err());
    assert!(validate_loopback_url("ws://my-mac.local:8088").is_err());
    assert!(validate_loopback_url("https://external.example.com").is_err());
}

#[test]
fn test_path_resolution_and_cellar_detection() {
    let home = Path::new("/Users/developer");
    let paths = PocketPaths::from_home_dir(home);

    assert_eq!(
        paths.config_file,
        PathBuf::from("/Users/developer/Library/Application Support/cmux-pocket/config.toml")
    );
    assert_eq!(
        paths.token_file,
        PathBuf::from("/Users/developer/Library/Application Support/cmux-pocket/gateway-token")
    );
    assert_eq!(
        paths.plist_file,
        PathBuf::from("/Users/developer/Library/LaunchAgents/com.cmuxpocket.gateway.plist")
    );

    // Cellar paths must be detected and rejected
    assert!(is_cellar_path(Path::new(
        "/opt/homebrew/Cellar/cmux-pocket/0.1.0/config.toml"
    )));
    assert!(is_cellar_path(Path::new(
        "/usr/local/Cellar/cmux-pocket/0.1.0/bin/cmux-pocket"
    )));
    assert!(!is_cellar_path(&paths.config_file));
    assert!(!is_cellar_path(&paths.token_file));

    assert!(validate_outside_cellar(&paths.config_file, "config").is_ok());
    assert!(validate_outside_cellar(&paths.token_file, "token").is_ok());
    assert!(
        validate_outside_cellar(Path::new("/opt/homebrew/Cellar/pkg/1.0/token"), "token").is_err()
    );
}

#[test]
fn test_stable_executable_path_resolution() {
    // Apple Silicon Cellar -> opt
    let arm_cellar = Path::new("/opt/homebrew/Cellar/cmux-pocket/1.2.0/bin/cmux-pocket");
    assert_eq!(
        resolve_stable_executable_path(arm_cellar),
        PathBuf::from("/opt/homebrew/opt/cmux-pocket/bin/cmux-pocket")
    );

    // Intel Cellar -> opt
    let intel_cellar = Path::new("/usr/local/Cellar/cmux-pocket/1.2.0/bin/cmux-pocket");
    assert_eq!(
        resolve_stable_executable_path(intel_cellar),
        PathBuf::from("/usr/local/opt/cmux-pocket/bin/cmux-pocket")
    );

    // Normal path preserved
    let regular = Path::new("/usr/local/bin/cmux-pocket");
    assert_eq!(
        resolve_stable_executable_path(regular),
        PathBuf::from("/usr/local/opt/cmux-pocket/bin/cmux-pocket")
    );
}

#[test]
fn test_token_permissions_and_atomic_replacement() {
    let tmp = tempdir().unwrap();
    let token_path = tmp.path().join("secure-token");

    // 1. Initial write
    let (token1, created) = ensure_token(&token_path).unwrap();
    assert!(created);
    assert_eq!(token1.len(), TOKEN_BYTE_LEN * 2);

    // Verify mode is 0o600
    let metadata = fs::metadata(&token_path).unwrap();
    let mode = metadata.permissions().mode() & 0o777;
    assert_eq!(mode, USER_ONLY_FILE_MODE);

    // Verify loading works
    let loaded = load_token(&token_path).unwrap();
    assert_eq!(loaded, token1);

    // 2. Insecure permission detection: change mode to 0o644
    fs::set_permissions(&token_path, fs::Permissions::from_mode(0o644)).unwrap();
    assert!(load_token(&token_path).is_err());

    // Fix permissions
    set_user_only_file_permissions(&token_path).unwrap();
    assert!(load_token(&token_path).is_ok());

    // 3. Atomic rotation replaces token
    let token2 = rotate_token(&token_path).unwrap();
    assert_ne!(token1, token2);
    let loaded2 = load_token(&token_path).unwrap();
    assert_eq!(loaded2, token2);

    // Ensure permissions remain 0o600 after rotation
    let metadata2 = fs::metadata(&token_path).unwrap();
    assert_eq!(metadata2.permissions().mode() & 0o777, USER_ONLY_FILE_MODE);
}

#[test]
fn test_token_fingerprint_and_redaction() {
    let token = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";
    let fp = TokenFingerprint::compute(token);

    assert_eq!(fp.char_length, 64);
    assert_eq!(fp.sha256_full.len(), 64);
    assert_eq!(fp.sha256_short.len(), 12);
    assert!(fp.display_summary().contains(&fp.sha256_short));

    let redacted = RedactedToken::new(token.to_string());
    assert_eq!(format!("{redacted}"), "[REDACTED]");
    assert!(!format!("{redacted:?}").contains(token));
    assert_eq!(redacted.expose_secret(), token);
}

#[test]
fn test_launchd_plist_generation_and_token_absence() {
    let tmp = tempdir().unwrap();
    let log_dir = tmp.path().join("Logs");
    let config_path = tmp.path().join("config.toml");
    let exe_path = PathBuf::from("/opt/homebrew/opt/cmux-pocket/bin/cmux-pocket");

    let plist_config = LaunchdPlistConfig::new(exe_path, config_path, &log_dir);
    let plist_xml = generate_launchd_plist(&plist_config).unwrap();

    // Required fields check
    assert!(plist_xml.contains("<key>Label</key>"));
    assert!(plist_xml.contains("<string>com.cmuxpocket.gateway</string>"));
    assert!(plist_xml.contains("<key>ProgramArguments</key>"));
    assert!(plist_xml.contains("<string>/opt/homebrew/opt/cmux-pocket/bin/cmux-pocket</string>"));
    assert!(plist_xml.contains("<string>gateway</string>"));
    assert!(plist_xml.contains("<string>run</string>"));
    assert!(plist_xml.contains("<string>--config</string>"));
    assert!(plist_xml.contains("<key>RunAtLoad</key>"));
    assert!(plist_xml.contains("<true/>"));
    assert!(plist_xml.contains("<key>KeepAlive</key>"));
    assert!(plist_xml.contains("<key>StandardOutPath</key>"));
    assert!(plist_xml.contains("<key>StandardErrorPath</key>"));

    // Absolute guarantee: Token or sensitive credentials MUST NOT appear in the plist
    assert!(!plist_xml.contains("token"));
    assert!(!plist_xml.contains("gateway-token"));
    assert!(!plist_xml.contains("auth_token"));
    assert!(!plist_xml.contains("secret"));

    // Test saving plist
    let plist_dest = tmp.path().join("com.cmuxpocket.gateway.plist");
    save_launchd_plist(&plist_dest, &plist_xml).unwrap();
    assert!(plist_dest.exists());
}

#[test]
fn test_launchctl_command_specifications() {
    let target = LaunchdTarget::Gui(501);
    let plist_path = Path::new("/Users/test/Library/LaunchAgents/com.cmuxpocket.gateway.plist");

    let bootstrap = launchctl_bootstrap_cmd(&target, plist_path);
    assert_eq!(
        bootstrap.to_argv(),
        vec![
            "launchctl",
            "bootstrap",
            "gui/501",
            "/Users/test/Library/LaunchAgents/com.cmuxpocket.gateway.plist"
        ]
    );

    let bootout_svc = launchctl_bootout_service_cmd(&target, "com.cmuxpocket.gateway");
    assert_eq!(
        bootout_svc.to_argv(),
        vec!["launchctl", "bootout", "gui/501/com.cmuxpocket.gateway"]
    );

    let bootout_pl = launchctl_bootout_plist_cmd(&target, plist_path);
    assert_eq!(
        bootout_pl.to_argv(),
        vec![
            "launchctl",
            "bootout",
            "gui/501",
            "/Users/test/Library/LaunchAgents/com.cmuxpocket.gateway.plist"
        ]
    );

    let kickstart_kill = launchctl_kickstart_cmd(&target, "com.cmuxpocket.gateway", true);
    assert_eq!(
        kickstart_kill.to_argv(),
        vec![
            "launchctl",
            "kickstart",
            "-k",
            "gui/501/com.cmuxpocket.gateway"
        ]
    );

    let kickstart_nokill = launchctl_kickstart_cmd(&target, "com.cmuxpocket.gateway", false);
    assert_eq!(
        kickstart_nokill.to_argv(),
        vec!["launchctl", "kickstart", "gui/501/com.cmuxpocket.gateway"]
    );

    let print_cmd = launchctl_print_service_cmd(&target, "com.cmuxpocket.gateway");
    assert_eq!(
        print_cmd.to_argv(),
        vec!["launchctl", "print", "gui/501/com.cmuxpocket.gateway"]
    );

    let enable_cmd = launchctl_enable_service_cmd(&target, "com.cmuxpocket.gateway");
    assert_eq!(
        enable_cmd.to_argv(),
        vec!["launchctl", "enable", "gui/501/com.cmuxpocket.gateway"]
    );

    let disable_cmd = launchctl_disable_service_cmd(&target, "com.cmuxpocket.gateway");
    assert_eq!(
        disable_cmd.to_argv(),
        vec!["launchctl", "disable", "gui/501/com.cmuxpocket.gateway"]
    );
}

#[test]
fn test_config_atomic_write_and_preservation() {
    let tmp = tempdir().unwrap();
    let config_file = tmp.path().join("config.toml");

    let (cfg1, created) = ensure_config(&config_file).unwrap();
    assert!(created);
    assert_eq!(cfg1.host, DEFAULT_GATEWAY_HOST);
    assert_eq!(cfg1.port, DEFAULT_GATEWAY_PORT);

    let mut modified = cfg1;
    modified.port = 8090;
    save_config(&config_file, &modified).unwrap();

    let reloaded = load_config(&config_file).unwrap();
    assert_eq!(reloaded.port, 8090);
    assert_eq!(reloaded.host, DEFAULT_GATEWAY_HOST);
}

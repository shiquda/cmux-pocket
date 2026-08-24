use cmux_pocket_cli::cli::SetupArgs;
use cmux_pocket_cli::commands::handle_setup;
use cmux_pocket_macos::{load_config, load_token, PocketPaths};
use std::fs;
use tempfile::TempDir;

#[tokio::test]
async fn test_setup_first_run_no_start() {
    let temp = TempDir::new().unwrap();
    let paths = PocketPaths::from_home_dir(temp.path());

    let args = SetupArgs {
        port: Some(8089),
        no_start: true,
    };

    let res = handle_setup(&paths, &args, false).await;
    assert!(
        res.is_ok(),
        "Setup with --no-start should succeed: {:?}",
        res
    );

    // 1. Verify config file created with custom port
    assert!(paths.config_file.exists());
    let cfg = load_config(&paths.config_file).unwrap();
    assert_eq!(cfg.port, 8089);
    assert_eq!(cfg.host, "127.0.0.1");

    // 2. Verify token file created with 0o600 permissions
    assert!(paths.token_file.exists());
    let token = load_token(&paths.token_file).unwrap();
    assert_eq!(token.trim().len(), 64);

    // 3. Verify launchd plist file created with RunAtLoad false
    assert!(paths.plist_file.exists());
    let plist_xml = fs::read_to_string(&paths.plist_file).unwrap();
    assert!(plist_xml.contains("com.cmuxpocket.gateway"));
    assert!(
        plist_xml.contains("<key>RunAtLoad</key>\n\t<false/>") || plist_xml.contains("<false/>")
    );
    assert!(
        !plist_xml.contains(&token),
        "Secret token must never be embedded in plist!"
    );
}

#[tokio::test]
async fn test_setup_idempotency_preserves_token_and_config() {
    let temp = TempDir::new().unwrap();
    let paths = PocketPaths::from_home_dir(temp.path());

    let args1 = SetupArgs {
        port: Some(8090),
        no_start: true,
    };

    // First setup run
    handle_setup(&paths, &args1, false).await.unwrap();
    let initial_token = load_token(&paths.token_file).unwrap();

    // Second setup run (without port override)
    let args2 = SetupArgs {
        port: None,
        no_start: true,
    };
    handle_setup(&paths, &args2, false).await.unwrap();

    // Verify token was NOT rotated
    let preserved_token = load_token(&paths.token_file).unwrap();
    assert_eq!(
        initial_token, preserved_token,
        "Idempotent setup must preserve existing authentication token"
    );

    // Verify port was preserved
    let cfg = load_config(&paths.config_file).unwrap();
    assert_eq!(
        cfg.port, 8090,
        "Idempotent setup must preserve existing config values"
    );
}

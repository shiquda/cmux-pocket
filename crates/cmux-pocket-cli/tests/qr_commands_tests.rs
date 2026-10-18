use cmux_pocket_cli::cli::QrArgs;
use cmux_pocket_cli::commands::qr::handle_qr;
use cmux_pocket_cli::uri::parse_import_uri;
use cmux_pocket_macos::{
    create_dir_user_only, load_config, save_config, save_token, GatewayConfig, PocketPaths,
};
use tempfile::TempDir;

const TEST_TOKEN: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

fn setup_test_env(temp: &TempDir, endpoint: Option<&str>) -> PocketPaths {
    let paths = PocketPaths::from_home_dir(temp.path());
    create_dir_user_only(&paths.config_dir).unwrap();

    let config = GatewayConfig {
        host: "127.0.0.1".to_string(),
        port: 8088,
        endpoint: endpoint.map(|s| s.to_string()),
        ..GatewayConfig::default()
    };
    save_config(&paths.config_file, &config).unwrap();
    save_token(&paths.token_file, TEST_TOKEN).unwrap();
    paths
}

#[tokio::test]
async fn qr_with_explicit_cli_endpoint() {
    let temp = TempDir::new().unwrap();
    let paths = setup_test_env(&temp, None);

    let args = QrArgs {
        endpoint: Some("wss://custom.domain.net:8443".to_string()),
        url_only: true,
        name: Some("My Custom Station".to_string()),
        invert: false,
    };

    let result = handle_qr(&paths, &args, false).await;
    assert!(result.is_ok());

    // Config must not be modified by one-time CLI argument
    let loaded = load_config(&paths.config_file).unwrap();
    assert_eq!(loaded.endpoint, None);
}

#[tokio::test]
async fn qr_precedence_cli_overrides_saved_config() {
    let temp = TempDir::new().unwrap();
    let paths = setup_test_env(&temp, Some("wss://saved.domain.net"));

    let args = QrArgs {
        endpoint: Some("wss://override.domain.net".to_string()),
        url_only: true,
        name: None,
        invert: false,
    };

    let result = handle_qr(&paths, &args, false).await;
    assert!(result.is_ok());

    // Saved config retains original value
    let loaded = load_config(&paths.config_file).unwrap();
    assert_eq!(loaded.endpoint.as_deref(), Some("wss://saved.domain.net"));
}

#[tokio::test]
async fn qr_uses_saved_config_when_cli_arg_omitted() {
    let temp = TempDir::new().unwrap();
    let paths = setup_test_env(&temp, Some("wss://saved.domain.net"));

    let args = QrArgs {
        endpoint: None,
        url_only: false,
        name: None,
        invert: false,
    };

    let result = handle_qr(&paths, &args, true).await;
    assert!(result.is_ok());
}

#[tokio::test]
async fn qr_rejects_non_loopback_cleartext() {
    let temp = TempDir::new().unwrap();
    let paths = setup_test_env(&temp, None);

    let args = QrArgs {
        endpoint: Some("ws://remote.insecure.com:8088".to_string()),
        url_only: false,
        name: None,
        invert: false,
    };

    let err = handle_qr(&paths, &args, false).await.unwrap_err();
    let err_msg = err.to_string();
    assert!(
        err_msg.contains("Cleartext WebSocket is only allowed for loopback"),
        "Expected cleartext error, got: {}",
        err_msg
    );
}

#[tokio::test]
async fn qr_loopback_explicit_allowed_with_notice() {
    let temp = TempDir::new().unwrap();
    let paths = setup_test_env(&temp, None);

    let args = QrArgs {
        endpoint: Some("ws://127.0.0.1:8088".to_string()),
        url_only: false,
        name: None,
        invert: false,
    };

    let result = handle_qr(&paths, &args, false).await;
    assert!(result.is_ok());
}

#[tokio::test]
async fn qr_generated_uri_roundtrips_cleanly() {
    let temp = TempDir::new().unwrap();
    let _paths = setup_test_env(&temp, None);

    let ep = cmux_pocket_macos::parse_and_validate_endpoint("wss://my-host.ts.net").unwrap();
    let uri = cmux_pocket_cli::uri::build_import_uri(&ep, TEST_TOKEN, Some("Pocket Station"));

    let parsed = parse_import_uri(&uri).unwrap();
    assert_eq!(parsed.version, "1");
    assert_eq!(parsed.host, "my-host.ts.net");
    assert_eq!(parsed.port, 443);
    assert!(parsed.tls);
    assert_eq!(parsed.token, TEST_TOKEN);
    assert_eq!(parsed.name, "Pocket Station");
    assert!(!parsed.is_loopback);
    assert_eq!(parsed.masked_token(), "0123••••cdef");
}

#[test]
fn qr_ansi_rendering_standard_and_inverted() {
    let uri = "cmux-pocket://import?v=1&host=test.ts.net&port=443&token=abc";
    let standard = cmux_pocket_cli::commands::qr::generate_ansi_qr(uri, false).unwrap();
    let inverted = cmux_pocket_cli::commands::qr::generate_ansi_qr(uri, true).unwrap();

    assert!(!standard.is_empty());
    assert!(!inverted.is_empty());
    assert_ne!(standard, inverted);
}

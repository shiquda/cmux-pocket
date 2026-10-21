use cmux_pocket_cli::cli::EndpointSubcommand;
use cmux_pocket_cli::commands::endpoint::handle_endpoint;
use cmux_pocket_macos::{
    create_dir_user_only, load_config, save_config, save_token, GatewayConfig, PocketPaths,
};
use tempfile::TempDir;

const TEST_TOKEN: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

fn test_paths(temp: &TempDir) -> PocketPaths {
    let paths = PocketPaths::from_home_dir(temp.path());
    create_dir_user_only(&paths.config_dir).unwrap();
    save_token(&paths.token_file, TEST_TOKEN).unwrap();
    paths
}

#[tokio::test]
async fn endpoint_add_list_and_remove_roundtrip() {
    let temp = TempDir::new().unwrap();
    let paths = test_paths(&temp);
    save_config(&paths.config_file, &GatewayConfig::default()).unwrap();

    handle_endpoint(
        &paths,
        &EndpointSubcommand::Add {
            url: "https://gateway.lan.example".to_string(),
            name: "LAN".to_string(),
        },
        true,
    )
    .await
    .unwrap();

    let config = load_config(&paths.config_file).unwrap();
    assert_eq!(config.endpoints.len(), 1);
    assert_eq!(config.endpoints[0].name, "LAN");
    // https:// is normalized to the wss:// form the phone uses.
    assert_eq!(config.endpoints[0].url, "wss://gateway.lan.example");

    let duplicate = handle_endpoint(
        &paths,
        &EndpointSubcommand::Add {
            url: "wss://other.example.com".to_string(),
            name: "lan".to_string(),
        },
        true,
    )
    .await;
    assert!(duplicate.is_err());

    handle_endpoint(
        &paths,
        &EndpointSubcommand::Remove {
            name: "LAN".to_string(),
        },
        true,
    )
    .await
    .unwrap();
    let config = load_config(&paths.config_file).unwrap();
    assert!(config.endpoints.is_empty());

    let missing = handle_endpoint(
        &paths,
        &EndpointSubcommand::Remove {
            name: "LAN".to_string(),
        },
        true,
    )
    .await;
    assert!(missing.is_err());
}

#[tokio::test]
async fn endpoint_add_creates_config_without_touching_the_legacy_default() {
    let temp = TempDir::new().unwrap();
    let paths = test_paths(&temp);
    let config = GatewayConfig {
        endpoint: Some("wss://default.example.com".to_string()),
        ..GatewayConfig::default()
    };
    save_config(&paths.config_file, &config).unwrap();

    handle_endpoint(
        &paths,
        &EndpointSubcommand::Add {
            url: "wss://lan.example.com".to_string(),
            name: "LAN".to_string(),
        },
        true,
    )
    .await
    .unwrap();

    let loaded = load_config(&paths.config_file).unwrap();
    assert_eq!(
        loaded.endpoint.as_deref(),
        Some("wss://default.example.com")
    );
    assert_eq!(loaded.endpoints.len(), 1);
}

#[tokio::test]
async fn endpoint_add_rejects_cleartext_remote_urls() {
    let temp = TempDir::new().unwrap();
    let paths = test_paths(&temp);
    let result = handle_endpoint(
        &paths,
        &EndpointSubcommand::Add {
            url: "ws://public.example.com:8088".to_string(),
            name: "Bad".to_string(),
        },
        true,
    )
    .await;
    assert!(result.is_err());
    assert!(!paths.config_file.exists());
}

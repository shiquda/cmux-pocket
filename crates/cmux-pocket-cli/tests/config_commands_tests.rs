use cmux_pocket_cli::cli::ConfigSubcommand;
use cmux_pocket_cli::commands::handle_config;
use cmux_pocket_macos::{
    create_dir_user_only, load_config, save_config, GatewayConfig, PocketPaths,
};
use tempfile::TempDir;

#[tokio::test]
async fn test_config_get_and_set() {
    let temp = TempDir::new().unwrap();
    let paths = PocketPaths::from_home_dir(temp.path());

    create_dir_user_only(&paths.config_dir).unwrap();

    let initial_cfg = GatewayConfig::default();
    save_config(&paths.config_file, &initial_cfg).unwrap();

    // 1. Get default port
    let get_cmd = ConfigSubcommand::Get {
        key: "port".to_string(),
    };
    handle_config(&paths, &get_cmd, false).await.unwrap();

    // 2. Set custom port
    let set_cmd = ConfigSubcommand::Set {
        key: "port".to_string(),
        value: "9095".to_string(),
    };
    handle_config(&paths, &set_cmd, false).await.unwrap();

    let updated = load_config(&paths.config_file).unwrap();
    assert_eq!(updated.port, 9095);

    // 3. Set custom extra key
    let set_extra = ConfigSubcommand::Set {
        key: "custom_flag".to_string(),
        value: "true".to_string(),
    };
    handle_config(&paths, &set_extra, false).await.unwrap();

    let updated2 = load_config(&paths.config_file).unwrap();
    assert_eq!(
        updated2.extra.get("custom_flag").unwrap(),
        &toml::Value::Boolean(true)
    );
}

#[tokio::test]
async fn test_config_set_loopback_validation() {
    let temp = TempDir::new().unwrap();
    let paths = PocketPaths::from_home_dir(temp.path());
    create_dir_user_only(&paths.config_dir).unwrap();

    let initial_cfg = GatewayConfig::default();
    save_config(&paths.config_file, &initial_cfg).unwrap();

    // Loopback hosts must succeed
    assert!(handle_config(
        &paths,
        &ConfigSubcommand::Set {
            key: "host".to_string(),
            value: "127.0.0.1".to_string(),
        },
        false
    )
    .await
    .is_ok());

    assert!(handle_config(
        &paths,
        &ConfigSubcommand::Set {
            key: "host".to_string(),
            value: "localhost".to_string(),
        },
        false
    )
    .await
    .is_ok());

    assert!(handle_config(
        &paths,
        &ConfigSubcommand::Set {
            key: "host".to_string(),
            value: "::1".to_string(),
        },
        false
    )
    .await
    .is_ok());

    // Non-loopback / wildcard hosts must be strictly rejected
    assert!(handle_config(
        &paths,
        &ConfigSubcommand::Set {
            key: "host".to_string(),
            value: "0.0.0.0".to_string(),
        },
        false
    )
    .await
    .is_err());

    assert!(handle_config(
        &paths,
        &ConfigSubcommand::Set {
            key: "host".to_string(),
            value: "192.168.1.100".to_string(),
        },
        false
    )
    .await
    .is_err());
}

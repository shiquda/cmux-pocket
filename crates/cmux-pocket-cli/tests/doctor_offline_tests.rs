use cmux_pocket_cli::cli::DoctorArgs;
use cmux_pocket_cli::commands::handle_doctor;
use cmux_pocket_macos::{
    create_dir_user_only, save_config, save_token, GatewayConfig, PocketPaths,
};
use std::fs;
use tempfile::TempDir;

#[tokio::test]
async fn test_doctor_offline_unconfigured_passes() {
    let temp = TempDir::new().unwrap();
    let paths = PocketPaths::from_home_dir(temp.path());

    // In a completely clean Homebrew formula test environment, offline doctor must pass
    let args = DoctorArgs {
        offline: true,
        deep: false,
    };

    let res = handle_doctor(&paths, &args, false).await;
    assert!(
        res.is_ok(),
        "Offline doctor must pass on clean unconfigured system"
    );
}

#[tokio::test]
async fn test_doctor_offline_with_valid_config_and_token() {
    let temp = TempDir::new().unwrap();
    let paths = PocketPaths::from_home_dir(temp.path());

    create_dir_user_only(&paths.config_dir).unwrap();

    let config = GatewayConfig {
        host: "127.0.0.1".to_string(),
        port: 8088,
        token_path: None,
        log_dir: None,
        cmux_path: None,
        extra: toml::Table::new(),
    };
    save_config(&paths.config_file, &config).unwrap();
    save_token(
        &paths.token_file,
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    )
    .unwrap();

    let args = DoctorArgs {
        offline: true,
        deep: false,
    };

    let res = handle_doctor(&paths, &args, false).await;
    assert!(
        res.is_ok(),
        "Offline doctor with valid config & token must pass"
    );
}

#[tokio::test]
async fn test_doctor_offline_detects_corrupt_config() {
    let temp = TempDir::new().unwrap();
    let paths = PocketPaths::from_home_dir(temp.path());

    create_dir_user_only(&paths.config_dir).unwrap();
    fs::write(&paths.config_file, "INVALID_TOML[[[[").unwrap();

    let args = DoctorArgs {
        offline: true,
        deep: false,
    };

    let res = handle_doctor(&paths, &args, false).await;
    assert!(
        res.is_err(),
        "Doctor must fail when configuration file is corrupt"
    );
}

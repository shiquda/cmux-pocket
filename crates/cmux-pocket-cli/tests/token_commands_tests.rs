use cmux_pocket_cli::cli::TokenSubcommand;
use cmux_pocket_cli::commands::handle_token;
use cmux_pocket_cli::output::token_fingerprint;
use cmux_pocket_macos::{create_dir_user_only, load_token, save_token, PocketPaths};
use tempfile::TempDir;

#[tokio::test]
async fn test_token_show_and_rotate() {
    let temp = TempDir::new().unwrap();
    let paths = PocketPaths::from_home_dir(temp.path());

    create_dir_user_only(&paths.config_dir).unwrap();

    let initial_token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    save_token(&paths.token_file, initial_token).unwrap();

    let fp_initial = token_fingerprint(initial_token);

    // 1. Token show
    let show_res = handle_token(&paths, &TokenSubcommand::Show, false).await;
    assert!(show_res.is_ok());

    // 2. Token rotate
    let rotate_res = handle_token(&paths, &TokenSubcommand::Rotate, false).await;
    assert!(rotate_res.is_ok());

    // Verify token was changed
    let rotated_token = load_token(&paths.token_file).unwrap();
    assert_ne!(initial_token, rotated_token);
    assert_eq!(rotated_token.trim().len(), 64);

    let fp_rotated = token_fingerprint(&rotated_token);
    assert_ne!(fp_initial, fp_rotated);
}

#[tokio::test]
async fn test_token_show_missing_fails() {
    let temp = TempDir::new().unwrap();
    let paths = PocketPaths::from_home_dir(temp.path());

    let show_res = handle_token(&paths, &TokenSubcommand::Show, false).await;
    assert!(
        show_res.is_err(),
        "Token show on non-existent token should fail with error"
    );
}

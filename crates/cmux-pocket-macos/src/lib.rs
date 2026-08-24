//! macOS foundation library for cmux-pocket.
//!
//! Provides deterministic macOS paths, atomic user-only config/token storage,
//! strict loopback network validation, launchd plist generation, and safe command
//! specifications.

pub mod command;
pub mod config;
pub mod error;
pub mod launchd;
pub mod loopback;
pub mod paths;
pub mod permissions;
pub mod token;

// Re-export common types and functions for ergonomic access
pub use command::CommandSpec;
pub use config::{
    ensure_config, load_config, save_config, GatewayConfig, DEFAULT_GATEWAY_HOST,
    DEFAULT_GATEWAY_PORT,
};
pub use error::{LoopbackError, MacOsError};
pub use launchd::{
    generate_launchd_plist, get_current_uid, launchctl_bootout_plist_cmd,
    launchctl_bootout_service_cmd, launchctl_bootstrap_cmd, launchctl_disable_service_cmd,
    launchctl_enable_service_cmd, launchctl_kickstart_cmd, launchctl_print_service_cmd,
    parse_launchctl_print, save_launchd_plist, LaunchdPlistConfig, LaunchdServiceStatus,
    LaunchdTarget, PLIST_FILE_MODE,
};
pub use loopback::{
    is_loopback_host, is_loopback_ip, parse_and_validate_bind, validate_loopback_addr,
    validate_loopback_host, validate_loopback_url,
};
pub use paths::{
    is_cellar_path, resolve_stable_executable_path, validate_outside_cellar, PocketPaths,
    APP_DIR_NAME, DEFAULT_CONFIG_FILENAME, DEFAULT_LAUNCHD_LABEL, DEFAULT_PLIST_FILENAME,
    DEFAULT_TOKEN_FILENAME,
};
pub use permissions::{
    atomic_write_file, atomic_write_secret_file, create_dir_user_only, ensure_dir_user_only,
    ensure_file_user_only, set_user_only_dir_permissions, set_user_only_file_permissions,
    GROUP_OTHER_PERMISSION_MASK, USER_ONLY_DIR_MODE, USER_ONLY_FILE_MODE,
};
pub use token::{
    ensure_token, generate_token, load_token, rotate_token, save_token, RedactedToken,
    TokenFingerprint, TOKEN_BYTE_LEN,
};

/// Returns the library version.
pub fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

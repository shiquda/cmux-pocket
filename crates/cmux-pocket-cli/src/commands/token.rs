//! Implementation of `cmux-pocket token` subcommands.

use crate::cli::TokenSubcommand;
use crate::error::CliError;
use crate::output::{print_success, token_fingerprint};
use cmux_pocket_macos::{
    get_current_uid, launchctl_kickstart_cmd, load_config, load_token, rotate_token, GatewayConfig,
    LaunchdTarget, PocketPaths, DEFAULT_LAUNCHD_LABEL,
};
use serde::Serialize;
use std::fs;

#[derive(Debug, Serialize)]
pub struct TokenPathData {
    pub path: String,
}

#[derive(Debug, Serialize)]
pub struct TokenShowData {
    pub path: String,
    pub exists: bool,
    pub fingerprint: String,
    pub length: usize,
    pub permissions_valid: bool,
}

#[derive(Debug, Serialize)]
pub struct TokenRotateData {
    pub path: String,
    pub new_fingerprint: String,
    pub service_reloaded: bool,
    pub note: String,
}

/// Handles `cmux-pocket token` subcommands.
pub async fn handle_token(
    paths: &PocketPaths,
    subcmd: &TokenSubcommand,
    json_mode: bool,
) -> Result<(), CliError> {
    let config = if paths.config_file.exists() {
        load_config(&paths.config_file).unwrap_or_default()
    } else {
        GatewayConfig::default()
    };
    let token_path = config.resolve_token_path(paths);

    match subcmd {
        TokenSubcommand::Path => {
            let path_str = token_path.display().to_string();
            let data = TokenPathData {
                path: path_str.clone(),
            };
            print_success(&data, &path_str, json_mode);
            Ok(())
        }
        TokenSubcommand::Show => {
            if !token_path.exists() {
                return Err(CliError::ConfigOrToken(format!(
                    "Token file does not exist at {}. Run 'cmux-pocket setup' to create one.",
                    token_path.display()
                )));
            }

            let token = load_token(&token_path)?;
            let fp = token_fingerprint(&token);
            let metadata = fs::metadata(&token_path)?;
            #[cfg(unix)]
            let mode = {
                use std::os::unix::fs::PermissionsExt;
                metadata.permissions().mode() & 0o777
            };
            #[cfg(not(unix))]
            let mode = 0o600;

            let permissions_valid = mode & 0o077 == 0;

            let data = TokenShowData {
                path: token_path.display().to_string(),
                exists: true,
                fingerprint: fp.clone(),
                length: token.trim().len(),
                permissions_valid,
            };

            let prose = format!(
                "Token path: {}\nFingerprint: {}\nPermissions: {:04o} (valid: {})\n(Raw secret is redacted and never printed)",
                token_path.display(),
                fp,
                mode,
                permissions_valid
            );

            print_success(&data, &prose, json_mode);
            Ok(())
        }
        TokenSubcommand::Rotate => {
            if let Some(parent) = token_path.parent() {
                cmux_pocket_macos::create_dir_user_only(parent)?;
            }

            let new_token = rotate_token(&token_path)?;
            let new_fp = token_fingerprint(&new_token);

            // Attempt to kickstart running launchd service if registered
            let mut service_reloaded = false;
            let uid = get_current_uid();
            let target = LaunchdTarget::Gui(uid);
            let kickstart_cmd = launchctl_kickstart_cmd(&target, DEFAULT_LAUNCHD_LABEL, true);

            if let Ok(status) = kickstart_cmd.to_std_command().status() {
                if status.success() {
                    service_reloaded = true;
                }
            }

            let note = "Token rotated successfully. IMPORTANT: You MUST update your Android cmux Pocket connection profile with the new token.".to_string();

            let data = TokenRotateData {
                path: token_path.display().to_string(),
                new_fingerprint: new_fp.clone(),
                service_reloaded,
                note: note.clone(),
            };

            let prose = format!(
                "Token rotated successfully.\nNew fingerprint: {}\nPath: {}\nService reloaded: {}\n\nIMPORTANT: You MUST update your Android cmux Pocket connection profile with the new token.",
                new_fp,
                token_path.display(),
                service_reloaded
            );

            print_success(&data, &prose, json_mode);
            Ok(())
        }
    }
}

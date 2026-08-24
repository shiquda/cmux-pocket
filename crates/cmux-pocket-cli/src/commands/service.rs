//! Implementation of `cmux-pocket service` subcommands.

use crate::cli::ServiceSubcommand;
use crate::error::CliError;
use crate::output::print_success;
use cmux_pocket_macos::{
    generate_launchd_plist, get_current_uid, launchctl_bootout_service_cmd,
    launchctl_bootstrap_cmd, launchctl_disable_service_cmd, launchctl_enable_service_cmd,
    launchctl_kickstart_cmd, launchctl_print_service_cmd, parse_launchctl_print,
    save_launchd_plist, LaunchdPlistConfig, LaunchdServiceStatus, LaunchdTarget, PocketPaths,
    DEFAULT_LAUNCHD_LABEL,
};
use serde::Serialize;
use std::fs;

#[derive(Debug, Serialize)]
pub struct ServiceOperationData {
    pub operation: String,
    pub label: String,
    pub plist_path: String,
    pub status: String,
    pub details: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct ServiceStatusData {
    pub label: String,
    pub plist_path: String,
    pub plist_exists: bool,
    pub registered: bool,
    pub pid: Option<u32>,
    pub state: String,
    pub last_exit_code: Option<i32>,
}

/// Handles `cmux-pocket service` subcommands.
pub async fn handle_service(
    paths: &PocketPaths,
    subcmd: &ServiceSubcommand,
    json_mode: bool,
) -> Result<(), CliError> {
    let uid = get_current_uid();
    let target = LaunchdTarget::Gui(uid);
    let label = DEFAULT_LAUNCHD_LABEL;

    match subcmd {
        ServiceSubcommand::Install => {
            // 1. Resolve executable path
            let current_exe = std::env::current_exe().map_err(|e| {
                CliError::RuntimeFailure(format!(
                    "Failed to determine current executable path: {}",
                    e
                ))
            })?;
            let plist_config =
                LaunchdPlistConfig::new(&current_exe, &paths.config_file, &paths.log_dir);

            let plist_xml = generate_launchd_plist(&plist_config)?;
            save_launchd_plist(&paths.plist_file, &plist_xml)?;

            // 5. Unload stale job if already loaded.
            let bootout_cmd = launchctl_bootout_service_cmd(&target, label);
            let _ = bootout_cmd.to_std_command().output();

            // 6. Clear a previous disabled state before bootstrapping.
            let enable_cmd = launchctl_enable_service_cmd(&target, label);
            let _ = enable_cmd.to_std_command().output();

            // 7. Bootstrap new plist.
            let bootstrap_cmd = launchctl_bootstrap_cmd(&target, &paths.plist_file);
            let bootstrap_out = bootstrap_cmd.to_std_command().output().map_err(|e| {
                CliError::DependencyUnavailable(format!(
                    "Failed to execute launchctl bootstrap: {}",
                    e
                ))
            })?;

            if !bootstrap_out.status.success() {
                let stderr = String::from_utf8_lossy(&bootstrap_out.stderr);
                // Note: If bootstrap failed because already bootstrapped, we continue
                if !stderr.contains("Already bootstrapped")
                    && !stderr.contains("service already bootstrapped")
                {
                    return Err(CliError::DependencyUnavailable(format!(
                        "launchctl bootstrap failed: {}",
                        stderr.trim()
                    )));
                }
            }

            // 8. Kickstart service

            let kickstart_cmd = launchctl_kickstart_cmd(&target, label, true);
            let kickstart_out = kickstart_cmd.to_std_command().output().map_err(|e| {
                CliError::DependencyUnavailable(format!(
                    "Failed to execute launchctl kickstart: {}",
                    e
                ))
            })?;

            let details = if kickstart_out.status.success() {
                "Service installed, bootstrapped, and kickstarted successfully.".to_string()
            } else {
                let stderr = String::from_utf8_lossy(&kickstart_out.stderr);
                format!(
                    "Service installed and bootstrapped. Kickstart returned: {}",
                    stderr.trim()
                )
            };

            let data = ServiceOperationData {
                operation: "install".to_string(),
                label: label.to_string(),
                plist_path: paths.plist_file.display().to_string(),
                status: "installed".to_string(),
                details: Some(details.clone()),
            };

            print_success(&data, &details, json_mode);
            Ok(())
        }
        ServiceSubcommand::Uninstall => {
            // 1. Bootout service
            let bootout_cmd = launchctl_bootout_service_cmd(&target, label);
            let _ = bootout_cmd.to_std_command().output();

            // 2. Disable service
            let disable_cmd = launchctl_disable_service_cmd(&target, label);
            let _ = disable_cmd.to_std_command().output();

            // 3. Remove plist file
            let mut file_removed = false;
            if paths.plist_file.exists() {
                fs::remove_file(&paths.plist_file).map_err(|e| {
                    CliError::RuntimeFailure(format!(
                        "Failed to remove plist file {}: {}",
                        paths.plist_file.display(),
                        e
                    ))
                })?;
                file_removed = true;
            }

            let msg = format!(
                "Service '{}' uninstalled successfully (plist removed: {})",
                label, file_removed
            );

            let data = ServiceOperationData {
                operation: "uninstall".to_string(),
                label: label.to_string(),
                plist_path: paths.plist_file.display().to_string(),
                status: "uninstalled".to_string(),
                details: Some(msg.clone()),
            };

            print_success(&data, &msg, json_mode);
            Ok(())
        }
        ServiceSubcommand::Start => {
            let kickstart_cmd = launchctl_kickstart_cmd(&target, label, true);
            let out = kickstart_cmd.to_std_command().output().map_err(|e| {
                CliError::DependencyUnavailable(format!(
                    "Failed to execute launchctl kickstart: {}",
                    e
                ))
            })?;

            if !out.status.success() {
                let stderr = String::from_utf8_lossy(&out.stderr);
                return Err(CliError::DependencyUnavailable(format!(
                    "launchctl kickstart failed for service '{}': {}. Run 'cmux-pocket service install' first.",
                    label, stderr.trim()
                )));
            }

            let msg = format!("Service '{}' started successfully", label);
            let data = ServiceOperationData {
                operation: "start".to_string(),
                label: label.to_string(),
                plist_path: paths.plist_file.display().to_string(),
                status: "started".to_string(),
                details: Some(msg.clone()),
            };

            print_success(&data, &msg, json_mode);
            Ok(())
        }
        ServiceSubcommand::Stop => {
            let bootout_cmd = launchctl_bootout_service_cmd(&target, label);
            let out = bootout_cmd.to_std_command().output().map_err(|e| {
                CliError::DependencyUnavailable(format!(
                    "Failed to execute launchctl bootout: {}",
                    e
                ))
            })?;

            let stderr = String::from_utf8_lossy(&out.stderr);
            let msg = if out.status.success() || stderr.contains("No such process") {
                format!("Service '{}' stopped successfully", label)
            } else {
                format!("Service '{}' stop issued ({})", label, stderr.trim())
            };

            let data = ServiceOperationData {
                operation: "stop".to_string(),
                label: label.to_string(),
                plist_path: paths.plist_file.display().to_string(),
                status: "stopped".to_string(),
                details: Some(msg.clone()),
            };

            print_success(&data, &msg, json_mode);
            Ok(())
        }
        ServiceSubcommand::Restart => {
            let kickstart_cmd = launchctl_kickstart_cmd(&target, label, true);
            let out = kickstart_cmd.to_std_command().output().map_err(|e| {
                CliError::DependencyUnavailable(format!(
                    "Failed to execute launchctl kickstart: {}",
                    e
                ))
            })?;

            if !out.status.success() {
                let stderr = String::from_utf8_lossy(&out.stderr);
                return Err(CliError::DependencyUnavailable(format!(
                    "launchctl kickstart restart failed for service '{}': {}",
                    label,
                    stderr.trim()
                )));
            }

            let msg = format!("Service '{}' restarted successfully", label);
            let data = ServiceOperationData {
                operation: "restart".to_string(),
                label: label.to_string(),
                plist_path: paths.plist_file.display().to_string(),
                status: "restarted".to_string(),
                details: Some(msg.clone()),
            };

            print_success(&data, &msg, json_mode);
            Ok(())
        }
        ServiceSubcommand::Status => {
            let print_cmd = launchctl_print_service_cmd(&target, label);
            let out_res = print_cmd.to_std_command().output();

            let plist_exists = paths.plist_file.exists();

            let (registered, status) = match out_res {
                Ok(out) if out.status.success() => {
                    let text = String::from_utf8_lossy(&out.stdout);
                    let st = parse_launchctl_print(&text);
                    (st.registered, st)
                }
                _ => (
                    false,
                    LaunchdServiceStatus {
                        registered: false,
                        state: "not_registered".to_string(),
                        pid: None,
                        last_exit_code: None,
                        last_exit_reason: None,
                    },
                ),
            };

            let data = ServiceStatusData {
                label: label.to_string(),
                plist_path: paths.plist_file.display().to_string(),
                plist_exists,
                registered,
                pid: status.pid,
                state: status.state.clone(),
                last_exit_code: status.last_exit_code,
            };

            let prose = format!(
                "Service: {}\nRegistered: {}\nState: {}\nPID: {}\nPlist: {} (exists: {})\nLast exit code: {}",
                label,
                registered,
                status.state,
                status.pid.map(|p| p.to_string()).unwrap_or_else(|| "none".to_string()),
                paths.plist_file.display(),
                plist_exists,
                status.last_exit_code.map(|c| c.to_string()).unwrap_or_else(|| "none".to_string())
            );

            print_success(&data, &prose, json_mode);
            Ok(())
        }
    }
}

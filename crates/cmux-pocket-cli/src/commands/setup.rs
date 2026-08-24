//! Implementation of `cmux-pocket setup` command.

use crate::cli::SetupArgs;
use crate::error::CliError;
use crate::output::{print_success, token_fingerprint};
use crate::probe::probe_gateway;
use cmux_pocket_cmux::{CmuxBackend, LiveCmuxBackend};
use cmux_pocket_macos::{
    create_dir_user_only, ensure_token, generate_launchd_plist, get_current_uid,
    launchctl_bootout_service_cmd, launchctl_bootstrap_cmd, launchctl_enable_service_cmd,
    launchctl_kickstart_cmd, load_config, save_config, save_launchd_plist, GatewayConfig,
    LaunchdPlistConfig, LaunchdTarget, PocketPaths, DEFAULT_LAUNCHD_LABEL,
};
use serde::Serialize;
use std::path::PathBuf;
use std::time::Duration;
use tokio::time::sleep;

#[derive(Debug, Serialize)]
pub struct SetupData {
    pub endpoint: String,
    pub config_path: String,
    pub token_path: String,
    pub token_fingerprint: String,
    pub plist_path: String,
    pub service_started: bool,
    pub cmux_discovered: bool,
    pub cmux_path: String,
    pub probe_ok: bool,
    pub next_steps: String,
}

fn discover_cmux_binary(config: &GatewayConfig) -> (PathBuf, bool) {
    if let Some(p) = &config.cmux_path {
        if p.exists() {
            return (p.clone(), true);
        }
    }

    // Common standard macOS locations
    let candidates = ["/opt/homebrew/bin/cmux", "/usr/local/bin/cmux"];

    for candidate in &candidates {
        let p = PathBuf::from(candidate);
        if p.exists() {
            return (p, true);
        }
    }

    // PATH check via which or standard binary name
    (PathBuf::from("cmux"), false)
}

/// Handles `cmux-pocket setup` workflow.
pub async fn handle_setup(
    paths: &PocketPaths,
    args: &SetupArgs,
    json_mode: bool,
) -> Result<(), CliError> {
    // 1. macOS environment check
    #[cfg(not(target_os = "macos"))]
    {
        return Err(CliError::RuntimeFailure(
            "cmux-pocket is designed for macOS (Darwin) systems".to_string(),
        ));
    }

    // 2. Ensure directories exist with 0o700 permissions
    create_dir_user_only(&paths.config_dir)?;
    create_dir_user_only(&paths.log_dir)?;
    create_dir_user_only(&paths.launch_agents_dir)?;

    // 3. Load or create config (preserve existing values!)
    let (mut config, _config_created) = if paths.config_file.exists() {
        let existing = load_config(&paths.config_file)?;
        (existing, false)
    } else {
        let default_cfg = GatewayConfig::default();
        (default_cfg, true)
    };

    if let Some(p) = args.port {
        if p > 0 {
            config.port = p;
        }
    }

    let (discovered_cmux, cmux_found) = discover_cmux_binary(&config);
    if config.cmux_path.is_none() && cmux_found {
        config.cmux_path = Some(discovered_cmux.clone());
    }

    save_config(&paths.config_file, &config)?;

    // 4. Ensure token exists (do not rotate existing token!)
    let token_path = config.resolve_token_path(paths);
    if let Some(parent) = token_path.parent() {
        create_dir_user_only(parent)?;
    }

    let (token, _token_created) = ensure_token(&token_path)?;
    let fp = token_fingerprint(&token);

    // 5. Run cmux ping check
    let cmux_backend = LiveCmuxBackend::with_path(&discovered_cmux);
    let cmux_ping_ok = cmux_backend.ping().await.is_ok();

    // 6. Generate and save launchd plist
    let current_exe = std::env::current_exe().map_err(|e| {
        CliError::RuntimeFailure(format!(
            "Failed to determine current executable path: {}",
            e
        ))
    })?;
    let mut plist_config =
        LaunchdPlistConfig::new(&current_exe, &paths.config_file, &paths.log_dir);
    plist_config.run_at_load = !args.no_start;

    let plist_xml = generate_launchd_plist(&plist_config)?;
    save_launchd_plist(&paths.plist_file, &plist_xml)?;

    // 7. Optional service start
    let mut service_started = false;
    let mut probe_ok = false;

    if !args.no_start {
        let uid = get_current_uid();
        let target = LaunchdTarget::Gui(uid);

        // Bootout stale job if exists
        let bootout_cmd = launchctl_bootout_service_cmd(&target, DEFAULT_LAUNCHD_LABEL);
        let _ = bootout_cmd.to_std_command().output();

        // Clear a previous disabled state before bootstrapping the plist.
        let enable_cmd = launchctl_enable_service_cmd(&target, DEFAULT_LAUNCHD_LABEL);
        let _ = enable_cmd.to_std_command().output();

        // Bootstrap
        let bootstrap_cmd = launchctl_bootstrap_cmd(&target, &paths.plist_file);
        let _ = bootstrap_cmd.to_std_command().output();

        // Kickstart
        let kickstart_cmd = launchctl_kickstart_cmd(&target, DEFAULT_LAUNCHD_LABEL, true);
        if let Ok(st) = kickstart_cmd.to_std_command().output() {
            if st.status.success() {
                service_started = true;
            }
        }

        // 8. Probe local gateway with short backoff
        for _ in 0..5 {
            sleep(Duration::from_millis(300)).await;
            if let Ok(report) =
                probe_gateway(&config.host, config.port, &token, Duration::from_secs(2)).await
            {
                if report.connected && report.authenticated {
                    probe_ok = true;
                    break;
                }
            }
        }
    }

    let endpoint = format!("ws://{}:{}", config.host, config.port);
    let next_steps = format!(
        "In Android cmux Pocket, add a connection profile with endpoint '{}' and copy the token from '{}'.",
        endpoint,
        token_path.display()
    );

    let data = SetupData {
        endpoint: endpoint.clone(),
        config_path: paths.config_file.display().to_string(),
        token_path: token_path.display().to_string(),
        token_fingerprint: fp.clone(),
        plist_path: paths.plist_file.display().to_string(),
        service_started,
        cmux_discovered: cmux_found || cmux_ping_ok,
        cmux_path: discovered_cmux.display().to_string(),
        probe_ok,
        next_steps: next_steps.clone(),
    };

    let prose = format!(
        "cmux-pocket Setup Complete\n\
         ==========================\n\
         Endpoint:          {}\n\
         Config Path:       {}\n\
         Token Path:        {}\n\
         Token Fingerprint: {}\n\
         Launchd Service:   {}\n\
         cmux Discovered:   {} ({})\n\
         Gateway Probe:     {}\n\n\
         Next Steps:\n\
         {}\n\
         (Note: Raw secret token is stored securely in '{}' and is never printed in logs or terminal)",
        endpoint,
        paths.config_file.display(),
        token_path.display(),
        fp,
        if service_started { "Started & Enabled" } else if args.no_start { "Configured (no-start flag)" } else { "Created" },
        if cmux_ping_ok { "Ready (ping ok)" } else if cmux_found { "Found" } else { "Not running" },
        discovered_cmux.display(),
        if probe_ok { "OK (authenticated)" } else if args.no_start { "Skipped (--no-start)" } else { "Pending start" },
        next_steps,
        token_path.display()
    );

    print_success(&data, &prose, json_mode);
    Ok(())
}

//! Implementation of `cmux-pocket setup` command.

use crate::cli::SetupArgs;
use crate::error::CliError;
use crate::output::{print_success, token_fingerprint};
use crate::probe::{probe_gateway, ProbeReport};
use crate::tailscale::{TailnetEndpoint, Tailscale};
use cmux_pocket_cmux::{CmuxBackend, LiveCmuxBackend};
use cmux_pocket_macos::{
    create_dir_user_only, ensure_token, generate_launchd_plist, get_current_uid,
    launchctl_bootout_service_cmd, launchctl_bootstrap_cmd, launchctl_enable_service_cmd,
    launchctl_kickstart_cmd, load_config, save_config, save_launchd_plist, GatewayConfig,
    LaunchdPlistConfig, LaunchdTarget, PocketPaths, DEFAULT_LAUNCHD_LABEL,
};
use serde::Serialize;
use serde_json::Value;
use std::path::PathBuf;
use std::time::Duration;
use tokio::time::sleep;

const CMUX_BINARY_CANDIDATES: [&str; 3] = [
    "/Applications/cmux.app/Contents/Resources/bin/cmux",
    "/opt/homebrew/bin/cmux",
    "/usr/local/bin/cmux",
];

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
    pub gateway_reused: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub tailscale: Option<TailnetEndpoint>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub uri: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub qr_code: Option<String>,
    pub next_steps: String,
}

fn discover_cmux_binary(config: &GatewayConfig) -> (PathBuf, bool) {
    if let Some(p) = &config.cmux_path {
        if p.exists() {
            return (p.clone(), true);
        }
    }

    for candidate in CMUX_BINARY_CANDIDATES {
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

    if let Some(ref ep) = args.endpoint {
        let parsed = cmux_pocket_macos::validate_remote_endpoint(ep)?;
        config.endpoint = Some(parsed.raw);
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
    let mut gateway_reused = false;

    if !args.no_start {
        gateway_reused = probe_gateway(&config.host, config.port, &token, Duration::from_secs(2))
            .await
            .is_ok_and(|report| report.is_backend_healthy() && gateway_is_current(&report));
    }

    if gateway_reused {
        probe_ok = true;
    } else if !args.no_start {
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
                if report.connected && report.is_backend_healthy() {
                    probe_ok = true;
                    break;
                }
            }
        }
    }

    let tailscale = if args.tailscale {
        if !probe_ok {
            return Err(CliError::DependencyUnavailable(
                "Gateway workspace handshake failed. Run cmux-pocket gateway probe. If cmux uses 'cmux processes only' socket access, run cmux-pocket service stop, then cmux-pocket gateway run in a dedicated cmux terminal. Keep it running and rerun setup --tailscale in another tab.".to_string(),
            ));
        }
        Some(
            Tailscale::default()
                .setup(&config.host, config.port)
                .await?,
        )
    } else {
        None
    };

    let (mobile_endpoint, mobile_source) = if let Some(ref ep) = args.endpoint {
        let parsed = cmux_pocket_macos::validate_remote_endpoint(ep)?;
        (parsed.raw, "CLI argument (--endpoint)")
    } else if let Some(ref tailnet) = tailscale {
        (format!("wss://{}", tailnet.host), "Tailscale Serve")
    } else if let Some(ref saved) = config.endpoint {
        (saved.clone(), "saved config.endpoint")
    } else {
        (
            format!("ws://{}:{}", config.host, config.port),
            "loopback default",
        )
    };

    let parsed_mobile = cmux_pocket_macos::parse_and_validate_endpoint(&mobile_endpoint).ok();
    let uri = parsed_mobile
        .as_ref()
        .map(|ep| crate::uri::build_import_uri(ep, &token, None));

    let qr_code = if args.qr {
        if let Some(ref u) = uri {
            let invert = !args.no_invert;
            crate::commands::qr::generate_ansi_qr(u, invert).ok()
        } else {
            None
        }
    } else {
        None
    };

    let mut next_steps = format!(
        "In Android cmux Pocket, add a connection profile with endpoint '{}' and copy the token from '{}'.\n\
         Or run `cmux-pocket qr` to display a QR code for one-tap import into the Android app.",
        mobile_endpoint,
        token_path.display()
    );
    if tailscale.is_some() {
        next_steps.push_str(" Connect Tailscale on both devices. In Pocket Settings, use Check connection to verify the phone-to-Gateway handshake and workspace access. Tailscale Serve stays configured in the background.");
    } else {
        next_steps.push_str(" This loopback endpoint works on the Mac or with USB reverse. For a phone over Tailscale, run cmux-pocket setup --tailscale.");
    }

    let data = SetupData {
        endpoint: mobile_endpoint.clone(),
        config_path: paths.config_file.display().to_string(),
        token_path: token_path.display().to_string(),
        token_fingerprint: fp.clone(),
        plist_path: paths.plist_file.display().to_string(),
        service_started,
        cmux_discovered: cmux_found || cmux_ping_ok,
        cmux_path: discovered_cmux.display().to_string(),
        probe_ok,
        gateway_reused,
        tailscale,
        uri: uri.clone(),
        qr_code: qr_code.clone(),
        next_steps: next_steps.clone(),
    };

    let mut prose = String::new();
    if let Some(ref qr) = qr_code {
        prose.push_str(qr);
        prose.push_str("\n\n");
    }
    prose.push_str(&format!(
        "cmux-pocket Setup Complete\n\
         ==========================\n\
         Endpoint:          {} ({})\n\
         Config Path:       {}\n\
         Token Path:        {}\n\
         Token Fingerprint: {}\n\
         Launchd Service:   {}\n\
         cmux Discovered:   {} ({})\n\
         Gateway Probe:     {}\n",
        mobile_endpoint,
        mobile_source,
        paths.config_file.display(),
        token_path.display(),
        fp,
        if gateway_reused {
            "Existing Gateway reused"
        } else if service_started {
            "Started & Enabled"
        } else if args.no_start {
            "Configured (no-start flag)"
        } else {
            "Created"
        },
        if cmux_ping_ok {
            "Ready (ping ok)"
        } else if cmux_found {
            "Found"
        } else {
            "Not running"
        },
        discovered_cmux.display(),
        if probe_ok {
            "OK (workspace access verified)"
        } else if args.no_start {
            "Skipped (--no-start)"
        } else {
            "Unavailable (run cmux-pocket gateway probe)"
        },
    ));
    if let Some(ref u) = uri {
        prose.push_str(&format!("Import Link:       {}\n", u));
    }
    prose.push_str(&format!(
        "\nNext Steps:\n{}\n\n(Note: Raw secret token is stored securely in '{}' and is never printed in logs or terminal)",
        next_steps,
        token_path.display()
    ));

    print_success(&data, &prose, json_mode);
    Ok(())
}

/// Whether the probed Gateway already runs the build this CLI ships.
///
/// `brew upgrade` replaces the binary on disk but launchd keeps the old process,
/// so a healthy Gateway can still be the previous release. Reusing it would leave
/// `setup` reporting success while the phone keeps talking to the old build.
fn gateway_is_current(report: &ProbeReport) -> bool {
    let running = report
        .host_status
        .as_ref()
        .and_then(|status| status.get("gateway_version"))
        .and_then(Value::as_str);
    match (
        running.and_then(crate::version::parse_version),
        crate::version::parse_version(env!("CARGO_PKG_VERSION")),
    ) {
        (Some(running), Some(current)) => running >= current,
        // Gateways older than 0.2.0 do not report a build version at all, and an
        // unparseable value cannot prove the process is current.
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::{gateway_is_current, CMUX_BINARY_CANDIDATES};
    use crate::probe::ProbeReport;

    fn report(gateway_version: Option<&str>) -> ProbeReport {
        ProbeReport {
            connected: true,
            authenticated: true,
            host: "127.0.0.1".to_string(),
            port: 8088,
            server_version: Some("2.0.0".to_string()),
            session_id: None,
            capabilities: Vec::new(),
            host_status: gateway_version
                .map(|version| serde_json::json!({ "gateway_version": version })),
            latency_ms: 1,
            backend_health: Some("healthy".to_string()),
            error: None,
        }
    }

    #[test]
    fn reuses_a_gateway_running_the_same_or_a_newer_build() {
        assert!(gateway_is_current(&report(Some(env!("CARGO_PKG_VERSION")))));
        assert!(gateway_is_current(&report(Some("99.0.0"))));
    }

    #[test]
    fn restarts_a_gateway_left_behind_by_an_upgrade() {
        assert!(!gateway_is_current(&report(Some("0.0.1"))));
        assert!(!gateway_is_current(&report(Some("not-a-version"))));
        assert!(!gateway_is_current(&report(None)));
    }

    #[test]
    fn discovers_cmux_app_bundled_cli_for_launchd() {
        assert_eq!(
            CMUX_BINARY_CANDIDATES[0],
            "/Applications/cmux.app/Contents/Resources/bin/cmux"
        );
    }
}

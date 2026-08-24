//! Implementation of `cmux-pocket status` command.

use crate::cli::StatusArgs;
use crate::error::CliError;
use crate::output::{print_success, token_fingerprint};
use crate::probe::probe_gateway;
use cmux_pocket_cmux::{CmuxBackend, LiveCmuxBackend};
use cmux_pocket_macos::{
    get_current_uid, launchctl_print_service_cmd, load_config, load_token, parse_launchctl_print,
    GatewayConfig, LaunchdServiceStatus, LaunchdTarget, PocketPaths, DEFAULT_LAUNCHD_LABEL,
};
use serde::Serialize;
use std::fs;
use std::net::TcpStream;
use std::time::Duration;

#[derive(Debug, Serialize)]
pub struct ConfigStatus {
    pub path: String,
    pub exists: bool,
    pub valid: bool,
    pub host: String,
    pub port: u16,
    pub cmux_path: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct TokenStatus {
    pub path: String,
    pub exists: bool,
    pub permissions_valid: bool,
    pub fingerprint: String,
    pub length: usize,
}

#[derive(Debug, Serialize)]
pub struct ServiceStatus {
    pub label: String,
    pub plist_path: String,
    pub plist_exists: bool,
    pub registered: bool,
    pub pid: Option<u32>,
    pub state: String,
    pub last_exit_code: Option<i32>,
}

#[derive(Debug, Serialize)]
pub struct GatewayStatus {
    pub listening: bool,
    pub authenticated_probe: bool,
    pub server_version: Option<String>,
    pub capabilities: Vec<String>,
    pub backend_health: Option<String>,
    pub latency_ms: Option<u64>,
}

#[derive(Debug, Serialize)]
pub struct CmuxStatus {
    pub path: String,
    pub ping_ok: bool,
}

#[derive(Debug, Serialize)]
pub struct StatusData {
    pub overall_status: String,
    pub config: ConfigStatus,
    pub token: TokenStatus,
    pub service: ServiceStatus,
    pub gateway: GatewayStatus,
    pub cmux: CmuxStatus,
}

/// Handles `cmux-pocket status` command.
pub async fn handle_status(
    paths: &PocketPaths,
    _args: &StatusArgs,
    json_mode: bool,
) -> Result<(), CliError> {
    // 1. Config status
    let (config, config_exists, config_valid) = if paths.config_file.exists() {
        match load_config(&paths.config_file) {
            Ok(cfg) => (cfg, true, true),
            Err(_) => (GatewayConfig::default(), true, false),
        }
    } else {
        (GatewayConfig::default(), false, false)
    };

    let config_status = ConfigStatus {
        path: paths.config_file.display().to_string(),
        exists: config_exists,
        valid: config_valid,
        host: config.host.clone(),
        port: config.port,
        cmux_path: config.cmux_path.as_ref().map(|p| p.display().to_string()),
    };

    // 2. Token status
    let token_path = config.resolve_token_path(paths);
    let (token_exists, token_valid_perms, token_fp, token_len, loaded_token) =
        if token_path.exists() {
            let perms_valid = fs::metadata(&token_path)
                .map(|m| {
                    #[cfg(unix)]
                    {
                        use std::os::unix::fs::PermissionsExt;
                        m.permissions().mode() & 0o077 == 0
                    }
                    #[cfg(not(unix))]
                    true
                })
                .unwrap_or(false);

            match load_token(&token_path) {
                Ok(t) => {
                    let fp = token_fingerprint(&t);
                    let len = t.trim().len();
                    (true, perms_valid, fp, len, Some(t))
                }
                Err(_) => (true, perms_valid, "invalid".to_string(), 0, None),
            }
        } else {
            (false, false, "none".to_string(), 0, None)
        };

    let token_status = TokenStatus {
        path: token_path.display().to_string(),
        exists: token_exists,
        permissions_valid: token_valid_perms,
        fingerprint: token_fp,
        length: token_len,
    };

    // 3. Service status
    let uid = get_current_uid();
    let target = LaunchdTarget::Gui(uid);
    let label = DEFAULT_LAUNCHD_LABEL;
    let plist_exists = paths.plist_file.exists();

    let print_cmd = launchctl_print_service_cmd(&target, label);
    let launchd_status = match print_cmd.to_std_command().output() {
        Ok(out) if out.status.success() => {
            let text = String::from_utf8_lossy(&out.stdout);
            parse_launchctl_print(&text)
        }
        _ => LaunchdServiceStatus {
            registered: false,
            state: "not_registered".to_string(),
            pid: None,
            last_exit_code: None,
            last_exit_reason: None,
        },
    };

    let service_status = ServiceStatus {
        label: label.to_string(),
        plist_path: paths.plist_file.display().to_string(),
        plist_exists,
        registered: launchd_status.registered,
        pid: launchd_status.pid,
        state: launchd_status.state.clone(),
        last_exit_code: launchd_status.last_exit_code,
    };

    // 4. Listener & Gateway Probe
    let socket_addr = format!("{}:{}", config.host, config.port);
    let listening = TcpStream::connect_timeout(
        &socket_addr
            .parse()
            .unwrap_or_else(|_| "127.0.0.1:8088".parse().unwrap()),
        Duration::from_millis(500),
    )
    .is_ok();

    let mut auth_probe_ok = false;
    let mut server_version = None;
    let mut capabilities = Vec::new();
    let mut backend_health = None;
    let mut probe_latency = None;

    if listening && loaded_token.is_some() {
        if let Ok(report) = probe_gateway(
            &config.host,
            config.port,
            loaded_token.as_deref().unwrap(),
            Duration::from_millis(1500),
        )
        .await
        {
            auth_probe_ok = report.authenticated;
            server_version = report.server_version;
            capabilities = report.capabilities;
            backend_health = report.backend_health;
            probe_latency = Some(report.latency_ms);
        }
    }

    let gateway_status = GatewayStatus {
        listening,
        authenticated_probe: auth_probe_ok,
        server_version: server_version.clone(),
        capabilities: capabilities.clone(),
        backend_health: backend_health.clone(),
        latency_ms: probe_latency,
    };

    // 5. cmux status
    let cmux_bin = config.cmux_path.clone().unwrap_or_else(|| "cmux".into());
    let backend = LiveCmuxBackend::with_path(&cmux_bin);
    let ping_ok = backend.ping().await.is_ok();

    let cmux_status = CmuxStatus {
        path: cmux_bin.display().to_string(),
        ping_ok,
    };

    // 6. Compute overall status
    let overall_status = if !config_exists || !token_exists {
        "unconfigured".to_string()
    } else if auth_probe_ok && ping_ok {
        "healthy".to_string()
    } else if auth_probe_ok && !ping_ok {
        "degraded (cmux unreachable)".to_string()
    } else if listening && !auth_probe_ok {
        "degraded (auth probe failed)".to_string()
    } else if service_status.registered && launchd_status.pid.is_some() {
        "starting".to_string()
    } else {
        "stopped".to_string()
    };

    let data = StatusData {
        overall_status: overall_status.clone(),
        config: config_status,
        token: token_status,
        service: service_status,
        gateway: gateway_status,
        cmux: cmux_status,
    };

    let prose = format!(
        "cmux-pocket Status: {}\n\
         ========================================\n\
         Configuration:   {} (valid: {})\n\
         Host & Port:     {}:{}\n\
         Token:           {} (permissions valid: {})\n\
         Token FP:        {}\n\
         Launchd Service: {} (registered: {}, PID: {})\n\
         TCP Listener:    {}\n\
         Gateway Probe:   {} (version: {}, latency: {})\n\
         cmux Reachable:  {} ({})\n\
         Capabilities:    {}",
        overall_status.to_uppercase(),
        paths.config_file.display(),
        config_valid,
        config.host,
        config.port,
        token_path.display(),
        token_valid_perms,
        data.token.fingerprint,
        label,
        data.service.registered,
        data.service
            .pid
            .map(|p| p.to_string())
            .unwrap_or_else(|| "none".to_string()),
        if listening {
            "Bound & Listening"
        } else {
            "Not Listening"
        },
        if auth_probe_ok {
            "Authenticated OK"
        } else {
            "Unavailable / Failed"
        },
        server_version.as_deref().unwrap_or("n/a"),
        probe_latency
            .map(|ms| format!("{}ms", ms))
            .unwrap_or_else(|| "n/a".to_string()),
        if ping_ok {
            "Ready (ping OK)"
        } else {
            "Unreachable / Not running"
        },
        cmux_bin.display(),
        if capabilities.is_empty() {
            "none".to_string()
        } else {
            capabilities.join(", ")
        }
    );

    print_success(&data, &prose, json_mode);
    Ok(())
}

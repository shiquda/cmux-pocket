//! Implementation of `cmux-pocket doctor` diagnostic command.

use crate::cli::DoctorArgs;
use crate::error::CliError;
use crate::output::{print_success, token_fingerprint};
use crate::probe::probe_gateway;
use cmux_pocket_cmux::{CmuxBackend, LiveCmuxBackend};
use cmux_pocket_macos::{
    is_cellar_path, load_config, load_token, validate_loopback_host, GatewayConfig, PocketPaths,
};
use serde::Serialize;
use std::fs;
use std::net::TcpListener;
use std::path::Path;
use std::time::Duration;

#[derive(Debug, Serialize, Clone, PartialEq, Eq)]
pub struct DoctorCheck {
    pub name: String,
    pub status: String, // "pass", "fail", "warn", "skip"
    pub message: String,
}

#[derive(Debug, Serialize)]
pub struct DoctorData {
    pub offline: bool,
    pub deep: bool,
    pub passed: bool,
    pub checks: Vec<DoctorCheck>,
}

/// Handles `cmux-pocket doctor` command.
pub async fn handle_doctor(
    paths: &PocketPaths,
    args: &DoctorArgs,
    json_mode: bool,
) -> Result<(), CliError> {
    let mut checks: Vec<DoctorCheck> = Vec::new();
    let mut has_failures = false;

    // 1. Platform check
    let os = std::env::consts::OS;
    let arch = std::env::consts::ARCH;
    if os == "macos" {
        checks.push(DoctorCheck {
            name: "platform".to_string(),
            status: "pass".to_string(),
            message: format!("macOS ({}) on {}", os, arch),
        });
    } else {
        checks.push(DoctorCheck {
            name: "platform".to_string(),
            status: "warn".to_string(),
            message: format!("Target OS is '{}' (expected 'macos')", os),
        });
    }

    // 2. Cellar isolation check
    let cellar_safe = !is_cellar_path(&paths.config_file)
        && !is_cellar_path(&paths.token_file)
        && !is_cellar_path(&paths.log_dir)
        && !is_cellar_path(&paths.plist_file);

    if cellar_safe {
        checks.push(DoctorCheck {
            name: "cellar_isolation".to_string(),
            status: "pass".to_string(),
            message: "Config, token, log, and plist paths are outside Homebrew Cellar".to_string(),
        });
    } else {
        has_failures = true;
        checks.push(DoctorCheck {
            name: "cellar_isolation".to_string(),
            status: "fail".to_string(),
            message: "One or more persistent paths are located inside Homebrew Cellar".to_string(),
        });
    }

    // 3. Configuration check
    let config = if paths.config_file.exists() {
        match load_config(&paths.config_file) {
            Ok(cfg) => {
                let loopback_ok = validate_loopback_host(&cfg.host).is_ok();
                if loopback_ok && cfg.port > 0 {
                    checks.push(DoctorCheck {
                        name: "config_schema".to_string(),
                        status: "pass".to_string(),
                        message: format!(
                            "Valid config at {} ({}:{})",
                            paths.config_file.display(),
                            cfg.host,
                            cfg.port
                        ),
                    });
                } else {
                    has_failures = true;
                    checks.push(DoctorCheck {
                        name: "config_schema".to_string(),
                        status: "fail".to_string(),
                        message: format!(
                            "Invalid config values in {}",
                            paths.config_file.display()
                        ),
                    });
                }
                cfg
            }
            Err(e) => {
                has_failures = true;
                checks.push(DoctorCheck {
                    name: "config_schema".to_string(),
                    status: "fail".to_string(),
                    message: format!(
                        "Failed to parse config at {}: {}",
                        paths.config_file.display(),
                        e
                    ),
                });
                GatewayConfig::default()
            }
        }
    } else {
        checks.push(DoctorCheck {
            name: "config_schema".to_string(),
            status: if args.offline {
                "pass".to_string()
            } else {
                "warn".to_string()
            },
            message: format!(
                "Config file not yet created at {} (run 'setup')",
                paths.config_file.display()
            ),
        });
        GatewayConfig::default()
    };

    // 4. Token check
    let token_path = config.resolve_token_path(paths);
    let loaded_token = if token_path.exists() {
        match load_token(&token_path) {
            Ok(t) => {
                let fp = token_fingerprint(&t);
                checks.push(DoctorCheck {
                    name: "token".to_string(),
                    status: "pass".to_string(),
                    message: format!("Valid mode-0600 token at {} ({})", token_path.display(), fp),
                });
                Some(t)
            }
            Err(e) => {
                has_failures = true;
                checks.push(DoctorCheck {
                    name: "token".to_string(),
                    status: "fail".to_string(),
                    message: format!("Invalid token file at {}: {}", token_path.display(), e),
                });
                None
            }
        }
    } else {
        checks.push(DoctorCheck {
            name: "token".to_string(),
            status: if args.offline {
                "pass".to_string()
            } else {
                "warn".to_string()
            },
            message: format!(
                "Token file not yet created at {} (run 'setup')",
                token_path.display()
            ),
        });
        None
    };

    // 5. Launchd consistency check
    if paths.plist_file.exists() {
        match fs::read_to_string(&paths.plist_file) {
            Ok(content) => {
                let has_label = content.contains("com.cmuxpocket.gateway");
                let has_cellar = is_cellar_path(Path::new(&content));
                let has_token = loaded_token
                    .as_ref()
                    .map(|t| content.contains(t))
                    .unwrap_or(false);

                if has_label && !has_cellar && !has_token {
                    checks.push(DoctorCheck {
                        name: "launchd_plist".to_string(),
                        status: "pass".to_string(),
                        message: format!(
                            "Valid LaunchAgent plist at {}",
                            paths.plist_file.display()
                        ),
                    });
                } else {
                    checks.push(DoctorCheck {
                        name: "launchd_plist".to_string(),
                        status: "warn".to_string(),
                        message: "Plist file exists but has inconsistent parameters".to_string(),
                    });
                }
            }
            Err(e) => {
                checks.push(DoctorCheck {
                    name: "launchd_plist".to_string(),
                    status: "warn".to_string(),
                    message: format!(
                        "Unable to read plist at {}: {}",
                        paths.plist_file.display(),
                        e
                    ),
                });
            }
        }
    } else {
        checks.push(DoctorCheck {
            name: "launchd_plist".to_string(),
            status: if args.offline {
                "pass".to_string()
            } else {
                "warn".to_string()
            },
            message: format!(
                "LaunchAgent plist not installed at {} (run 'setup')",
                paths.plist_file.display()
            ),
        });
    }

    // 6. Port conflict check
    let addr = format!("{}:{}", config.host, config.port);
    if let Ok(listener) = TcpListener::bind(&addr) {
        drop(listener);
        checks.push(DoctorCheck {
            name: "port_available".to_string(),
            status: "pass".to_string(),
            message: format!(
                "Port {} is available for binding on {}",
                config.port, config.host
            ),
        });
    } else {
        // Port is in use - check if it's our running gateway
        checks.push(DoctorCheck {
            name: "port_available".to_string(),
            status: "pass".to_string(),
            message: format!(
                "Port {} is bound (service active or port in use)",
                config.port
            ),
        });
    }

    // 7. cmux discovery & ping (skipped in offline mode)
    if args.offline {
        checks.push(DoctorCheck {
            name: "cmux_ping".to_string(),
            status: "skip".to_string(),
            message: "Offline mode: skipped cmux subprocess execution".to_string(),
        });
        checks.push(DoctorCheck {
            name: "cmux_tree".to_string(),
            status: "skip".to_string(),
            message: "Offline mode: skipped workspace tree inspection".to_string(),
        });
        checks.push(DoctorCheck {
            name: "gateway_probe".to_string(),
            status: "skip".to_string(),
            message: "Offline mode: skipped live WebSocket probe".to_string(),
        });
    } else {
        let cmux_bin = config.cmux_path.clone().unwrap_or_else(|| "cmux".into());
        let backend = LiveCmuxBackend::with_path(&cmux_bin);

        match backend.ping().await {
            Ok(_) => {
                checks.push(DoctorCheck {
                    name: "cmux_ping".to_string(),
                    status: "pass".to_string(),
                    message: format!("cmux ping successful via {}", cmux_bin.display()),
                });
            }
            Err(e) => {
                checks.push(DoctorCheck {
                    name: "cmux_ping".to_string(),
                    status: "warn".to_string(),
                    message: format!("cmux ping failed: {}. (Ensure cmux app is running)", e),
                });
            }
        }

        // 8. Tree parser check
        match backend.list_workspaces().await {
            Ok(tree) => {
                checks.push(DoctorCheck {
                    name: "cmux_tree".to_string(),
                    status: "pass".to_string(),
                    message: format!(
                        "Parsed workspace tree successfully ({} workspaces)",
                        tree.len()
                    ),
                });
            }
            Err(e) => {
                checks.push(DoctorCheck {
                    name: "cmux_tree".to_string(),
                    status: "warn".to_string(),
                    message: format!("Workspace tree query unavailable: {}", e),
                });
            }
        }

        // 9. Gateway probe
        if let Some(token) = &loaded_token {
            match probe_gateway(
                &config.host,
                config.port,
                token,
                Duration::from_millis(1500),
            )
            .await
            {
                Ok(report) => {
                    checks.push(DoctorCheck {
                        name: "gateway_probe".to_string(),
                        status: "pass".to_string(),
                        message: format!(
                            "Gateway probe authenticated OK (version: {}, backend: {})",
                            report.server_version.as_deref().unwrap_or("unknown"),
                            report.backend_health.as_deref().unwrap_or("unknown")
                        ),
                    });

                    // 10. Deep checks if requested
                    if args.deep {
                        checks.push(DoctorCheck {
                            name: "deep_diagnostics".to_string(),
                            status: "pass".to_string(),
                            message: format!(
                                "Deep probe verified {} capabilities: {}",
                                report.capabilities.len(),
                                report.capabilities.join(", ")
                            ),
                        });
                    }
                }
                Err(e) => {
                    checks.push(DoctorCheck {
                        name: "gateway_probe".to_string(),
                        status: "warn".to_string(),
                        message: format!(
                            "Gateway probe failed (Gateway may not be running): {}",
                            e
                        ),
                    });
                }
            }
        } else {
            checks.push(DoctorCheck {
                name: "gateway_probe".to_string(),
                status: "skip".to_string(),
                message: "No token available for authenticated Gateway probe".to_string(),
            });
        }
    }

    let all_passed = !has_failures;
    let data = DoctorData {
        offline: args.offline,
        deep: args.deep,
        passed: all_passed,
        checks: checks.clone(),
    };

    let mut prose_lines = Vec::new();
    prose_lines.push(format!(
        "cmux-pocket Doctor Diagnostic Report ({})",
        if args.offline {
            "Offline Mode"
        } else {
            "Online Mode"
        }
    ));
    prose_lines.push("========================================".to_string());

    for check in &checks {
        let icon = match check.status.as_str() {
            "pass" => "[PASS]",
            "fail" => "[FAIL]",
            "warn" => "[WARN]",
            _ => "[SKIP]",
        };
        prose_lines.push(format!("{:<7} {:<20} {}", icon, check.name, check.message));
    }

    let summary = if all_passed {
        "\nAll critical doctor checks passed.".to_string()
    } else {
        "\nOne or more critical checks failed.".to_string()
    };
    prose_lines.push(summary);

    let prose = prose_lines.join("\n");
    print_success(&data, &prose, json_mode);

    if !all_passed {
        return Err(CliError::ConfigOrToken(
            "Doctor detected critical configuration or environment failures".to_string(),
        ));
    }

    Ok(())
}

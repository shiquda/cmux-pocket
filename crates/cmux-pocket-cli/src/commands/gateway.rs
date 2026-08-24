//! Implementation of `cmux-pocket gateway` subcommands.

use crate::cli::GatewaySubcommand;
use crate::error::CliError;
use crate::output::print_success;
use crate::probe::{probe_gateway, DEFAULT_PROBE_TIMEOUT};
use cmux_pocket_cmux::LiveCmuxBackend;
use cmux_pocket_gateway::CmuxGateway;
use cmux_pocket_macos::{load_config, load_token, validate_loopback_host, PocketPaths};
use serde::Serialize;
use std::sync::Arc;
use tracing::{error, info};

#[derive(Debug, Serialize)]
pub struct GatewayRunInfo {
    pub host: String,
    pub port: u16,
    pub cmux_path: Option<String>,
}

async fn wait_for_shutdown() {
    let ctrl_c = tokio::signal::ctrl_c();
    #[cfg(unix)]
    {
        use tokio::signal::unix::{signal, SignalKind};
        if let Ok(mut sig) = signal(SignalKind::terminate()) {
            tokio::select! {
                _ = ctrl_c => {
                    info!("Received Ctrl+C interrupt signal");
                }
                _ = sig.recv() => {
                    info!("Received SIGTERM signal");
                }
            }
            return;
        }
    }
    let _ = ctrl_c.await;
    info!("Received Ctrl+C interrupt signal");
}
/// Handles `cmux-pocket gateway` subcommands.
pub async fn handle_gateway(
    paths: &PocketPaths,
    subcmd: &GatewaySubcommand,
    json_mode: bool,
) -> Result<(), CliError> {
    match subcmd {
        GatewaySubcommand::Run {
            config: custom_config,
        } => {
            let config_path = custom_config.as_ref().unwrap_or(&paths.config_file);

            if !config_path.exists() {
                return Err(CliError::ConfigOrToken(format!(
                    "Configuration file not found at {}. Run 'cmux-pocket setup' first.",
                    config_path.display()
                )));
            }

            let config = load_config(config_path)?;
            validate_loopback_host(&config.host)?;

            let token_path = config.resolve_token_path(paths);
            if !token_path.exists() {
                return Err(CliError::ConfigOrToken(format!(
                    "Authentication token not found at {}. Run 'cmux-pocket setup' first.",
                    token_path.display()
                )));
            }

            let token = load_token(&token_path)?;

            let backend = match &config.cmux_path {
                Some(p) => Arc::new(LiveCmuxBackend::with_path(p)),
                None => Arc::new(LiveCmuxBackend::new()),
            };

            let gateway = Arc::new(CmuxGateway::new(
                &config.host,
                config.port,
                &token,
                backend,
            )?);

            let listener = match gateway.start().await {
                Ok(l) => l,
                Err(e) => {
                    return Err(CliError::DependencyUnavailable(format!(
                        "Failed to bind Gateway listener on {}:{}: {}",
                        config.host, config.port, e
                    )));
                }
            };

            info!(
                "cmux-pocket Gateway running on ws://{}:{} (config: {})",
                config.host,
                config.port,
                config_path.display()
            );

            println!(
                "cmux-pocket Gateway listening on ws://{}:{}\nPress Ctrl+C to terminate.",
                config.host, config.port
            );

            // Shutdown signal listener
            let shutdown_gateway = gateway.clone();
            let accept_gateway = gateway.clone();
            tokio::select! {
                res = accept_gateway.run_with_listener(listener) => {
                    if let Err(e) = res {
                        error!("Gateway accept loop exited with error: {}", e);
                    }
                }
                _ = wait_for_shutdown() => {
                    info!("Shutdown signal received");
                }
            }

            println!("\nShutting down Gateway...");
            shutdown_gateway.stop().await;
            info!("Gateway shutdown complete");
            Ok(())
        }
        GatewaySubcommand::Probe => {
            let config = if paths.config_file.exists() {
                load_config(&paths.config_file)?
            } else {
                return Err(CliError::ConfigOrToken(format!(
                    "Config file not found at {}. Run 'cmux-pocket setup' first.",
                    paths.config_file.display()
                )));
            };

            let token_path = config.resolve_token_path(paths);
            if !token_path.exists() {
                return Err(CliError::ConfigOrToken(format!(
                    "Token file not found at {}. Run 'cmux-pocket setup' first.",
                    token_path.display()
                )));
            }

            let token = load_token(&token_path)?;

            let report =
                probe_gateway(&config.host, config.port, &token, DEFAULT_PROBE_TIMEOUT).await?;

            let prose = format!(
                "Gateway Probe: OK\nEndpoint: ws://{}:{}\nServer version: {}\nCapabilities: {}\nBackend health: {}\nLatency: {}ms",
                report.host,
                report.port,
                report.server_version.as_deref().unwrap_or("unknown"),
                report.capabilities.join(", "),
                report.backend_health.as_deref().unwrap_or("unknown"),
                report.latency_ms
            );

            print_success(&report, &prose, json_mode);
            Ok(())
        }
    }
}

//! Library interface for the `cmux-pocket` CLI and service manager.

pub mod cli;
pub mod commands;
pub mod error;
pub mod output;
pub mod probe;

pub use cli::{Cli, Commands};
pub use error::{CliError, CliExitCode};
pub use output::{print_error, print_success, token_fingerprint, JsonEnvelope};
pub use probe::{probe_gateway, ProbeReport};

use cmux_pocket_macos::PocketPaths;

/// Returns the crate version string.
pub fn version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

/// Executes a parsed CLI command structure.
pub async fn run_cli(cli: Cli) -> Result<(), CliError> {
    let json_mode = cli.json;

    // Discover or override paths
    let paths = if let Some(custom_cfg) = &cli.config {
        let base = PocketPaths::discover()
            .unwrap_or_else(|_| PocketPaths::from_home_dir(&std::env::temp_dir()));
        base.with_custom_config(custom_cfg)
    } else {
        PocketPaths::discover()?
    };

    match &cli.command {
        Commands::Setup(args) => {
            commands::handle_setup(&paths, args, json_mode).await?;
        }
        Commands::Status(args) => {
            commands::handle_status(&paths, args, json_mode).await?;
        }
        Commands::Doctor(args) => {
            commands::handle_doctor(&paths, args, json_mode).await?;
        }
        Commands::Config(args) => {
            commands::handle_config(&paths, &args.command, json_mode).await?;
        }
        Commands::Token(args) => {
            commands::handle_token(&paths, &args.command, json_mode).await?;
        }
        Commands::Service(args) => {
            commands::handle_service(&paths, &args.command, json_mode).await?;
        }
        Commands::Logs(args) => {
            commands::handle_logs(&paths, args, json_mode).await?;
        }
        Commands::Gateway(args) => {
            commands::handle_gateway(&paths, &args.command, json_mode).await?;
        }
    }

    Ok(())
}

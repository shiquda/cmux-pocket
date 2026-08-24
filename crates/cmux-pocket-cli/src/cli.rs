//! Clap CLI definitions for cmux-pocket.

use clap::{Args, Parser, Subcommand};
use std::path::PathBuf;

/// cmux-pocket - Android companion gateway and macOS service manager for cmux.
#[derive(Parser, Debug, Clone)]
#[command(
    name = "cmux-pocket",
    about = "cmux-pocket Gateway and launchd service manager",
    version = env!("CARGO_PKG_VERSION"),
    arg_required_else_help = true
)]
pub struct Cli {
    /// Custom path to config.toml file
    #[arg(long, global = true, value_name = "PATH")]
    pub config: Option<PathBuf>,

    /// Emit formatted JSON output for automation
    #[arg(long, global = true)]
    pub json: bool,

    /// Enable verbose / debug logging
    #[arg(short = 'v', long, global = true)]
    pub verbose: bool,

    #[command(subcommand)]
    pub command: Commands,
}

#[derive(Subcommand, Debug, Clone, PartialEq, Eq)]
pub enum Commands {
    /// Setup cmux-pocket configuration, token, launchd service, and verify connectivity
    Setup(SetupArgs),

    /// Show overall health and status of config, token, service, gateway, and cmux
    Status(StatusArgs),

    /// Side-effect-free diagnostic checks for platform, config, token, cmux, and launchd
    Doctor(DoctorArgs),

    /// Manage Gateway configuration
    Config(ConfigArgs),

    /// Manage Gateway authentication token
    Token(TokenArgs),

    /// Manage launchd background service
    Service(ServiceArgs),

    /// View or follow Gateway application logs
    Logs(LogsArgs),

    /// Gateway runtime and health probe
    Gateway(GatewayArgs),
}

/// Arguments for `cmux-pocket setup`.
#[derive(Args, Debug, Clone, PartialEq, Eq)]
pub struct SetupArgs {
    /// Custom TCP port to bind (default: 8088)
    #[arg(long, value_name = "PORT")]
    pub port: Option<u16>,

    /// Do not start or bootstrap the launchd service after configuration
    #[arg(long)]
    pub no_start: bool,
}

/// Arguments for `cmux-pocket status`.
#[derive(Args, Debug, Clone, PartialEq, Eq, Default)]
pub struct StatusArgs {}

/// Arguments for `cmux-pocket doctor`.
#[derive(Args, Debug, Clone, PartialEq, Eq, Default)]
pub struct DoctorArgs {
    /// Run offline checks only without connecting to cmux or gateway (formula-test safe)
    #[arg(long)]
    pub offline: bool,

    /// Run deep diagnostic checks including replay probe
    #[arg(long)]
    pub deep: bool,
}

/// Subcommands for `cmux-pocket config`.
#[derive(Args, Debug, Clone, PartialEq, Eq)]
pub struct ConfigArgs {
    #[command(subcommand)]
    pub command: ConfigSubcommand,
}

#[derive(Subcommand, Debug, Clone, PartialEq, Eq)]
pub enum ConfigSubcommand {
    /// Print absolute path to configuration file
    Path,
    /// Display current configuration (secrets redacted)
    Show,
    /// Read a specific configuration value
    Get {
        /// Configuration key (e.g. host, port, token_path, log_dir, cmux_path)
        key: String,
    },
    /// Update a specific configuration value atomically
    Set {
        /// Configuration key (e.g. host, port, token_path, log_dir, cmux_path)
        key: String,
        /// New value
        value: String,
    },
}

/// Subcommands for `cmux-pocket token`.
#[derive(Args, Debug, Clone, PartialEq, Eq)]
pub struct TokenArgs {
    #[command(subcommand)]
    pub command: TokenSubcommand,
}

#[derive(Subcommand, Debug, Clone, PartialEq, Eq)]
pub enum TokenSubcommand {
    /// Print path to authentication token file
    Path,
    /// Display token metadata and fingerprint (never prints the raw secret)
    Show,
    /// Atomically generate a new token, reload service, and state Android update requirement
    Rotate,
}

/// Subcommands for `cmux-pocket service`.
#[derive(Args, Debug, Clone, PartialEq, Eq)]
pub struct ServiceArgs {
    #[command(subcommand)]
    pub command: ServiceSubcommand,
}

#[derive(Subcommand, Debug, Clone, PartialEq, Eq)]
pub enum ServiceSubcommand {
    /// Generate launchd plist, write atomically, and bootstrap service
    Install,
    /// Bootout and remove CLI-owned launchd service plist
    Uninstall,
    /// Start or bootstrap launchd service
    Start,
    /// Stop or bootout launchd service
    Stop,
    /// Restart launchd service
    Restart,
    /// Inspect launchd registration, PID, version, and health
    Status,
}

/// Arguments for `cmux-pocket logs`.
#[derive(Args, Debug, Clone, PartialEq, Eq)]
pub struct LogsArgs {
    /// Continuously follow the log stream
    #[arg(short = 'f', long)]
    pub follow: bool,

    /// Number of lines to output from each log file
    #[arg(short = 'n', long, default_value = "50")]
    pub lines: usize,
}

/// Subcommands for `cmux-pocket gateway`.
#[derive(Args, Debug, Clone, PartialEq, Eq)]
pub struct GatewayArgs {
    #[command(subcommand)]
    pub command: GatewaySubcommand,
}

#[derive(Subcommand, Debug, Clone, PartialEq, Eq)]
pub enum GatewaySubcommand {
    /// Run the Gateway server in foreground for debugging
    Run {
        /// Explicit path to config.toml
        #[arg(long, value_name = "PATH")]
        config: Option<PathBuf>,
    },
    /// Perform a read-only authenticated probe against running Gateway
    Probe,
}

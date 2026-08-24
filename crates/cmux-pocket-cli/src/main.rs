//! Main entry point for the `cmux-pocket` CLI binary.

use clap::error::ErrorKind;
use clap::Parser;
use cmux_pocket_cli::error::CliExitCode;
use cmux_pocket_cli::output::{print_error, JsonEnvelope};
use cmux_pocket_cli::{run_cli, Cli};

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    let json_requested = args.iter().any(|a| a == "--json");
    let verbose_requested = args.iter().any(|a| a == "-v" || a == "--verbose");

    // Initialize tracing subscriber
    let filter = if verbose_requested {
        "cmux_pocket=debug,cmux_pocket_gateway=debug,cmux_pocket_cmux=debug,cmux_pocket_macos=debug"
    } else {
        "cmux_pocket=info,cmux_pocket_gateway=info"
    };

    let _ = tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new(filter)),
        )
        .with_target(false)
        .with_writer(std::io::stderr)
        .try_init();

    // Parse command line arguments
    let cli = match Cli::try_parse() {
        Ok(c) => c,
        Err(e) => match e.kind() {
            ErrorKind::DisplayHelp | ErrorKind::DisplayVersion => {
                print!("{}", e);
                std::process::exit(0);
            }
            _ => {
                if json_requested {
                    let envelope = JsonEnvelope::error(
                        CliExitCode::InvalidUsage,
                        format!("Invalid argument: {}", e),
                    );
                    if let Ok(json_str) = serde_json::to_string_pretty(&envelope) {
                        println!("{}", json_str);
                    } else {
                        println!("{{\"ok\":false,\"code\":2,\"message\":\"{}\"}}", e);
                    }
                } else {
                    eprint!("{}", e);
                }
                std::process::exit(CliExitCode::InvalidUsage.as_i32());
            }
        },
    };

    let json_mode = cli.json;

    // Run the parsed CLI command
    if let Err(err) = run_cli(cli).await {
        print_error(&err, json_mode);
        std::process::exit(err.exit_code().as_i32());
    }
}

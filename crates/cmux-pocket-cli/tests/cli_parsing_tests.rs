use clap::Parser;
use cmux_pocket_cli::cli::{
    Cli, Commands, ConfigArgs, ConfigSubcommand, DoctorArgs, GatewayArgs, GatewaySubcommand,
    LogsArgs, QrArgs, ServiceArgs, ServiceSubcommand, SetupArgs, StatusArgs, TokenArgs,
    TokenSubcommand,
};
use std::path::PathBuf;

#[test]
fn test_parse_global_flags() {
    let cli = Cli::try_parse_from([
        "cmux-pocket",
        "--config",
        "/custom/config.toml",
        "--json",
        "-v",
        "status",
    ])
    .expect("Should parse globals successfully");

    assert_eq!(cli.config, Some(PathBuf::from("/custom/config.toml")));
    assert!(cli.json);
    assert!(cli.verbose);
    assert_eq!(cli.command, Commands::Status(StatusArgs {}));
}

#[test]
fn test_parse_setup() {
    // Default setup
    let cli = Cli::try_parse_from(["cmux-pocket", "setup"]).unwrap();
    assert_eq!(
        cli.command,
        Commands::Setup(SetupArgs {
            port: None,
            no_start: false,
            tailscale: false,
            qr: false,
            endpoint: None,
            invert: false,
        })
    );

    // Setup with port and no-start
    let cli =
        Cli::try_parse_from(["cmux-pocket", "setup", "--port", "9090", "--no-start"]).unwrap();
    assert_eq!(
        cli.command,
        Commands::Setup(SetupArgs {
            port: Some(9090),
            no_start: true,
            tailscale: false,
            qr: false,
            endpoint: None,
            invert: false,
        })
    );
}

#[test]
fn test_tailscale_setup_requires_gateway_verification() {
    let cli = Cli::try_parse_from(["cmux-pocket", "setup", "--tailscale"]).unwrap();
    assert!(matches!(
        cli.command,
        Commands::Setup(SetupArgs {
            tailscale: true,
            ..
        })
    ));
    assert!(Cli::try_parse_from(["cmux-pocket", "setup", "--tailscale", "--no-start"]).is_err());
}

#[test]
fn test_parse_qr() {
    // Default qr
    let cli = Cli::try_parse_from(["cmux-pocket", "qr"]).unwrap();
    assert_eq!(
        cli.command,
        Commands::Qr(QrArgs {
            endpoint: None,
            url_only: false,
            name: None,
            invert: false,
        })
    );

    // Alias qrcode
    let cli = Cli::try_parse_from(["cmux-pocket", "qrcode"]).unwrap();
    assert_eq!(
        cli.command,
        Commands::Qr(QrArgs {
            endpoint: None,
            url_only: false,
            name: None,
            invert: false,
        })
    );

    // Full qr options
    let cli = Cli::try_parse_from([
        "cmux-pocket",
        "qr",
        "--endpoint",
        "wss://my.tailnet.ts.net",
        "--url-only",
        "--name",
        "My Workstation",
    ])
    .unwrap();
    assert_eq!(
        cli.command,
        Commands::Qr(QrArgs {
            endpoint: Some("wss://my.tailnet.ts.net".to_string()),
            url_only: true,
            name: Some("My Workstation".to_string()),
            invert: false,
        })
    );

    // Setup with --qr and --endpoint
    let cli = Cli::try_parse_from([
        "cmux-pocket",
        "setup",
        "--qr",
        "--endpoint",
        "wss://custom.net",
    ])
    .unwrap();
    assert_eq!(
        cli.command,
        Commands::Setup(SetupArgs {
            port: None,
            no_start: false,
            tailscale: false,
            qr: true,
            endpoint: Some("wss://custom.net".to_string()),
            invert: false,
        })
    );
}

#[test]
fn test_parse_doctor() {
    let cli = Cli::try_parse_from(["cmux-pocket", "doctor"]).unwrap();
    assert_eq!(
        cli.command,
        Commands::Doctor(DoctorArgs {
            offline: false,
            deep: false,
        })
    );

    let cli = Cli::try_parse_from(["cmux-pocket", "doctor", "--offline", "--deep"]).unwrap();
    assert_eq!(
        cli.command,
        Commands::Doctor(DoctorArgs {
            offline: true,
            deep: true,
        })
    );
}

#[test]
fn test_parse_config() {
    let cli = Cli::try_parse_from(["cmux-pocket", "config", "path"]).unwrap();
    assert_eq!(
        cli.command,
        Commands::Config(ConfigArgs {
            command: ConfigSubcommand::Path,
        })
    );

    let cli = Cli::try_parse_from(["cmux-pocket", "config", "show"]).unwrap();
    assert_eq!(
        cli.command,
        Commands::Config(ConfigArgs {
            command: ConfigSubcommand::Show,
        })
    );

    let cli = Cli::try_parse_from(["cmux-pocket", "config", "get", "port"]).unwrap();
    assert_eq!(
        cli.command,
        Commands::Config(ConfigArgs {
            command: ConfigSubcommand::Get {
                key: "port".to_string(),
            },
        })
    );

    let cli = Cli::try_parse_from(["cmux-pocket", "config", "set", "port", "8089"]).unwrap();
    assert_eq!(
        cli.command,
        Commands::Config(ConfigArgs {
            command: ConfigSubcommand::Set {
                key: "port".to_string(),
                value: "8089".to_string(),
            },
        })
    );
}

#[test]
fn test_parse_token() {
    let cli = Cli::try_parse_from(["cmux-pocket", "token", "path"]).unwrap();
    assert_eq!(
        cli.command,
        Commands::Token(TokenArgs {
            command: TokenSubcommand::Path,
        })
    );

    let cli = Cli::try_parse_from(["cmux-pocket", "token", "show"]).unwrap();
    assert_eq!(
        cli.command,
        Commands::Token(TokenArgs {
            command: TokenSubcommand::Show,
        })
    );

    let cli = Cli::try_parse_from(["cmux-pocket", "token", "rotate"]).unwrap();
    assert_eq!(
        cli.command,
        Commands::Token(TokenArgs {
            command: TokenSubcommand::Rotate,
        })
    );
}

#[test]
fn test_parse_service() {
    let subcommands = [
        ("install", ServiceSubcommand::Install),
        ("uninstall", ServiceSubcommand::Uninstall),
        ("start", ServiceSubcommand::Start),
        ("stop", ServiceSubcommand::Stop),
        ("restart", ServiceSubcommand::Restart),
        ("status", ServiceSubcommand::Status),
    ];

    for (name, expected) in subcommands {
        let cli = Cli::try_parse_from(["cmux-pocket", "service", name]).unwrap();
        assert_eq!(
            cli.command,
            Commands::Service(ServiceArgs { command: expected })
        );
    }
}

#[test]
fn test_parse_logs() {
    let cli = Cli::try_parse_from(["cmux-pocket", "logs"]).unwrap();
    assert_eq!(
        cli.command,
        Commands::Logs(LogsArgs {
            follow: false,
            lines: 50,
        })
    );

    let cli = Cli::try_parse_from(["cmux-pocket", "logs", "-f", "-n", "100"]).unwrap();
    assert_eq!(
        cli.command,
        Commands::Logs(LogsArgs {
            follow: true,
            lines: 100,
        })
    );
}

#[test]
fn test_parse_gateway() {
    let cli = Cli::try_parse_from(["cmux-pocket", "gateway", "run"]).unwrap();
    assert_eq!(
        cli.command,
        Commands::Gateway(GatewayArgs {
            command: GatewaySubcommand::Run { config: None },
        })
    );

    let cli = Cli::try_parse_from([
        "cmux-pocket",
        "gateway",
        "run",
        "--config",
        "/path/config.toml",
    ])
    .unwrap();
    assert_eq!(
        cli.command,
        Commands::Gateway(GatewayArgs {
            command: GatewaySubcommand::Run {
                config: Some(PathBuf::from("/path/config.toml")),
            },
        })
    );

    let cli = Cli::try_parse_from(["cmux-pocket", "gateway", "probe"]).unwrap();
    assert_eq!(
        cli.command,
        Commands::Gateway(GatewayArgs {
            command: GatewaySubcommand::Probe,
        })
    );
}

#[test]
fn test_parse_invalid_args() {
    assert!(Cli::try_parse_from(["cmux-pocket", "nonexistent-subcommand"]).is_err());
    assert!(Cli::try_parse_from(["cmux-pocket", "config", "invalid-op"]).is_err());
    assert!(Cli::try_parse_from(["cmux-pocket", "setup", "--invalid-flag"]).is_err());
}

//! Implementation of `cmux-pocket endpoint` subcommands.

use crate::cli::EndpointSubcommand;
use crate::error::CliError;
use crate::output::print_success;
use cmux_pocket_macos::{
    create_dir_user_only, load_config, save_config, validate_remote_endpoint, GatewayConfig,
    NamedEndpoint, PocketPaths,
};
use serde::Serialize;

#[derive(Debug, Clone, Serialize)]
pub struct EndpointEntry {
    pub name: String,
    pub url: String,
    /// `default` for the legacy single endpoint, `named` for saved entries.
    pub kind: String,
}

#[derive(Debug, Serialize)]
pub struct EndpointListData {
    pub config_path: String,
    pub endpoints: Vec<EndpointEntry>,
}

#[derive(Debug, Serialize)]
pub struct EndpointMutationData {
    pub name: String,
    pub url: String,
    pub config_path: String,
}

fn load_or_default(paths: &PocketPaths) -> Result<GatewayConfig, CliError> {
    if paths.config_file.exists() {
        Ok(load_config(&paths.config_file)?)
    } else {
        Ok(GatewayConfig::default())
    }
}

/// Handles `cmux-pocket endpoint` operations.
pub async fn handle_endpoint(
    paths: &PocketPaths,
    subcmd: &EndpointSubcommand,
    json_mode: bool,
) -> Result<(), CliError> {
    match subcmd {
        EndpointSubcommand::List => {
            let config = load_or_default(paths)?;
            let mut entries = Vec::new();
            if let Some(default) = &config.endpoint {
                if let Ok(parsed) = validate_remote_endpoint(default) {
                    entries.push(EndpointEntry {
                        name: "(default)".to_string(),
                        url: parsed.raw,
                        kind: "default".to_string(),
                    });
                }
            }
            for entry in &config.endpoints {
                if let Ok(parsed) = validate_remote_endpoint(&entry.url) {
                    entries.push(EndpointEntry {
                        name: entry.name.clone(),
                        url: parsed.raw,
                        kind: "named".to_string(),
                    });
                }
            }

            let data = EndpointListData {
                config_path: paths.config_file.display().to_string(),
                endpoints: entries.clone(),
            };
            let mut prose = format!("Saved phone endpoints ({})\n", paths.config_file.display());
            if entries.is_empty() {
                prose.push_str(
                    "  (none)\n\nAdd one with: cmux-pocket endpoint add wss://gateway.example.com --name LAN\n",
                );
            } else {
                for entry in &entries {
                    prose.push_str(&format!(
                        "  {:<20} {:<44} [{}]\n",
                        entry.name, entry.url, entry.kind
                    ));
                }
                prose.push_str("\nRun `cmux-pocket qr` to pick one interactively.\n");
            }
            print_success(&data, &prose, json_mode);
            Ok(())
        }
        EndpointSubcommand::Add { url, name } => {
            let name = name.trim();
            if name.is_empty() {
                return Err(CliError::InvalidUsage(
                    "Endpoint name must not be empty".to_string(),
                ));
            }
            let parsed = validate_remote_endpoint(url.trim())
                .map_err(|e| CliError::InvalidUsage(format!("Invalid endpoint URL: {e}")))?;
            let mut config = load_or_default(paths)?;
            if config
                .endpoints
                .iter()
                .any(|entry| entry.name.eq_ignore_ascii_case(name))
            {
                return Err(CliError::InvalidUsage(format!(
                    "Endpoint name '{name}' already exists; remove it first or choose another name"
                )));
            }
            config
                .endpoints
                .push(NamedEndpoint::new(name, parsed.raw.clone()));
            config.validate()?;

            create_dir_user_only(&paths.config_dir)?;
            save_config(&paths.config_file, &config)?;

            let data = EndpointMutationData {
                name: name.to_string(),
                url: parsed.raw.clone(),
                config_path: paths.config_file.display().to_string(),
            };
            let prose = format!(
                "Added endpoint '{name}' -> {}\nRun `cmux-pocket qr` to select it.\n",
                parsed.raw
            );
            print_success(&data, &prose, json_mode);
            Ok(())
        }
        EndpointSubcommand::Remove { name } => {
            let mut config = load_or_default(paths)?;
            let index = config
                .endpoints
                .iter()
                .position(|entry| entry.name == *name)
                .or_else(|| {
                    config
                        .endpoints
                        .iter()
                        .position(|entry| entry.name.eq_ignore_ascii_case(name))
                })
                .ok_or_else(|| {
                    CliError::ConfigOrToken(format!("Endpoint name '{name}' not found"))
                })?;
            let removed = config.endpoints.remove(index);
            save_config(&paths.config_file, &config)?;

            let data = EndpointMutationData {
                name: removed.name.clone(),
                url: removed.url.clone(),
                config_path: paths.config_file.display().to_string(),
            };
            let prose = format!("Removed endpoint '{}' ({})\n", removed.name, removed.url);
            print_success(&data, &prose, json_mode);
            Ok(())
        }
    }
}

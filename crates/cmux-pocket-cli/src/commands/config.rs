//! Implementation of `cmux-pocket config` subcommands.

use crate::cli::ConfigSubcommand;
use crate::error::CliError;
use crate::output::print_success;
use cmux_pocket_macos::{
    load_config, save_config, validate_loopback_host, validate_outside_cellar, GatewayConfig,
    PocketPaths,
};
use serde::Serialize;
use std::path::Path;
#[derive(Debug, Serialize)]
pub struct ConfigPathData {
    pub path: String,
}

#[derive(Debug, Serialize)]
pub struct ConfigGetData {
    pub key: String,
    pub value: String,
}

#[derive(Debug, Serialize)]
pub struct ConfigSetData {
    pub key: String,
    pub value: String,
    pub path: String,
}

/// Handles `cmux-pocket config` operations.
pub async fn handle_config(
    paths: &PocketPaths,
    subcmd: &ConfigSubcommand,
    json_mode: bool,
) -> Result<(), CliError> {
    match subcmd {
        ConfigSubcommand::Path => {
            let path_str = paths.config_file.display().to_string();
            let data = ConfigPathData {
                path: path_str.clone(),
            };
            print_success(&data, &path_str, json_mode);
            Ok(())
        }
        ConfigSubcommand::Show => {
            let config = if paths.config_file.exists() {
                load_config(&paths.config_file)?
            } else {
                GatewayConfig::default()
            };

            let toml_str = config.to_redacted_toml_string()?;
            if json_mode {
                let val = serde_json::to_value(&config)?;
                print_success(&val, &toml_str, true);
            } else {
                println!("{}", toml_str.trim());
            }
            Ok(())
        }
        ConfigSubcommand::Get { key } => {
            let config = if paths.config_file.exists() {
                load_config(&paths.config_file)?
            } else {
                GatewayConfig::default()
            };

            let val_str = match key.as_str() {
                "host" => config.host.clone(),
                "port" => config.port.to_string(),
                "token_path" => config.resolve_token_path(paths).display().to_string(),
                "log_dir" => config.resolve_log_dir(paths).display().to_string(),
                "cmux_path" => config
                    .cmux_path
                    .as_ref()
                    .map(|p| p.display().to_string())
                    .unwrap_or_else(|| "cmux".to_string()),
                _ => {
                    if let Some(v) = config.extra.get(key) {
                        v.to_string()
                    } else {
                        return Err(CliError::ConfigOrToken(format!(
                            "Configuration key '{}' not found",
                            key
                        )));
                    }
                }
            };

            let data = ConfigGetData {
                key: key.clone(),
                value: val_str.clone(),
            };
            print_success(&data, &val_str, json_mode);
            Ok(())
        }
        ConfigSubcommand::Set { key, value } => {
            let mut config = if paths.config_file.exists() {
                load_config(&paths.config_file)?
            } else {
                GatewayConfig::default()
            };

            match key.as_str() {
                "host" => {
                    validate_loopback_host(value)?;
                    config.host = value.clone();
                }
                "port" => {
                    let p: u16 = value.parse().map_err(|_| {
                        CliError::InvalidUsage(format!(
                            "Invalid port number '{}': must be between 1 and 65535",
                            value
                        ))
                    })?;
                    if p == 0 {
                        return Err(CliError::InvalidUsage("Port 0 is not allowed".to_string()));
                    }
                    config.port = p;
                }
                "token_path" => {
                    let path = Path::new(value);
                    validate_outside_cellar(path, "token_path")?;
                    config.token_path = Some(path.to_path_buf());
                }
                "log_dir" => {
                    let path = Path::new(value);
                    validate_outside_cellar(path, "log_dir")?;
                    config.log_dir = Some(path.to_path_buf());
                }
                "cmux_path" => {
                    let path = Path::new(value);
                    validate_outside_cellar(path, "cmux_path")?;
                    config.cmux_path = Some(path.to_path_buf());
                }
                _ => {
                    // Try parsing as boolean, integer, or default to string
                    let toml_val = if let Ok(b) = value.parse::<bool>() {
                        toml::Value::Boolean(b)
                    } else if let Ok(i) = value.parse::<i64>() {
                        toml::Value::Integer(i)
                    } else {
                        toml::Value::String(value.clone())
                    };
                    config.extra.insert(key.clone(), toml_val);
                }
            }

            // Ensure parent directory exists before saving
            if let Some(parent) = paths.config_file.parent() {
                cmux_pocket_macos::create_dir_user_only(parent)?;
            }

            save_config(&paths.config_file, &config)?;

            let data = ConfigSetData {
                key: key.clone(),
                value: value.clone(),
                path: paths.config_file.display().to_string(),
            };
            let prose = format!("Set {} = {} in {}", key, value, paths.config_file.display());
            print_success(&data, &prose, json_mode);
            Ok(())
        }
    }
}

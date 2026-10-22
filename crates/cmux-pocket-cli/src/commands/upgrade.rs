//! Implementation of `cmux-pocket upgrade`.
//!
//! Compares the installed build with the latest GitHub Release, delegates the
//! upgrade to whichever package manager owns the binary, and restarts the
//! registered Gateway LaunchAgent so the phone reaches the new build at once.

use crate::cli::UpgradeArgs;
use crate::commands::service::restart_service;
use crate::error::CliError;
use crate::output::print_success;
use crate::probe::probe_gateway;
use crate::upgrade::{self, InstallSource, UpgradeCommand};
use crate::version::{normalize_tag, parse_version};
use cmux_pocket_macos::{load_config, load_token, PocketPaths, DEFAULT_LAUNCHD_LABEL};
use serde::Serialize;
use std::cmp::Ordering;
use std::path::{Path, PathBuf};
use std::time::Duration;
use tokio::process::Command;
use tokio::time::sleep;

const PROBE_ATTEMPTS: usize = 10;
const PROBE_BACKOFF: Duration = Duration::from_millis(300);
const UPDATE_HINT: &str =
    "Open the Android app, go to Settings → Connection, and tap Recheck version.";

#[derive(Debug, Serialize)]
pub struct ServiceUpdate {
    pub label: String,
    pub restarted: bool,
    pub detail: String,
}

#[derive(Debug, Serialize)]
pub struct UpgradeData {
    pub current_version: String,
    pub latest_version: String,
    pub latest_tag: String,
    pub update_available: bool,
    pub check_only: bool,
    pub install_source: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub install_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub command: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub installed_version: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub service: Option<ServiceUpdate>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub gateway_version: Option<String>,
    pub next_steps: String,
}

/// Handles `cmux-pocket upgrade`.
pub async fn handle_upgrade(
    paths: &PocketPaths,
    args: &UpgradeArgs,
    json_mode: bool,
) -> Result<(), CliError> {
    let current = crate::version().to_string();
    let latest_tag = upgrade::latest_release_tag().await?;
    let latest = normalize_tag(&latest_tag).to_string();

    let ordering = match (parse_version(&current), parse_version(&latest)) {
        (Some(current), Some(latest)) => current.cmp(&latest),
        _ => {
            return Err(CliError::RuntimeFailure(format!(
                "Cannot compare installed version '{current}' with release tag '{latest_tag}'"
            )));
        }
    };

    let source = upgrade::current_install_source().await;
    let planned = upgrade::plan_upgrade(&source, &latest_tag);
    let install_path = source
        .managed_binary()
        .map(|path| path.display().to_string());
    let source_label = source_description(&source);

    if ordering != Ordering::Less {
        let same = ordering == Ordering::Equal;
        let prose = format!(
            "cmux-pocket Upgrade\n\
             ====================\n\
             Current: {current}\n\
             Latest:  {latest} ({latest_tag})\n\
             Source:  {source_label}\n\
             Result:  {}\n",
            if same {
                "Already on the latest release."
            } else {
                "Installed build is newer than the latest release."
            }
        );
        let data = UpgradeData {
            current_version: current,
            latest_version: latest,
            latest_tag,
            update_available: false,
            check_only: args.check,
            install_source: source.label().to_string(),
            install_path,
            command: None,
            installed_version: None,
            service: None,
            gateway_version: None,
            next_steps: UPDATE_HINT.to_string(),
        };
        print_success(&data, &prose, json_mode);
        return Ok(());
    }

    if args.check {
        let mut prose = format!(
            "cmux-pocket Upgrade\n\
             ====================\n\
             Current: {current}\n\
             Latest:  {latest} ({latest_tag})\n\
             Source:  {source_label}\n\
             Result:  Update available ({current} → {latest}). No changes made.\n"
        );
        match &planned {
            Some(command) => prose.push_str(&format!("Upgrade: {}\n", command.display())),
            None => {
                prose.push_str("Upgrade manually with either:\n");
                for command in upgrade::manual_commands(&latest_tag) {
                    prose.push_str(&format!("  {}\n", command.display()));
                }
            }
        }
        let data = UpgradeData {
            current_version: current,
            latest_version: latest,
            latest_tag,
            update_available: true,
            check_only: true,
            install_source: source.label().to_string(),
            install_path,
            command: planned.as_ref().map(UpgradeCommand::display),
            installed_version: None,
            service: None,
            gateway_version: None,
            next_steps: UPDATE_HINT.to_string(),
        };
        print_success(&data, &prose, json_mode);
        return Ok(());
    }

    let Some(command) = planned else {
        let manual: Vec<String> = upgrade::manual_commands(&latest_tag)
            .iter()
            .map(|command| format!("  {}", command.display()))
            .collect();
        return Err(CliError::DependencyUnavailable(format!(
            "Cannot tell how this cmux-pocket was installed, so it was not modified. \
             Upgrade with either of these commands and rerun the upgrade:\n{}",
            manual.join("\n")
        )));
    };

    run_upgrade_command(&command, json_mode).await?;

    // Verify the package manager actually linked the release we asked for.
    let installed_version = match &install_path {
        Some(path) => upgrade::installed_version(Path::new(path)).await,
        None => None,
    };
    if let Some(installed) = installed_version.as_deref() {
        if normalize_tag(installed) != latest {
            return Err(CliError::RuntimeFailure(format!(
                "'{}' still reports version {installed} after the upgrade; expected {latest} ({latest_tag}). \
                 Refresh the package manager metadata (for Homebrew: brew update) and retry.",
                install_path.as_deref().unwrap_or("cmux-pocket")
            )));
        }
    }

    let service = restart_managed_service(paths, source.managed_binary().as_deref()).await?;
    let gateway_version = probe_gateway_version(paths).await;

    let mut prose = format!(
        "cmux-pocket Upgrade\n\
         ====================\n\
         Current: {current}\n\
         Latest:  {latest} ({latest_tag})\n\
         Source:  {source_label}\n\
         Command: {}\n\
         Result:  Upgraded {current} → {}\n\
         Service: {} · {}\n",
        command.display(),
        installed_version.as_deref().unwrap_or(&latest),
        service.detail,
        match gateway_version.as_deref() {
            Some(version) => format!("Gateway build {version}"),
            None => "Gateway build not reported".to_string(),
        }
    );
    if let Some(path) = &install_path {
        if let Some(program) = service_program(paths) {
            if !same_binary(&program, Path::new(path))
                && gateway_version.as_deref().map(normalize_tag) != Some(latest.as_str())
            {
                prose.push_str(&format!(
                    "Warning: the LaunchAgent runs {}. Run '{} service install' from the upgraded binary \
                     to point it at {path}.\n",
                    program.display(),
                    path
                ));
            }
        }
    }
    prose.push_str(&format!("\nNext: {UPDATE_HINT}\n"));

    let data = UpgradeData {
        current_version: current,
        latest_version: latest,
        latest_tag,
        update_available: true,
        check_only: false,
        install_source: source.label().to_string(),
        install_path,
        command: Some(command.display()),
        installed_version,
        service: Some(service),
        gateway_version,
        next_steps: UPDATE_HINT.to_string(),
    };
    print_success(&data, &prose, json_mode);
    Ok(())
}

fn source_description(source: &InstallSource) -> String {
    match source {
        InstallSource::Homebrew { prefix } => {
            format!("Homebrew ({})", prefix.display())
        }
        InstallSource::Cargo { bin_dir } => format!("cargo install ({})", bin_dir.display()),
        InstallSource::Unknown => "unrecognized (not Homebrew or cargo)".to_string(),
    }
}

/// Runs the package-manager upgrade.
///
/// Human mode inherits the terminal so progress and prompts stay visible; JSON mode
/// captures the output and folds its tail into the error when the command fails.
async fn run_upgrade_command(command: &UpgradeCommand, json_mode: bool) -> Result<(), CliError> {
    let mut process = Command::new(&command.program);
    process.args(&command.args);

    let output = if json_mode {
        process
            .output()
            .await
            .map_err(|e| upgrade_spawn_error(command, e))?
    } else {
        let status = process
            .status()
            .await
            .map_err(|e| upgrade_spawn_error(command, e))?;
        return if status.success() {
            Ok(())
        } else {
            Err(CliError::RuntimeFailure(format!(
                "'{}' failed ({status})",
                command.display()
            )))
        };
    };

    if output.status.success() {
        return Ok(());
    }

    let mut tail: Vec<String> = String::from_utf8_lossy(&output.stderr)
        .lines()
        .chain(String::from_utf8_lossy(&output.stdout).lines())
        .filter(|line| !line.trim().is_empty())
        .map(|line| line.trim().to_string())
        .collect();
    if tail.len() > 5 {
        tail = tail.split_off(tail.len() - 5);
    }
    Err(CliError::RuntimeFailure(format!(
        "'{}' failed ({}): {}",
        command.display(),
        output.status,
        tail.join(" | ")
    )))
}

fn upgrade_spawn_error(command: &UpgradeCommand, error: std::io::Error) -> CliError {
    CliError::DependencyUnavailable(format!(
        "Cannot run '{}': {error}. Install it or upgrade manually.",
        command.program
    ))
}

/// Restarts the registered LaunchAgent, reporting when there is nothing to restart.
async fn restart_managed_service(
    paths: &PocketPaths,
    managed_binary: Option<&Path>,
) -> Result<ServiceUpdate, CliError> {
    let skipped = |detail: &str| ServiceUpdate {
        label: DEFAULT_LAUNCHD_LABEL.to_string(),
        restarted: false,
        detail: detail.to_string(),
    };

    if !paths.plist_file.exists() {
        return Ok(skipped("no LaunchAgent registered; nothing to restart"));
    }

    if let (Some(program), Some(managed)) = (service_program(paths), managed_binary) {
        if !same_binary(&program, managed) {
            return Ok(ServiceUpdate {
                label: DEFAULT_LAUNCHD_LABEL.to_string(),
                restarted: false,
                detail: format!(
                    "LaunchAgent runs {}, not the upgraded binary; run '{} service install' to repoint it",
                    program.display(),
                    managed.display()
                ),
            });
        }
    }

    restart_service()?;
    Ok(ServiceUpdate {
        label: DEFAULT_LAUNCHD_LABEL.to_string(),
        restarted: true,
        detail: format!("{DEFAULT_LAUNCHD_LABEL} restarted"),
    })
}

/// Reads the executable a saved plist launches, so a service pinned to another
/// install is reported instead of silently restarted.
fn service_program(paths: &PocketPaths) -> Option<PathBuf> {
    let plist = std::fs::read_to_string(&paths.plist_file).ok()?;
    program_argument(&plist).map(PathBuf::from)
}

fn program_argument(plist: &str) -> Option<String> {
    let key = plist.find("<key>ProgramArguments</key>")?;
    let after_key = &plist[key..];
    let open = after_key.find("<string>")? + "<string>".len();
    let close = after_key[open..].find("</string>")? + open;
    Some(unescape_xml(&after_key[open..close]))
}

fn unescape_xml(value: &str) -> String {
    value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
}

fn same_binary(left: &Path, right: &Path) -> bool {
    match (left.canonicalize(), right.canonicalize()) {
        (Ok(left), Ok(right)) => left == right,
        _ => left == right,
    }
}

/// Reads the Gateway build the restarted service reports, with a short backoff for launchd.
async fn probe_gateway_version(paths: &PocketPaths) -> Option<String> {
    if !paths.config_file.exists() {
        return None;
    }
    let config = load_config(&paths.config_file).ok()?;
    let token_path = config.resolve_token_path(paths);
    let token = load_token(&token_path).ok()?;

    for attempt in 0..PROBE_ATTEMPTS {
        if attempt > 0 {
            sleep(PROBE_BACKOFF).await;
        }
        let Ok(report) =
            probe_gateway(&config.host, config.port, &token, Duration::from_secs(2)).await
        else {
            continue;
        };
        if let Some(version) = report
            .host_status
            .as_ref()
            .and_then(|status| status.get("gateway_version"))
            .and_then(serde_json::Value::as_str)
        {
            return Some(version.to_string());
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_the_launchd_program_from_a_generated_plist() {
        let plist = "<dict>\n    <key>Label</key>\n    <string>com.cmuxpocket.gateway</string>\n    <key>ProgramArguments</key>\n    <array>\n        <string>/opt/homebrew/opt/cmux-pocket/bin/cmux-pocket</string>\n        <string>gateway</string>\n    </array>\n</dict>";
        assert_eq!(
            program_argument(plist),
            Some("/opt/homebrew/opt/cmux-pocket/bin/cmux-pocket".to_string())
        );
        assert_eq!(program_argument("<dict></dict>"), None);
    }

    #[cfg(unix)]
    #[test]
    fn compares_installs_by_the_binary_they_resolve_to() {
        let temp = tempfile::tempdir().unwrap();
        let keg = temp.path().join("Cellar/cmux-pocket/0.3.0/bin/cmux-pocket");
        std::fs::create_dir_all(keg.parent().unwrap()).unwrap();
        std::fs::write(&keg, b"").unwrap();
        let linked = temp.path().join("bin/cmux-pocket");
        std::fs::create_dir_all(linked.parent().unwrap()).unwrap();
        std::os::unix::fs::symlink(&keg, &linked).unwrap();

        assert!(same_binary(&linked, &keg));
        assert!(!same_binary(&linked, &temp.path().join("bin/other")));
    }
}

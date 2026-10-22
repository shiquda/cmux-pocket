//! Latest-release lookup, install-source detection, and upgrade planning.
//!
//! `cmux-pocket upgrade` never overwrites the binary itself: it identifies which
//! package manager owns the running executable and delegates the upgrade to it.
//! Anything it cannot attribute is reported instead of guessed.

use crate::error::CliError;
use std::path::{Path, PathBuf};
use std::time::Duration;
use tokio::process::Command;
use tokio::time::timeout;

/// Redirect target that resolves the newest published GitHub Release without the API.
pub const RELEASES_LATEST_URL: &str = "https://github.com/shiquda/cmux-pocket/releases/latest";
/// Fully qualified Homebrew formula.
pub const HOMEBREW_FORMULA: &str = "shiquda/cmux-pocket/cmux-pocket";
/// Cargo package name of the CLI binary.
pub const CARGO_PACKAGE: &str = "cmux-pocket-cli";
/// Repository the CLI is installed from with `cargo install --git`.
pub const REPOSITORY_URL: &str = "https://github.com/shiquda/cmux-pocket";
/// Binary name the package managers install.
pub const BINARY_NAME: &str = "cmux-pocket";

const RELEASE_LOOKUP_TIMEOUT: Duration = Duration::from_secs(20);
const PACKAGE_MANAGER_QUERY_TIMEOUT: Duration = Duration::from_secs(10);

/// Where the running binary came from.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum InstallSource {
    /// Installed by Homebrew under the given prefix (`/opt/homebrew`).
    Homebrew { prefix: PathBuf },
    /// Installed by `cargo install` into the given bin directory.
    Cargo { bin_dir: PathBuf },
    /// Not attributable to a supported package manager.
    Unknown,
}

impl InstallSource {
    /// Stable machine-readable label for JSON output.
    pub fn label(&self) -> &'static str {
        match self {
            InstallSource::Homebrew { .. } => "homebrew",
            InstallSource::Cargo { .. } => "cargo",
            InstallSource::Unknown => "unknown",
        }
    }

    /// Path of the binary the package manager will have installed after an upgrade.
    ///
    /// Package managers own a stable path (`<prefix>/bin`, `~/.cargo/bin`) that survives
    /// the versioned Homebrew Cellar directory being replaced.
    pub fn managed_binary(&self) -> Option<PathBuf> {
        match self {
            InstallSource::Homebrew { prefix } => Some(prefix.join("bin").join(BINARY_NAME)),
            InstallSource::Cargo { bin_dir } => Some(bin_dir.join(BINARY_NAME)),
            InstallSource::Unknown => None,
        }
    }
}

/// A package-manager invocation that upgrades the CLI.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UpgradeCommand {
    pub program: String,
    pub args: Vec<String>,
}

impl UpgradeCommand {
    /// Single-line command suitable for prose, JSON, and copy-to-terminal.
    pub fn display(&self) -> String {
        std::iter::once(self.program.as_str())
            .chain(self.args.iter().map(String::as_str))
            .collect::<Vec<_>>()
            .join(" ")
    }
}

/// Extracts the tag from a `releases/latest` redirect
/// (`https://github.com/shiquda/cmux-pocket/releases/tag/v0.3.0`).
pub fn tag_from_release_url(url: &str) -> Option<String> {
    let trimmed = url.trim().trim_end_matches('/');
    let tag = trimmed.rsplit_once("/releases/tag/")?.1;
    if tag.is_empty() || tag.contains('/') || tag.contains('?') || tag.contains('#') {
        return None;
    }
    Some(tag.to_string())
}

/// Resolves the newest published release tag.
///
/// Uses the `releases/latest` redirect through the system `curl` rather than the
/// GitHub API: the API answers `403` once the shared unauthenticated quota is spent,
/// and this workspace deliberately carries no TLS stack for a single request.
pub async fn latest_release_tag() -> Result<String, CliError> {
    let output = timeout(
        RELEASE_LOOKUP_TIMEOUT,
        Command::new("curl")
            .args([
                "-sS",
                "--max-time",
                "15",
                "-o",
                "/dev/null",
                "-w",
                "%{redirect_url}",
                RELEASES_LATEST_URL,
            ])
            .kill_on_drop(true)
            .output(),
    )
    .await
    .map_err(|_| {
        CliError::DependencyUnavailable(
            "Timed out looking up the latest release on github.com".to_string(),
        )
    })?
    .map_err(|e| {
        CliError::DependencyUnavailable(format!(
            "Cannot run curl to look up the latest release: {e}. Install curl or check the release page manually."
        ))
    })?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(CliError::DependencyUnavailable(format!(
            "Failed to look up the latest release: {}",
            stderr.trim()
        )));
    }

    let redirect = String::from_utf8_lossy(&output.stdout);
    tag_from_release_url(&redirect).ok_or_else(|| {
        CliError::DependencyUnavailable(format!(
            "github.com did not redirect to a release tag (response: '{}')",
            redirect.trim()
        ))
    })
}

/// Classifies an executable path against known package-manager layouts.
///
/// Pure: callers pass the prefixes to consider, so the decision stays testable
/// and independent of the host's environment.
pub fn classify_install_source(
    executable: &Path,
    homebrew_prefixes: &[PathBuf],
    cargo_bin_dir: &Path,
) -> InstallSource {
    for prefix in homebrew_prefixes {
        if executable.starts_with(prefix.join("Cellar"))
            || executable.starts_with(prefix.join("opt"))
            || executable.parent() == Some(prefix.join("bin").as_path())
        {
            return InstallSource::Homebrew {
                prefix: prefix.clone(),
            };
        }
    }
    if executable.starts_with(cargo_bin_dir) {
        return InstallSource::Cargo {
            bin_dir: cargo_bin_dir.to_path_buf(),
        };
    }
    InstallSource::Unknown
}

/// Detects the package manager that owns the running executable.
///
/// Path layout decides first (Cellar / `opt` / `<prefix>/bin` / `CARGO_HOME/bin`);
/// only when that fails does it ask Homebrew, which also covers a binary that was
/// moved off its install path.
pub async fn current_install_source() -> InstallSource {
    let executable = std::env::current_exe()
        .ok()
        .map(|path| path.canonicalize().unwrap_or(path));

    let cargo_bin_dir = cargo_bin_dir();
    let homebrew_prefixes = homebrew_prefixes();

    if let Some(executable) = executable.as_deref() {
        let source = classify_install_source(executable, &homebrew_prefixes, &cargo_bin_dir);
        if source != InstallSource::Unknown {
            return source;
        }
    }

    if let Some(prefix) = homebrew_formula_prefix(&homebrew_prefixes).await {
        return InstallSource::Homebrew { prefix };
    }

    InstallSource::Unknown
}

/// Upgrade command for a detected source, or `None` when the source is unknown.
pub fn plan_upgrade(source: &InstallSource, tag: &str) -> Option<UpgradeCommand> {
    match source {
        InstallSource::Homebrew { .. } => Some(UpgradeCommand {
            program: "brew".to_string(),
            args: vec!["upgrade".to_string(), HOMEBREW_FORMULA.to_string()],
        }),
        InstallSource::Cargo { .. } => Some(UpgradeCommand {
            program: "cargo".to_string(),
            args: vec![
                "install".to_string(),
                "--force".to_string(),
                "--git".to_string(),
                REPOSITORY_URL.to_string(),
                "--tag".to_string(),
                tag.to_string(),
                CARGO_PACKAGE.to_string(),
            ],
        }),
        InstallSource::Unknown => None,
    }
}

/// The manual upgrades printed when the install source cannot be identified.
pub fn manual_commands(tag: &str) -> Vec<UpgradeCommand> {
    vec![
        UpgradeCommand {
            program: "brew".to_string(),
            args: vec!["upgrade".to_string(), HOMEBREW_FORMULA.to_string()],
        },
        UpgradeCommand {
            program: "cargo".to_string(),
            args: vec![
                "install".to_string(),
                "--force".to_string(),
                "--git".to_string(),
                REPOSITORY_URL.to_string(),
                "--tag".to_string(),
                tag.to_string(),
                CARGO_PACKAGE.to_string(),
            ],
        },
    ]
}

/// Reads the version an installed binary reports, for post-upgrade verification.
pub async fn installed_version(binary: &Path) -> Option<String> {
    let output = timeout(
        PACKAGE_MANAGER_QUERY_TIMEOUT,
        Command::new(binary)
            .arg("--version")
            .kill_on_drop(true)
            .output(),
    )
    .await
    .ok()?
    .ok()?;
    if !output.status.success() {
        return None;
    }
    let stdout = String::from_utf8_lossy(&output.stdout);
    stdout
        .split_whitespace()
        .last()
        .map(|value| value.trim().to_string())
}

fn cargo_bin_dir() -> PathBuf {
    if let Some(home) = std::env::var_os("CARGO_HOME") {
        return PathBuf::from(home).join("bin");
    }
    let home = std::env::var_os("HOME")
        .map(PathBuf::from)
        .unwrap_or_default();
    home.join(".cargo").join("bin")
}

fn homebrew_prefixes() -> Vec<PathBuf> {
    let mut prefixes: Vec<PathBuf> = Vec::new();
    if let Some(prefix) = std::env::var_os("HOMEBREW_PREFIX") {
        prefixes.push(PathBuf::from(prefix));
    }
    for candidate in ["/opt/homebrew", "/usr/local"] {
        let path = PathBuf::from(candidate);
        if path.is_dir() {
            prefixes.push(path);
        }
    }
    prefixes.dedup();
    prefixes
}

/// Asks Homebrew whether it owns the formula, returning its prefix.
async fn homebrew_formula_prefix(prefixes: &[PathBuf]) -> Option<PathBuf> {
    let output = timeout(
        PACKAGE_MANAGER_QUERY_TIMEOUT,
        Command::new("brew")
            .args(["list", "--versions", BINARY_NAME])
            .kill_on_drop(true)
            .output(),
    )
    .await
    .ok()?
    .ok()?;
    if !output.status.success() || String::from_utf8_lossy(&output.stdout).trim().is_empty() {
        return None;
    }

    let reported = timeout(
        PACKAGE_MANAGER_QUERY_TIMEOUT,
        Command::new("brew")
            .args(["--prefix"])
            .kill_on_drop(true)
            .output(),
    )
    .await
    .ok()?
    .ok()?;
    let prefix = String::from_utf8_lossy(&reported.stdout).trim().to_string();
    if !reported.status.success() || prefix.is_empty() {
        return prefixes.first().cloned();
    }
    Some(PathBuf::from(prefix))
}

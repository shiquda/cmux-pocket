//! macOS standard paths and Cellar validation.
//!
//! Provides deterministic path resolution for configuration, authentication token,
//! application logs, and launchd plist files. Enforces that config and token paths
//! remain outside the Homebrew Cellar across upgrades and reinstalls.

use crate::error::MacOsError;
use std::path::{Component, Path, PathBuf};

/// Standard launchd label for the cmux-pocket service.
pub const DEFAULT_LAUNCHD_LABEL: &str = "com.cmuxpocket.gateway";

/// Standard launchd plist filename.
pub const DEFAULT_PLIST_FILENAME: &str = "com.cmuxpocket.gateway.plist";

/// Standard config filename.
pub const DEFAULT_CONFIG_FILENAME: &str = "config.toml";

/// Standard token filename.
pub const DEFAULT_TOKEN_FILENAME: &str = "gateway-token";

/// Standard application directory name under `~/Library/Application Support` and `~/Library/Logs`.
pub const APP_DIR_NAME: &str = "cmux-pocket";

/// Structured container for resolved macOS paths.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PocketPaths {
    /// Directory for configuration and token files (`~/Library/Application Support/cmux-pocket`).
    pub config_dir: PathBuf,
    /// Path to `config.toml`.
    pub config_file: PathBuf,
    /// Path to `gateway-token`.
    pub token_file: PathBuf,
    /// Directory for application logs (`~/Library/Logs/cmux-pocket`).
    pub log_dir: PathBuf,
    /// Path to stdout log file (`~/Library/Logs/cmux-pocket/gateway.stdout.log`).
    pub stdout_log: PathBuf,
    /// Path to stderr log file (`~/Library/Logs/cmux-pocket/gateway.stderr.log`).
    pub stderr_log: PathBuf,
    /// Directory for user LaunchAgents (`~/Library/LaunchAgents`).
    pub launch_agents_dir: PathBuf,
    /// Path to launchd plist (`~/Library/LaunchAgents/com.cmuxpocket.gateway.plist`).
    pub plist_file: PathBuf,
}

impl PocketPaths {
    /// Resolves default paths rooted in the user's home directory.
    pub fn from_home_dir(home_dir: &Path) -> Self {
        let app_support = home_dir.join("Library").join("Application Support");
        let config_dir = app_support.join(APP_DIR_NAME);
        let config_file = config_dir.join(DEFAULT_CONFIG_FILENAME);
        let token_file = config_dir.join(DEFAULT_TOKEN_FILENAME);

        let log_dir = home_dir.join("Library").join("Logs").join(APP_DIR_NAME);
        let stdout_log = log_dir.join("gateway.stdout.log");
        let stderr_log = log_dir.join("gateway.stderr.log");

        let launch_agents_dir = home_dir.join("Library").join("LaunchAgents");
        let plist_file = launch_agents_dir.join(DEFAULT_PLIST_FILENAME);

        Self {
            config_dir,
            config_file,
            token_file,
            log_dir,
            stdout_log,
            stderr_log,
            launch_agents_dir,
            plist_file,
        }
    }

    /// Discovers paths by checking the `HOME` environment variable.
    pub fn discover() -> Result<Self, MacOsError> {
        let home = std::env::var_os("HOME")
            .map(PathBuf::from)
            .ok_or(MacOsError::HomeDirNotFound)?;
        Ok(Self::from_home_dir(&home))
    }

    /// Derives paths with a custom config file override.
    ///
    /// If custom config path is provided, token path defaults to the same parent directory
    /// unless explicitly overridden elsewhere.
    pub fn with_custom_config(&self, custom_config: &Path) -> Self {
        let mut paths = self.clone();
        paths.config_file = custom_config.to_path_buf();
        if let Some(parent) = custom_config.parent() {
            paths.config_dir = parent.to_path_buf();
            paths.token_file = parent.join(DEFAULT_TOKEN_FILENAME);
        }
        paths
    }
}

fn path_components_include_cellar(path: &Path) -> bool {
    path.components()
        .any(|component| matches!(component, Component::Normal(value) if value == "Cellar"))
}

/// Checks whether the supplied path explicitly names a Homebrew Cellar path.
/// Homebrew `opt` and `bin` symlinks intentionally remain non-Cellar paths here;
/// persistent-path validation additionally resolves them before accepting them.
pub fn is_cellar_path(path: &Path) -> bool {
    path_components_include_cellar(path)
}

/// Validates that a path is strictly outside the Homebrew Cellar.
///
/// Returns `Ok(())` if safe, or `Err(MacOsError::CellarPathForbidden)` if inside Cellar.
pub fn validate_outside_cellar(path: &Path, name: &str) -> Result<(), MacOsError> {
    let resolves_into_cellar = path
        .canonicalize()
        .map(|canonical| path_components_include_cellar(&canonical))
        .unwrap_or(false);
    if is_cellar_path(path) || resolves_into_cellar {
        Err(MacOsError::CellarPathForbidden {
            name: name.to_string(),
            path: path.to_path_buf(),
        })
    } else {
        Ok(())
    }
}

/// Resolves a stable executable path for Homebrew installations.
///
/// If the executable is detected inside a versioned Cellar path (e.g.
/// `/opt/homebrew/Cellar/cmux-pocket/0.1.0/bin/cmux-pocket`), it transforms it into
/// the persistent Homebrew opt symlink (`/opt/homebrew/opt/cmux-pocket/bin/cmux-pocket`),
/// which survives formula upgrades without invalidating launchd plists.
pub fn resolve_stable_executable_path(current_exe: &Path) -> PathBuf {
    let path_str = current_exe.to_string_lossy();

    // Check Apple Silicon / standard arm64 Homebrew
    if path_str.starts_with("/opt/homebrew/Cellar/") {
        let parts: Vec<&str> = path_str.split('/').collect();
        // Parts: ["", "opt", "homebrew", "Cellar", "<formula>", "<version>", "bin", "<exe>"]
        if parts.len() >= 8 && parts[6] == "bin" {
            let formula = parts[4];
            let exe_name = parts[7];
            return PathBuf::from(format!("/opt/homebrew/opt/{formula}/bin/{exe_name}"));
        }
    }

    // Check Intel / legacy Homebrew
    if path_str.starts_with("/usr/local/Cellar/") {
        let parts: Vec<&str> = path_str.split('/').collect();
        // Parts: ["", "usr", "local", "Cellar", "<formula>", "<version>", "bin", "<exe>"]
        if parts.len() >= 8 && parts[6] == "bin" {
            let formula = parts[4];
            let exe_name = parts[7];
            return PathBuf::from(format!("/usr/local/opt/{formula}/bin/{exe_name}"));
        }
    }

    if path_str == "/opt/homebrew/bin/cmux-pocket" {
        return PathBuf::from("/opt/homebrew/opt/cmux-pocket/bin/cmux-pocket");
    }
    if path_str == "/usr/local/bin/cmux-pocket" {
        return PathBuf::from("/usr/local/opt/cmux-pocket/bin/cmux-pocket");
    }

    current_exe.to_path_buf()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_path_derivation_from_home() {
        let home = Path::new("/Users/testuser");
        let paths = PocketPaths::from_home_dir(home);

        assert_eq!(
            paths.config_dir,
            PathBuf::from("/Users/testuser/Library/Application Support/cmux-pocket")
        );
        assert_eq!(
            paths.config_file,
            PathBuf::from("/Users/testuser/Library/Application Support/cmux-pocket/config.toml")
        );
        assert_eq!(
            paths.token_file,
            PathBuf::from("/Users/testuser/Library/Application Support/cmux-pocket/gateway-token")
        );
        assert_eq!(
            paths.log_dir,
            PathBuf::from("/Users/testuser/Library/Logs/cmux-pocket")
        );
        assert_eq!(
            paths.stdout_log,
            PathBuf::from("/Users/testuser/Library/Logs/cmux-pocket/gateway.stdout.log")
        );
        assert_eq!(
            paths.stderr_log,
            PathBuf::from("/Users/testuser/Library/Logs/cmux-pocket/gateway.stderr.log")
        );
        assert_eq!(
            paths.launch_agents_dir,
            PathBuf::from("/Users/testuser/Library/LaunchAgents")
        );
        assert_eq!(
            paths.plist_file,
            PathBuf::from("/Users/testuser/Library/LaunchAgents/com.cmuxpocket.gateway.plist")
        );
    }

    #[test]
    fn test_path_derivation_with_custom_config() {
        let home = Path::new("/Users/testuser");
        let base = PocketPaths::from_home_dir(home);

        let custom = Path::new("/custom/dir/my-config.toml");
        let derived = base.with_custom_config(custom);

        assert_eq!(
            derived.config_file,
            PathBuf::from("/custom/dir/my-config.toml")
        );
        assert_eq!(derived.config_dir, PathBuf::from("/custom/dir"));
        assert_eq!(
            derived.token_file,
            PathBuf::from("/custom/dir/gateway-token")
        );
        // Logs and plist remain standard
        assert_eq!(derived.log_dir, base.log_dir);
        assert_eq!(derived.plist_file, base.plist_file);
    }

    #[test]
    fn test_is_cellar_path() {
        assert!(is_cellar_path(Path::new(
            "/opt/homebrew/Cellar/cmux-pocket/0.1.0/config.toml"
        )));
        assert!(is_cellar_path(Path::new(
            "/usr/local/Cellar/cmux-pocket/0.1.0/bin/cmux-pocket"
        )));
        assert!(is_cellar_path(Path::new("/home/user/Cellar/foo")));

        assert!(!is_cellar_path(Path::new(
            "/Users/test/Library/Application Support/cmux-pocket/config.toml"
        )));
        assert!(!is_cellar_path(Path::new(
            "/opt/homebrew/opt/cmux-pocket/bin/cmux-pocket"
        )));
        assert!(!is_cellar_path(Path::new("/opt/homebrew/bin/cmux-pocket")));
        assert!(!is_cellar_path(Path::new("/usr/local/bin/cmux-pocket")));
    }

    #[test]
    fn test_validate_outside_cellar() {
        let valid_path =
            Path::new("/Users/test/Library/Application Support/cmux-pocket/config.toml");
        assert!(validate_outside_cellar(valid_path, "config").is_ok());

        let invalid_path = Path::new("/opt/homebrew/Cellar/cmux-pocket/0.1.0/config.toml");
        match validate_outside_cellar(invalid_path, "config") {
            Err(MacOsError::CellarPathForbidden { name, path }) => {
                assert_eq!(name, "config");
                assert_eq!(path, invalid_path);
            }
            other => panic!("Expected CellarPathForbidden, got: {other:?}"),
        }
    }

    #[test]
    fn test_resolve_stable_executable_path() {
        let cellar_arm = Path::new("/opt/homebrew/Cellar/cmux-pocket/0.1.0/bin/cmux-pocket");
        let stable_arm = resolve_stable_executable_path(cellar_arm);
        assert_eq!(
            stable_arm,
            PathBuf::from("/opt/homebrew/opt/cmux-pocket/bin/cmux-pocket")
        );

        let cellar_intel = Path::new("/usr/local/Cellar/cmux-pocket/1.2.3/bin/cmux-pocket");
        let stable_intel = resolve_stable_executable_path(cellar_intel);
        assert_eq!(
            stable_intel,
            PathBuf::from("/usr/local/opt/cmux-pocket/bin/cmux-pocket")
        );

        let standard_bin = Path::new("/opt/homebrew/bin/cmux-pocket");
        assert_eq!(
            resolve_stable_executable_path(standard_bin),
            PathBuf::from("/opt/homebrew/opt/cmux-pocket/bin/cmux-pocket")
        );

        let local_dev = Path::new("/Users/jim/repo/cmux-pocket-public/target/debug/cmux-pocket");
        assert_eq!(resolve_stable_executable_path(local_dev), local_dev);
    }
}

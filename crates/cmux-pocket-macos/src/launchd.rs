//! macOS launchd plist generation, path validation, and command construction.
//!
//! Exposes typed, deterministic functions for generating launchd agent plists and
//! constructing `launchctl` commands (e.g. `bootstrap`, `bootout`, `kickstart`, `print`).
//! Ensures token values are never included in plists or commands, and executable paths
//! use persistent non-Cellar paths.

use crate::command::CommandSpec;
use crate::error::MacOsError;
use crate::paths::{
    is_cellar_path, resolve_stable_executable_path, validate_outside_cellar, DEFAULT_LAUNCHD_LABEL,
};
use crate::permissions::atomic_write_file;
use std::collections::BTreeMap;
use std::fmt;
use std::path::{Path, PathBuf};

/// Mode for plist files (`0o644` / `rw-r--r--`).
pub const PLIST_FILE_MODE: u32 = 0o644;

/// Specification for generating a launchd LaunchAgent plist.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LaunchdPlistConfig {
    /// Service label (e.g. `com.cmuxpocket.gateway`).
    pub label: String,
    /// Absolute path to `cmux-pocket` executable.
    pub executable_path: PathBuf,
    /// Absolute path to `config.toml`.
    pub config_path: PathBuf,
    /// Absolute path to stdout log file.
    pub stdout_path: PathBuf,
    /// Absolute path to stderr log file.
    pub stderr_path: PathBuf,
    /// Whether to start the service automatically at user login.
    pub run_at_load: bool,
    /// Whether launchd should keep the service running/restart upon crash.
    pub keep_alive: bool,
    /// Process type (default: `"Standard"`).
    pub process_type: String,
    /// Optional working directory.
    pub working_directory: Option<PathBuf>,
    /// Environment variables for the daemon process.
    pub environment_variables: BTreeMap<String, String>,
}

impl LaunchdPlistConfig {
    /// Creates a default `LaunchdPlistConfig` with specified executable and config paths.
    pub fn new(
        executable_path: impl Into<PathBuf>,
        config_path: impl Into<PathBuf>,
        log_dir: &Path,
    ) -> Self {
        let raw_exe = executable_path.into();
        let stable_exe = resolve_stable_executable_path(&raw_exe);
        let config = config_path.into();

        Self {
            label: DEFAULT_LAUNCHD_LABEL.to_string(),
            executable_path: stable_exe,
            config_path: config,
            stdout_path: log_dir.join("gateway.stdout.log"),
            stderr_path: log_dir.join("gateway.stderr.log"),
            run_at_load: true,
            keep_alive: true,
            process_type: "Standard".to_string(),
            working_directory: None,
            environment_variables: BTreeMap::new(),
        }
    }

    fn is_stable_homebrew_opt_executable(path: &Path) -> bool {
        let value = path.to_string_lossy();
        value.starts_with("/opt/homebrew/opt/")
            || value.starts_with("/usr/local/opt/")
            || value.starts_with("/opt/homebrew/bin/")
            || value.starts_with("/usr/local/bin/")
    }

    /// Validates that all paths are absolute and strictly outside the Cellar.
    /// The Homebrew `opt` executable is the intentional stable launchd target;
    /// its symlink may resolve into `Cellar`, but the plist must retain the opt path.
    pub fn validate(&self) -> Result<(), MacOsError> {
        if self.label.trim().is_empty() {
            return Err(MacOsError::Plist(
                "Launchd label cannot be empty".to_string(),
            ));
        }

        if !self.executable_path.is_absolute() {
            return Err(MacOsError::Plist(format!(
                "Executable path must be absolute: {:?}",
                self.executable_path
            )));
        }

        if is_cellar_path(&self.executable_path)
            && !Self::is_stable_homebrew_opt_executable(&self.executable_path)
        {
            return Err(MacOsError::CellarPathForbidden {
                name: "launchd executable".to_string(),
                path: self.executable_path.clone(),
            });
        }

        validate_outside_cellar(&self.config_path, "launchd config")?;

        Ok(())
    }
}

/// Generates an XML launchd plist string from configuration.
///
/// Ensures valid XML escaping, standard formatting, and strict absence of tokens.
pub fn generate_launchd_plist(config: &LaunchdPlistConfig) -> Result<String, MacOsError> {
    config.validate()?;

    let exe_str = escape_xml(&config.executable_path.to_string_lossy());
    let config_str = escape_xml(&config.config_path.to_string_lossy());
    let stdout_str = escape_xml(&config.stdout_path.to_string_lossy());
    let stderr_str = escape_xml(&config.stderr_path.to_string_lossy());
    let label_str = escape_xml(&config.label);

    let mut xml = String::with_capacity(1024);
    xml.push_str("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
    xml.push_str("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n");
    xml.push_str("<plist version=\"1.0\">\n");
    xml.push_str("<dict>\n");

    // Label
    xml.push_str("    <key>Label</key>\n");
    xml.push_str(&format!("    <string>{label_str}</string>\n"));

    // ProgramArguments: ["/path/to/cmux-pocket", "gateway", "run", "--config", "/path/to/config.toml"]
    xml.push_str("    <key>ProgramArguments</key>\n");
    xml.push_str("    <array>\n");
    xml.push_str(&format!("        <string>{exe_str}</string>\n"));
    xml.push_str("        <string>gateway</string>\n");
    xml.push_str("        <string>run</string>\n");
    xml.push_str("        <string>--config</string>\n");
    xml.push_str(&format!("        <string>{config_str}</string>\n"));
    xml.push_str("    </array>\n");

    // RunAtLoad
    xml.push_str("    <key>RunAtLoad</key>\n");
    if config.run_at_load {
        xml.push_str("    <true/>\n");
    } else {
        xml.push_str("    <false/>\n");
    }

    // KeepAlive
    xml.push_str("    <key>KeepAlive</key>\n");
    if config.keep_alive {
        xml.push_str("    <true/>\n");
    } else {
        xml.push_str("    <false/>\n");
    }

    // ProcessType
    xml.push_str("    <key>ProcessType</key>\n");
    xml.push_str(&format!(
        "    <string>{}</string>\n",
        escape_xml(&config.process_type)
    ));

    // StandardOutPath & StandardErrorPath
    xml.push_str("    <key>StandardOutPath</key>\n");
    xml.push_str(&format!("    <string>{stdout_str}</string>\n"));

    xml.push_str("    <key>StandardErrorPath</key>\n");
    xml.push_str(&format!("    <string>{stderr_str}</string>\n"));

    // WorkingDirectory (optional)
    if let Some(wd) = &config.working_directory {
        let wd_str = escape_xml(&wd.to_string_lossy());
        xml.push_str("    <key>WorkingDirectory</key>\n");
        xml.push_str(&format!("    <string>{wd_str}</string>\n"));
    }

    // EnvironmentVariables (optional)
    if !config.environment_variables.is_empty() {
        xml.push_str("    <key>EnvironmentVariables</key>\n");
        xml.push_str("    <dict>\n");
        for (k, v) in &config.environment_variables {
            xml.push_str(&format!("        <key>{}</key>\n", escape_xml(k)));
            xml.push_str(&format!("        <string>{}</string>\n", escape_xml(v)));
        }
        xml.push_str("    </dict>\n");
    }

    xml.push_str("</dict>\n");
    xml.push_str("</plist>\n");

    Ok(xml)
}

/// Atomically writes a generated launchd plist file.
pub fn save_launchd_plist(plist_path: &Path, content: &str) -> Result<(), MacOsError> {
    validate_outside_cellar(plist_path, "plist_path")?;
    atomic_write_file(plist_path, content.as_bytes(), PLIST_FILE_MODE)
}

/// Target domain for macOS launchctl subcommands.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LaunchdTarget {
    /// Modern GUI domain for current user (`gui/<uid>`).
    Gui(u32),
    /// User domain for current user (`user/<uid>`).
    User(u32),
    /// System domain (`system`).
    System,
    /// Custom target string.
    Custom(String),
}

impl LaunchdTarget {
    /// Resolves target to domain prefix string (e.g. `"gui/501"`).
    pub fn domain_string(&self) -> String {
        match self {
            LaunchdTarget::Gui(uid) => format!("gui/{uid}"),
            LaunchdTarget::User(uid) => format!("user/{uid}"),
            LaunchdTarget::System => "system".to_string(),
            LaunchdTarget::Custom(c) => c.clone(),
        }
    }

    /// Resolves full target specifier for a service label (e.g. `"gui/501/com.cmuxpocket.gateway"`).
    pub fn service_target(&self, label: &str) -> String {
        format!("{}/{label}", self.domain_string())
    }

    /// Automatically detects target for the current process user ID.
    pub fn current_gui() -> Self {
        let uid = get_current_uid();
        LaunchdTarget::Gui(uid)
    }
}

impl fmt::Display for LaunchdTarget {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.domain_string())
    }
}

/// Returns the current process effective user ID.
pub fn get_current_uid() -> u32 {
    extern "C" {
        fn geteuid() -> u32;
    }
    unsafe { geteuid() }
}

/// Constructs a `launchctl bootstrap <domain> <plist_path>` command.
pub fn launchctl_bootstrap_cmd(target: &LaunchdTarget, plist_path: &Path) -> CommandSpec {
    CommandSpec::new("launchctl")
        .arg("bootstrap")
        .arg(target.domain_string())
        .arg(plist_path.to_string_lossy().to_string())
}

/// Constructs a `launchctl bootout <domain>/<label>` command.
pub fn launchctl_bootout_service_cmd(target: &LaunchdTarget, label: &str) -> CommandSpec {
    CommandSpec::new("launchctl")
        .arg("bootout")
        .arg(target.service_target(label))
}

/// Constructs a `launchctl bootout <domain> <plist_path>` command.
pub fn launchctl_bootout_plist_cmd(target: &LaunchdTarget, plist_path: &Path) -> CommandSpec {
    CommandSpec::new("launchctl")
        .arg("bootout")
        .arg(target.domain_string())
        .arg(plist_path.to_string_lossy().to_string())
}

/// Constructs a `launchctl kickstart [-k] <domain>/<label>` command.
pub fn launchctl_kickstart_cmd(
    target: &LaunchdTarget,
    label: &str,
    kill_existing: bool,
) -> CommandSpec {
    let mut spec = CommandSpec::new("launchctl").arg("kickstart");
    if kill_existing {
        spec = spec.arg("-k");
    }
    spec.arg(target.service_target(label))
}

/// Constructs a `launchctl print <domain>/<label>` command.
pub fn launchctl_print_service_cmd(target: &LaunchdTarget, label: &str) -> CommandSpec {
    CommandSpec::new("launchctl")
        .arg("print")
        .arg(target.service_target(label))
}

/// Constructs a `launchctl enable <domain>/<label>` command.
pub fn launchctl_enable_service_cmd(target: &LaunchdTarget, label: &str) -> CommandSpec {
    CommandSpec::new("launchctl")
        .arg("enable")
        .arg(target.service_target(label))
}

/// Constructs a `launchctl disable <domain>/<label>` command.
pub fn launchctl_disable_service_cmd(target: &LaunchdTarget, label: &str) -> CommandSpec {
    CommandSpec::new("launchctl")
        .arg("disable")
        .arg(target.service_target(label))
}

/// Status parsed from `launchctl print` output.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LaunchdServiceStatus {
    /// Whether the job is registered in launchd.
    pub registered: bool,
    /// Current execution state (e.g. `"running"`, `"not running"`, `"waiting"`).
    pub state: String,
    /// Process ID if actively running.
    pub pid: Option<u32>,
    /// Last exit status code if recorded.
    pub last_exit_code: Option<i32>,
    /// Last exit reason or description if available.
    pub last_exit_reason: Option<String>,
}

/// Pure deterministic parser for `launchctl print <domain>/<label>` output.
pub fn parse_launchctl_print(output: &str) -> LaunchdServiceStatus {
    let mut status = LaunchdServiceStatus {
        registered: true,
        state: "unknown".to_string(),
        pid: None,
        last_exit_code: None,
        last_exit_reason: None,
    };

    if output.contains("Could not find service") || output.contains("service is not loaded") {
        status.registered = false;
        status.state = "not loaded".to_string();
        return status;
    }

    for line in output.lines() {
        let trimmed = line.trim();

        if trimmed.starts_with("state = ") {
            status.state = trimmed.trim_start_matches("state = ").trim().to_string();
        } else if trimmed.starts_with("pid = ") {
            if let Ok(pid) = trimmed.trim_start_matches("pid = ").trim().parse::<u32>() {
                status.pid = Some(pid);
            }
        } else if trimmed.starts_with("last exit code = ") {
            if let Ok(code) = trimmed
                .trim_start_matches("last exit code = ")
                .trim()
                .parse::<i32>()
            {
                status.last_exit_code = Some(code);
            }
        } else if trimmed.starts_with("last exit reason = ") {
            status.last_exit_reason = Some(
                trimmed
                    .trim_start_matches("last exit reason = ")
                    .trim()
                    .to_string(),
            );
        }
    }

    status
}

fn escape_xml(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for c in input.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&apos;"),
            other => out.push(other),
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_generate_launchd_plist_fields() {
        let config = LaunchdPlistConfig::new(
            "/opt/homebrew/bin/cmux-pocket",
            "/Users/test/Library/Application Support/cmux-pocket/config.toml",
            Path::new("/Users/test/Library/Logs/cmux-pocket"),
        );

        let plist = generate_launchd_plist(&config).unwrap();

        assert!(plist.contains("<key>Label</key>"));
        assert!(plist.contains("<string>com.cmuxpocket.gateway</string>"));
        assert!(plist.contains("<key>ProgramArguments</key>"));
        assert!(plist.contains("<string>/opt/homebrew/opt/cmux-pocket/bin/cmux-pocket</string>"));
        assert!(plist.contains("<string>gateway</string>"));
        assert!(plist.contains("<string>run</string>"));
        assert!(plist.contains(
            "<string>/Users/test/Library/Application Support/cmux-pocket/config.toml</string>"
        ));
        assert!(plist.contains("<key>RunAtLoad</key>\n    <true/>"));
        assert!(plist.contains("<key>KeepAlive</key>\n    <true/>"));
        assert!(plist.contains("<key>StandardOutPath</key>"));
        assert!(plist
            .contains("<string>/Users/test/Library/Logs/cmux-pocket/gateway.stdout.log</string>"));
        assert!(plist.contains("<key>StandardErrorPath</key>"));
        assert!(plist
            .contains("<string>/Users/test/Library/Logs/cmux-pocket/gateway.stderr.log</string>"));

        // Guarantee NO token or secret in plist
        assert!(!plist.contains("token"));
        assert!(!plist.contains("auth"));
        assert!(!plist.contains("password"));
        assert!(!plist.contains("secret"));
    }

    #[test]
    fn test_plist_rejects_cellar_executable() {
        let config = LaunchdPlistConfig {
            label: "com.cmuxpocket.gateway".to_string(),
            executable_path: PathBuf::from(
                "/opt/homebrew/Cellar/cmux-pocket/0.1.0/bin/cmux-pocket",
            ),
            config_path: PathBuf::from(
                "/Users/test/Library/Application Support/cmux-pocket/config.toml",
            ),
            stdout_path: PathBuf::from("/Users/test/Library/Logs/cmux-pocket/stdout.log"),
            stderr_path: PathBuf::from("/Users/test/Library/Logs/cmux-pocket/stderr.log"),
            run_at_load: true,
            keep_alive: true,
            process_type: "Standard".to_string(),
            working_directory: None,
            environment_variables: BTreeMap::new(),
        };

        match generate_launchd_plist(&config) {
            Err(MacOsError::CellarPathForbidden { name, .. }) => {
                assert_eq!(name, "launchd executable");
            }
            other => panic!("Expected CellarPathForbidden, got: {other:?}"),
        }
    }

    #[test]
    fn test_plist_accepts_stable_homebrew_opt_executable() {
        let config = LaunchdPlistConfig::new(
            "/opt/homebrew/Cellar/cmux-pocket/0.1.0/bin/cmux-pocket",
            "/Users/test/Library/Application Support/cmux-pocket/config.toml",
            Path::new("/Users/test/Library/Logs/cmux-pocket"),
        );

        assert_eq!(
            config.executable_path,
            PathBuf::from("/opt/homebrew/opt/cmux-pocket/bin/cmux-pocket")
        );
        assert!(generate_launchd_plist(&config).is_ok());
    }

    #[test]
    fn test_launchctl_command_construction() {
        let target = LaunchdTarget::Gui(501);
        let plist = Path::new("/Users/test/Library/LaunchAgents/com.cmuxpocket.gateway.plist");

        let bootstrap = launchctl_bootstrap_cmd(&target, plist);
        assert_eq!(
            bootstrap.to_argv(),
            vec![
                "launchctl",
                "bootstrap",
                "gui/501",
                "/Users/test/Library/LaunchAgents/com.cmuxpocket.gateway.plist"
            ]
        );

        let bootout_svc = launchctl_bootout_service_cmd(&target, "com.cmuxpocket.gateway");
        assert_eq!(
            bootout_svc.to_argv(),
            vec!["launchctl", "bootout", "gui/501/com.cmuxpocket.gateway"]
        );

        let bootout_pl = launchctl_bootout_plist_cmd(&target, plist);
        assert_eq!(
            bootout_pl.to_argv(),
            vec![
                "launchctl",
                "bootout",
                "gui/501",
                "/Users/test/Library/LaunchAgents/com.cmuxpocket.gateway.plist"
            ]
        );

        let kickstart = launchctl_kickstart_cmd(&target, "com.cmuxpocket.gateway", true);
        assert_eq!(
            kickstart.to_argv(),
            vec![
                "launchctl",
                "kickstart",
                "-k",
                "gui/501/com.cmuxpocket.gateway"
            ]
        );

        let print = launchctl_print_service_cmd(&target, "com.cmuxpocket.gateway");
        assert_eq!(
            print.to_argv(),
            vec!["launchctl", "print", "gui/501/com.cmuxpocket.gateway"]
        );
    }

    #[test]
    fn test_parse_launchctl_print_running() {
        let sample_output = r#"
gui/501/com.cmuxpocket.gateway = {
	active count = 1
	path = /Users/test/Library/LaunchAgents/com.cmuxpocket.gateway.plist
	state = running

	program = /opt/homebrew/opt/cmux-pocket/bin/cmux-pocket
	arguments = {
		/opt/homebrew/opt/cmux-pocket/bin/cmux-pocket
		gateway
		run
		--config
		/Users/test/Library/Application Support/cmux-pocket/config.toml
	}

	stdout path = /Users/test/Library/Logs/cmux-pocket/gateway.stdout.log
	stderr path = /Users/test/Library/Logs/cmux-pocket/gateway.stderr.log
	inherited environment = {
	}

	default environment = {
		PATH => /usr/bin:/bin:/usr/sbin:/sbin
	}

	environment = {
	}

	domain = gui/501
	asid = 100004
	minimum runtime = 10
	exit timeout = 5
	runs = 1
	pid = 49201
	immediate reaper = 1
}
"#;

        let status = parse_launchctl_print(sample_output);
        assert!(status.registered);
        assert_eq!(status.state, "running");
        assert_eq!(status.pid, Some(49201));
    }

    #[test]
    fn test_parse_launchctl_print_not_found() {
        let sample_output =
            "Could not find service \"com.cmuxpocket.gateway\" in domain for gui/501";
        let status = parse_launchctl_print(sample_output);
        assert!(!status.registered);
        assert_eq!(status.state, "not loaded");
        assert_eq!(status.pid, None);
    }
}

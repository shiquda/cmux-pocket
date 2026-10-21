//! Safe, debounced launching of the cmux desktop app through LaunchServices.
//!
//! The Gateway runs as a LaunchAgent in the user's Aqua session, so `/usr/bin/open`
//! can start cmux without the phone knowing any host path. Arguments are fixed:
//! there is no client-controlled executable path or shell interpolation.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use crate::command::CommandSpec;
use crate::error::MacOsError;

/// LaunchServices bundle identifier of the cmux desktop app.
pub const CMUX_BUNDLE_ID: &str = "com.cmuxterm.app";
/// Absolute path of the system `open` utility.
pub const OPEN_PROGRAM: &str = "/usr/bin/open";
/// Repeated wake requests inside this window are absorbed.
pub const WAKE_DEBOUNCE: Duration = Duration::from_secs(10);

/// Common install locations for the cmux application bundle.
pub fn cmux_app_bundle_paths() -> Vec<PathBuf> {
    let mut paths = vec![PathBuf::from("/Applications/cmux.app")];
    if let Some(home) = std::env::var_os("HOME") {
        paths.push(PathBuf::from(home).join("Applications/cmux.app"));
    }
    paths
}

/// Resolves a binary name or path to an executable file.
///
/// Absolute and relative paths are checked directly; bare names are looked up in
/// `PATH` plus the Homebrew prefixes, since a LaunchAgent's minimal `PATH` often
/// omits `/opt/homebrew/bin`.
pub fn resolve_executable(program: &Path) -> Option<PathBuf> {
    if program.components().count() > 1 {
        return program.is_file().then(|| program.to_path_buf());
    }

    let mut dirs: Vec<PathBuf> = std::env::var_os("PATH")
        .map(|value| std::env::split_paths(&value).collect())
        .unwrap_or_default();
    dirs.push(PathBuf::from("/opt/homebrew/bin"));
    dirs.push(PathBuf::from("/usr/local/bin"));

    dirs.into_iter()
        .map(|dir| dir.join(program))
        .find(|candidate| candidate.is_file())
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|elapsed| elapsed.as_millis() as u64)
        .unwrap_or(0)
}

/// Launches `cmux.app` in the background and absorbs duplicate requests.
#[derive(Debug)]
pub struct CmuxLauncher {
    open_program: PathBuf,
    bundle_id: String,
    debounce_ms: u64,
    last_wake_ms: AtomicU64,
}

impl Default for CmuxLauncher {
    fn default() -> Self {
        Self::new()
    }
}

impl CmuxLauncher {
    pub fn new() -> Self {
        Self {
            open_program: PathBuf::from(OPEN_PROGRAM),
            bundle_id: CMUX_BUNDLE_ID.to_string(),
            debounce_ms: WAKE_DEBOUNCE.as_millis() as u64,
            last_wake_ms: AtomicU64::new(0),
        }
    }

    /// Creates a launcher with an explicit `open` binary and debounce window (tests, diagnostics).
    pub fn with_program(open_program: impl Into<PathBuf>, debounce: Duration) -> Self {
        Self {
            open_program: open_program.into(),
            bundle_id: CMUX_BUNDLE_ID.to_string(),
            debounce_ms: debounce.as_millis() as u64,
            last_wake_ms: AtomicU64::new(0),
        }
    }

    /// The exact command used to wake cmux, shared with tests and diagnostics.
    pub fn command_spec(&self) -> CommandSpec {
        CommandSpec::new(self.open_program.to_string_lossy().to_string())
            .arg("-g")
            .arg("-b")
            .arg(self.bundle_id.clone())
    }

    /// Whether this host can launch GUI apps through LaunchServices.
    pub fn is_supported(&self) -> bool {
        cfg!(target_os = "macos") && self.open_program.is_file()
    }

    /// Whether a cmux application bundle exists in a standard install location.
    pub fn is_app_installed(&self) -> bool {
        cmux_app_bundle_paths().iter().any(|path| path.is_dir())
    }

    /// Records a wake request and reports whether it should actually launch.
    ///
    /// Concurrent calls are single-winner thanks to the compare-exchange.
    pub fn try_begin_wake_at(&self, now_ms: u64) -> bool {
        let last = self.last_wake_ms.load(Ordering::Acquire);
        if last != 0 && now_ms.saturating_sub(last) < self.debounce_ms {
            return false;
        }
        self.last_wake_ms
            .compare_exchange(last, now_ms, Ordering::AcqRel, Ordering::Acquire)
            .is_ok()
    }

    /// Launches cmux in the background, skipping duplicate requests inside the debounce window.
    pub fn launch(&self) -> Result<(), MacOsError> {
        if !self.try_begin_wake_at(now_millis()) {
            return Ok(());
        }

        let output = self.command_spec().to_std_command().output()?;
        if output.status.success() {
            return Ok(());
        }

        let detail = String::from_utf8_lossy(&output.stderr).trim().to_string();
        Err(MacOsError::LaunchFailed(if detail.is_empty() {
            format!("open exited with status {}", output.status)
        } else {
            detail
        }))
    }
}

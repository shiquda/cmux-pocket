//! Host-side control used by remote wake requests.

use std::path::PathBuf;
use std::sync::Arc;

use async_trait::async_trait;
use cmux_pocket_macos::app_launch::{resolve_executable, CmuxLauncher};

/// Availability of the cmux desktop app on the Gateway host.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HostAppStatus {
    /// A cmux installation was found (CLI or application bundle).
    pub installed: bool,
    /// This Gateway can launch the cmux app for a remote client.
    pub can_wake: bool,
}

impl HostAppStatus {
    pub fn unsupported() -> Self {
        Self {
            installed: false,
            can_wake: false,
        }
    }
}

/// Host control surface used by `mobile.host.*` RPCs.
///
/// Implementations must never accept a path or command from a client.
#[async_trait]
pub trait HostController: Send + Sync {
    async fn status(&self) -> HostAppStatus;
    async fn wake_cmux(&self) -> Result<(), String>;
}

/// Default controller for platforms without desktop app launching (tests, Linux dev hosts).
#[derive(Debug, Default)]
pub struct UnsupportedHostController;

#[async_trait]
impl HostController for UnsupportedHostController {
    async fn status(&self) -> HostAppStatus {
        HostAppStatus::unsupported()
    }

    async fn wake_cmux(&self) -> Result<(), String> {
        Err("remote cmux wake is not supported on this host".to_string())
    }
}

/// Launches `cmux.app` through LaunchServices on macOS.
#[derive(Debug)]
pub struct MacOsHostController {
    cmux_path: PathBuf,
    launcher: CmuxLauncher,
}

impl MacOsHostController {
    /// Builds a controller from the configured cmux binary (`cmux` when unset).
    pub fn new(cmux_path: Option<PathBuf>) -> Self {
        Self {
            cmux_path: cmux_path.unwrap_or_else(|| PathBuf::from("cmux")),
            launcher: CmuxLauncher::new(),
        }
    }

    /// Builds a controller with an explicit launcher (tests).
    pub fn with_launcher(cmux_path: Option<PathBuf>, launcher: CmuxLauncher) -> Self {
        Self {
            cmux_path: cmux_path.unwrap_or_else(|| PathBuf::from("cmux")),
            launcher,
        }
    }
}

#[async_trait]
impl HostController for MacOsHostController {
    async fn status(&self) -> HostAppStatus {
        HostAppStatus {
            installed: resolve_executable(&self.cmux_path).is_some()
                || self.launcher.is_app_installed(),
            can_wake: self.launcher.is_supported(),
        }
    }

    async fn wake_cmux(&self) -> Result<(), String> {
        if !self.launcher.is_supported() {
            return Err("remote cmux wake is not supported on this host".to_string());
        }
        self.launcher.launch().map_err(|error| error.to_string())
    }
}

/// Shared host controller handle stored by the Gateway.
pub type SharedHostController = Arc<dyn HostController>;

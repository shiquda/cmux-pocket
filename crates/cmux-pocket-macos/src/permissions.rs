//! Permissions and atomic file writing primitives for macOS.
//!
//! Enforces user-only access (`0o700` for directories, `0o600` for secret files)
//! and provides atomic replace semantics to avoid partial writes or race conditions.

use crate::error::MacOsError;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::Path;

/// Expected permissions for secret files (`rw-------` / 0o600).
pub const USER_ONLY_FILE_MODE: u32 = 0o600;

/// Expected permissions for user-only directories (`rwx------` / 0o700).
pub const USER_ONLY_DIR_MODE: u32 = 0o700;

/// Bitmask for group and other permissions (`0o077`).
pub const GROUP_OTHER_PERMISSION_MASK: u32 = 0o077;

/// Creates a directory with `0o700` permissions (user read/write/execute only).
///
/// If parent directories do not exist, creates them recursively.
pub fn create_dir_user_only(path: &Path) -> Result<(), MacOsError> {
    if !path.exists() {
        fs::create_dir_all(path)?;
        fs::set_permissions(path, fs::Permissions::from_mode(USER_ONLY_DIR_MODE))?;
    } else {
        // Enforce user-only permissions on existing directory
        ensure_dir_user_only(path)?;
    }
    Ok(())
}

/// Verifies that a directory has user-only permissions (`mode & 0o077 == 0`).
pub fn ensure_dir_user_only(path: &Path) -> Result<(), MacOsError> {
    let metadata = fs::metadata(path)?;
    let mode = metadata.permissions().mode() & 0o777;
    if mode & GROUP_OTHER_PERMISSION_MASK != 0 {
        return Err(MacOsError::InsecurePermissions {
            path: path.to_path_buf(),
            mode,
            expected_mask: 0o700,
        });
    }
    Ok(())
}

/// Verifies that a file has user-only permissions (`mode & 0o077 == 0`).
pub fn ensure_file_user_only(path: &Path) -> Result<(), MacOsError> {
    let metadata = fs::metadata(path)?;
    let mode = metadata.permissions().mode() & 0o777;
    if mode & GROUP_OTHER_PERMISSION_MASK != 0 {
        return Err(MacOsError::InsecurePermissions {
            path: path.to_path_buf(),
            mode,
            expected_mask: 0o600,
        });
    }
    Ok(())
}

/// Sets file permissions to `0o600` (user read/write only).
pub fn set_user_only_file_permissions(path: &Path) -> Result<(), MacOsError> {
    fs::set_permissions(path, fs::Permissions::from_mode(USER_ONLY_FILE_MODE))?;
    Ok(())
}

/// Sets directory permissions to `0o700` (user read/write/execute only).
pub fn set_user_only_dir_permissions(path: &Path) -> Result<(), MacOsError> {
    fs::set_permissions(path, fs::Permissions::from_mode(USER_ONLY_DIR_MODE))?;
    Ok(())
}

/// Writes `data` atomically to `destination` with specified Unix permissions.
///
/// Creates a temporary file in the same parent directory as `destination`, writes and
/// syncs all data to disk, and replaces `destination` via an atomic rename.
pub fn atomic_write_file(destination: &Path, data: &[u8], mode: u32) -> Result<(), MacOsError> {
    let parent = destination.parent().ok_or_else(|| {
        MacOsError::InvalidConfiguration(format!(
            "Cannot determine parent directory for path: {destination:?}"
        ))
    })?;

    if !parent.exists() {
        create_dir_user_only(parent)?;
    }

    // Generate a unique temporary filename in the same directory
    let filename = destination
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("pocket");
    let mut random_bytes = [0u8; 8];
    getrandom::getrandom(&mut random_bytes).map_err(|e| {
        MacOsError::Io(std::io::Error::other(format!(
            "Random generation failed: {e}"
        )))
    })?;
    let random_hex: String = random_bytes.iter().map(|b| format!("{b:02x}")).collect();
    let temp_path = parent.join(format!(".tmp.{filename}.{random_hex}"));

    // Open temporary file with requested permissions
    let mut file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(mode)
        .open(&temp_path)?;

    // Ensure permissions are explicitly set
    let _ = fs::set_permissions(&temp_path, fs::Permissions::from_mode(mode));

    // Write content and sync to physical media
    if let Err(e) = file.write_all(data).and_then(|_| file.sync_all()) {
        let _ = fs::remove_file(&temp_path);
        return Err(MacOsError::Io(e));
    }
    drop(file);

    // Atomically replace destination
    if let Err(e) = fs::rename(&temp_path, destination) {
        let _ = fs::remove_file(&temp_path);
        return Err(MacOsError::Io(e));
    }

    // Ensure final permissions match
    let _ = fs::set_permissions(destination, fs::Permissions::from_mode(mode));

    Ok(())
}

/// Atomically writes a secret file (such as a token or config) with `0o600` permissions.
pub fn atomic_write_secret_file(destination: &Path, data: &[u8]) -> Result<(), MacOsError> {
    atomic_write_file(destination, data, USER_ONLY_FILE_MODE)
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::tempdir;

    #[test]
    fn test_create_dir_user_only() {
        let tmp = tempdir().unwrap();
        let dir = tmp.path().join("secure_dir");

        create_dir_user_only(&dir).unwrap();
        assert!(dir.is_dir());

        let metadata = fs::metadata(&dir).unwrap();
        let mode = metadata.permissions().mode() & 0o777;
        assert_eq!(mode, 0o700);
        assert!(ensure_dir_user_only(&dir).is_ok());
    }

    #[test]
    fn test_atomic_write_secret_file() {
        let tmp = tempdir().unwrap();
        let file_path = tmp.path().join("secret.key");

        atomic_write_secret_file(&file_path, b"super-secret-content").unwrap();

        assert_eq!(fs::read(&file_path).unwrap(), b"super-secret-content");
        let metadata = fs::metadata(&file_path).unwrap();
        let mode = metadata.permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);
        assert!(ensure_file_user_only(&file_path).is_ok());

        // Overwrite atomically
        atomic_write_secret_file(&file_path, b"updated-secret-content").unwrap();
        assert_eq!(fs::read(&file_path).unwrap(), b"updated-secret-content");
        let mode = fs::metadata(&file_path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);
    }

    #[test]
    fn test_insecure_permissions_detection() {
        let tmp = tempdir().unwrap();
        let file_path = tmp.path().join("leaky.txt");

        fs::write(&file_path, b"hello").unwrap();
        fs::set_permissions(&file_path, fs::Permissions::from_mode(0o644)).unwrap();

        match ensure_file_user_only(&file_path) {
            Err(MacOsError::InsecurePermissions { mode, .. }) => {
                assert_eq!(mode, 0o644);
            }
            other => panic!("Expected InsecurePermissions, got: {other:?}"),
        }

        // Setting to 0o600 fixes it
        set_user_only_file_permissions(&file_path).unwrap();
        assert!(ensure_file_user_only(&file_path).is_ok());
    }
}

//! Install-source detection and upgrade delegation for `cmux-pocket upgrade`.

use cmux_pocket_cli::upgrade::{
    classify_install_source, manual_commands, plan_upgrade, tag_from_release_url, InstallSource,
};
use std::path::{Path, PathBuf};

fn prefixes() -> Vec<PathBuf> {
    vec![PathBuf::from("/opt/homebrew"), PathBuf::from("/usr/local")]
}

#[test]
fn release_tag_comes_from_the_latest_release_redirect() {
    assert_eq!(
        tag_from_release_url("https://github.com/shiquda/cmux-pocket/releases/tag/v0.3.0"),
        Some("v0.3.0".to_string())
    );
    assert_eq!(
        tag_from_release_url("https://github.com/shiquda/cmux-pocket/releases/tag/v0.3.0/"),
        Some("v0.3.0".to_string())
    );
    // A login redirect or an error page cannot be mistaken for a release tag.
    assert_eq!(
        tag_from_release_url("https://github.com/login?return_to=releases"),
        None
    );
    assert_eq!(tag_from_release_url(""), None);
    assert_eq!(
        tag_from_release_url("https://github.com/shiquda/cmux-pocket/releases/tag/"),
        None
    );
}

#[test]
fn homebrew_owns_cellar_opt_and_prefix_bin_paths() {
    let cargo_bin = Path::new("/Users/dev/.cargo/bin");
    for path in [
        "/opt/homebrew/Cellar/cmux-pocket/0.3.0/bin/cmux-pocket",
        "/opt/homebrew/opt/cmux-pocket/bin/cmux-pocket",
        "/opt/homebrew/bin/cmux-pocket",
        "/usr/local/Cellar/cmux-pocket/0.3.0/bin/cmux-pocket",
        "/usr/local/bin/cmux-pocket",
    ] {
        assert_eq!(
            classify_install_source(Path::new(path), &prefixes(), cargo_bin),
            InstallSource::Homebrew {
                prefix: PathBuf::from(if path.starts_with("/usr/local") {
                    "/usr/local"
                } else {
                    "/opt/homebrew"
                }),
            },
            "{path} should be attributed to Homebrew"
        );
    }
}

#[test]
fn cargo_owns_the_cargo_bin_directory() {
    let cargo_bin = Path::new("/Users/dev/.cargo/bin");
    assert_eq!(
        classify_install_source(
            Path::new("/Users/dev/.cargo/bin/cmux-pocket"),
            &prefixes(),
            cargo_bin
        ),
        InstallSource::Cargo {
            bin_dir: cargo_bin.to_path_buf(),
        }
    );
}

#[test]
fn a_hand_placed_binary_is_never_guessed_at() {
    for path in [
        "/Users/dev/.local/bin/cmux-pocket",
        "/Users/dev/repo/cmux-pocket/target/debug/cmux-pocket",
        "/tmp/cmux-pocket",
    ] {
        assert_eq!(
            classify_install_source(
                Path::new(path),
                &prefixes(),
                Path::new("/Users/dev/.cargo/bin")
            ),
            InstallSource::Unknown,
            "{path} must not be attributed to a package manager"
        );
    }
}

#[test]
fn upgrade_is_delegated_to_the_owning_package_manager() {
    let brew = plan_upgrade(
        &InstallSource::Homebrew {
            prefix: PathBuf::from("/opt/homebrew"),
        },
        "v0.4.0",
    )
    .expect("homebrew plan");
    assert_eq!(
        brew.display(),
        "brew upgrade shiquda/cmux-pocket/cmux-pocket"
    );

    let cargo = plan_upgrade(
        &InstallSource::Cargo {
            bin_dir: PathBuf::from("/Users/dev/.cargo/bin"),
        },
        "v0.4.0",
    )
    .expect("cargo plan");
    assert_eq!(
        cargo.display(),
        "cargo install --force --git https://github.com/shiquda/cmux-pocket --tag v0.4.0 cmux-pocket-cli"
    );

    assert_eq!(plan_upgrade(&InstallSource::Unknown, "v0.4.0"), None);
}

#[test]
fn an_unrecognized_install_reports_both_manual_upgrades() {
    let commands: Vec<String> = manual_commands("v0.4.0")
        .iter()
        .map(|command| command.display())
        .collect();
    assert_eq!(
        commands,
        vec![
            "brew upgrade shiquda/cmux-pocket/cmux-pocket".to_string(),
            "cargo install --force --git https://github.com/shiquda/cmux-pocket --tag v0.4.0 cmux-pocket-cli".to_string(),
        ]
    );
}

#[test]
fn package_managers_expose_the_stable_binary_path() {
    assert_eq!(
        InstallSource::Homebrew {
            prefix: PathBuf::from("/opt/homebrew")
        }
        .managed_binary(),
        Some(PathBuf::from("/opt/homebrew/bin/cmux-pocket"))
    );
    assert_eq!(
        InstallSource::Cargo {
            bin_dir: PathBuf::from("/Users/dev/.cargo/bin")
        }
        .managed_binary(),
        Some(PathBuf::from("/Users/dev/.cargo/bin/cmux-pocket"))
    );
    assert_eq!(InstallSource::Unknown.managed_binary(), None);
}

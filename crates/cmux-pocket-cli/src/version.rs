//! Semantic-version parsing and release-tag normalization.
//!
//! Both `setup` and `upgrade` decide whether a running Gateway or an installed
//! binary is current, so they share one parser instead of comparing strings.

/// Parses a `major.minor.patch` version, ignoring prerelease and build suffixes.
///
/// Returns `None` for anything that is not exactly three numeric components, so
/// an unparseable value never silently compares as "newer" or "older".
pub fn parse_version(value: &str) -> Option<(u64, u64, u64)> {
    let core = value.trim().split(['-', '+']).next()?;
    let mut parts = core.split('.');
    let parsed = (
        parts.next()?.parse().ok()?,
        parts.next()?.parse().ok()?,
        parts.next()?.parse().ok()?,
    );
    if parts.next().is_some() {
        return None;
    }
    Some(parsed)
}

/// Strips the leading `v` GitHub uses in release tags (`v0.3.0` -> `0.3.0`).
pub fn normalize_tag(tag: &str) -> &str {
    let trimmed = tag.trim();
    trimmed
        .strip_prefix('v')
        .or_else(|| trimmed.strip_prefix('V'))
        .unwrap_or(trimmed)
}

/// Whether `candidate` is strictly newer than `current`.
///
/// `None` when either side is not a semver triple, so callers report the
/// comparison failure instead of guessing.
pub fn is_newer(candidate: &str, current: &str) -> Option<bool> {
    Some(parse_version(normalize_tag(candidate))? > parse_version(normalize_tag(current))?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compares_three_part_versions_and_ignores_prerelease_suffixes() {
        assert_eq!(parse_version("0.2.0"), Some((0, 2, 0)));
        assert_eq!(parse_version("0.3.0-rc1"), Some((0, 3, 0)));
        assert_eq!(parse_version("1.2"), None);
        assert_eq!(parse_version("1.2.3.4"), None);
        assert!(parse_version("10.0.0") > parse_version("9.9.9"));
    }

    #[test]
    fn normalizes_release_tags() {
        assert_eq!(normalize_tag("v0.3.0"), "0.3.0");
        assert_eq!(normalize_tag("0.3.0"), "0.3.0");
        assert_eq!(normalize_tag(" v0.3.0 "), "0.3.0");
    }

    #[test]
    fn only_a_strictly_greater_release_is_newer() {
        assert_eq!(is_newer("v0.3.1", "0.3.0"), Some(true));
        assert_eq!(is_newer("v0.3.0", "0.3.0"), Some(false));
        assert_eq!(is_newer("v0.2.0", "0.3.0"), Some(false));
        assert_eq!(is_newer("nightly", "0.3.0"), None);
    }
}

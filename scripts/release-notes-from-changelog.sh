#!/bin/sh
set -eu

# Prints the CHANGELOG.md body for one release tag, for use as GitHub Release notes.
# Exits non-zero when the section is missing or empty so a release cannot publish
# empty notes.

ROOT_DIR=$(cd -- "$(dirname -- "$0")/.." && pwd)
CHANGELOG=${CHANGELOG:-"$ROOT_DIR/CHANGELOG.md"}

usage() {
    printf 'Usage: %s <vX.Y.Z>\n' "$0" >&2
    printf '%s\n' 'Prints the CHANGELOG.md section for the given release tag (the leading "v" is optional).' >&2
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
    usage
    exit 0
fi

if [ "$#" -ne 1 ]; then
    usage
    exit 2
fi

RELEASE_TAG=$1
VERSION=${RELEASE_TAG#v}

case "$VERSION" in
    [0-9]*) ;;
    *)
        printf 'Release tag must look like vX.Y.Z (got: %s)\n' "$RELEASE_TAG" >&2
        usage
        exit 2
        ;;
esac

if [ ! -f "$CHANGELOG" ]; then
    printf 'Changelog not found: %s\n' "$CHANGELOG" >&2
    exit 1
fi

if ! NOTES=$(awk -v version="$VERSION" '
    function is_version_heading(line,    rest) {
        if (substr(line, 1, 4) != "## [") {
            return 0
        }
        rest = substr(line, 5)
        if (substr(rest, 1, 1) == "v") {
            rest = substr(rest, 2)
        }
        return substr(rest, 1, length(version) + 1) == version "]"
    }
    is_version_heading($0) { found = 1; next }
    found && (/^## / || /^\[[^]]*\]:/) { exit }
    found { print }
    END { if (!found) exit 1 }
' "$CHANGELOG"); then
    printf 'CHANGELOG.md has no section for %s\n' "$RELEASE_TAG" >&2
    exit 1
fi

# Collapse runs of blank lines and drop leading/trailing ones.
NOTES=$(printf '%s\n' "$NOTES" | awk '
    /^[[:space:]]*$/ { if (seen) blank++; next }
    { while (blank > 0) { print ""; blank-- } print; seen = 1 }
')

if [ -z "$NOTES" ]; then
    printf 'CHANGELOG.md has an empty section for %s\n' "$RELEASE_TAG" >&2
    exit 1
fi

printf '%s\n' "$NOTES"

#!/bin/sh
set -eu

# Package a release CLI into a flat tarball for the Homebrew formula.
# The archive contains one file, cmux-pocket, so bin.install works after
# Homebrew unpacks it. Ad-hoc signing happens on the packaged copy; the
# caller's binary is left unchanged.

usage() {
    printf 'Usage: %s <binary> <vX.Y.Z> <aarch64-apple-darwin|x86_64-apple-darwin> <output-dir>\n' "$0" >&2
}

if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
    usage
    exit 0
fi

if [ "$#" -ne 4 ]; then
    usage
    exit 2
fi

bin=$1
tag=$2
target=$3
out_dir=$4

case "$tag" in
    v[0-9]*.[0-9]*.[0-9]*) ;;
    *)
        printf 'tag must look like v1.2.3 (got: %s)\n' "$tag" >&2
        exit 2
        ;;
esac

case "$target" in
    aarch64-apple-darwin | x86_64-apple-darwin) ;;
    *)
        printf 'unsupported target: %s\n' "$target" >&2
        exit 2
        ;;
esac

if [ ! -f "$bin" ]; then
    printf 'binary not found: %s\n' "$bin" >&2
    exit 1
fi

version=${tag#v}
actual=$("$bin" --version)
expected="cmux-pocket $version"
if [ "$actual" != "$expected" ]; then
    printf 'version %s does not match %s\n' "$actual" "$expected" >&2
    exit 1
fi

stage=$(mktemp -d)
home=$(mktemp -d)
cleanup() {
    rm -rf "$stage" "$home"
}
trap cleanup EXIT

cp "$bin" "$stage/cmux-pocket"
chmod 755 "$stage/cmux-pocket"
# rustc's linker signature is already ad-hoc. Re-sign the packaged copy so the
# archived bytes are the ones codesign --verify accepts, then hash that archive
# downstream. Do not strip; stripping invalidates the signature.
codesign --force --sign - "$stage/cmux-pocket"
codesign --verify --strict "$stage/cmux-pocket"
codesign -dv "$stage/cmux-pocket" 2>&1 | grep -q 'Signature=adhoc'

minos=$(otool -l "$stage/cmux-pocket" | awk '/minos/{print $2; exit}')
if [ "$minos" != "11.0" ]; then
    printf 'minos is %s, expected 11.0\n' "$minos" >&2
    exit 1
fi

unexpected=$(otool -L "$stage/cmux-pocket" | awk 'NR>1 { print $1 }' | grep -v '^/usr/lib/' || true)
if [ -n "$unexpected" ]; then
    printf 'unexpected linked libraries:\n%s\n' "$unexpected" >&2
    exit 1
fi

HOME="$home" "$stage/cmux-pocket" doctor --offline --json > "$stage/doctor.json"
grep -q '"ok": true' "$stage/doctor.json"
grep -q '"code": 0' "$stage/doctor.json"

mkdir -p "$out_dir"
archive="$out_dir/cmux-pocket-${tag}-${target}.tar.gz"
# macOS tar otherwise adds AppleDouble files and the formula unpacks two members.
COPYFILE_DISABLE=1 tar -C "$stage" -czf "$archive" cmux-pocket
listing=$(COPYFILE_DISABLE=1 tar -tf "$archive")
if [ "$listing" != "cmux-pocket" ]; then
    printf 'archive layout must be a single cmux-pocket file, got:\n%s\n' "$listing" >&2
    exit 1
fi

printf '%s\n' "$archive"

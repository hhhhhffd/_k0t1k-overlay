#!/usr/bin/env bash
# Installs a local build of the overlay into a _k0t1k launcher as the active plugin version, signed
# like a release, so the launcher's checks pass and the next game it starts loads this build.
# Usage: scripts/dev-install.sh [launcher-dir]   (or set K0T1K_DIR), e.g. /mnt/c/Apps/_k0t1k
# Requires JDK 21, openssl and the plugin signing key.
set -euo pipefail

PLUGIN_ID="overlay"
API_VERSION=1
MIN_LAUNCHER="0.1.4"
JAR_ASSET="k0t1k-overlay.jar"
# Raw 32-byte Ed25519 public key (base64) embedded in the launcher.
PUBLIC_KEY_B64="b37Axim0D7VgDNWVIgvlnpdn2nVOMSLM/jIFbRFc/4k="
KEY="${K0T1K_PLUGIN_KEY:-$HOME/.config/k0t1k/plugin-signing-key.pem}"

die() { echo "dev-install: $*" >&2; exit 1; }

launcher="${1:-${K0T1K_DIR:-}}"
[ -n "$launcher" ] || die "pass the launcher folder (the one holding plugins/) or set K0T1K_DIR"
plugins="$launcher/plugins"
[ -d "$plugins" ] || die "no plugins folder in $launcher; start the launcher once first"
[ -r "$KEY" ] || die "signing key not readable: $KEY"

cd "$(git rev-parse --show-toplevel)"

version="$(sed -n 's/^version = "\(.*\)"$/\1/p' build.gradle.kts)"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "unexpected version: $version"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
out="$work/out"
mkdir "$out"

# No daemon: a lingering Gradle JVM would look like a running game to the launcher.
./gradlew shadowJar --no-daemon --quiet
cp "build/libs/k0t1k-overlay-$version.jar" "$out/$JAR_ASSET"

jar_sha="$(sha256sum "$out/$JAR_ASSET" | cut -d' ' -f1)"

# Written once; the signature covers these exact bytes.
cat > "$out/plugin.json" <<JSON
{
  "id": "$PLUGIN_ID",
  "version": "$version",
  "api": $API_VERSION,
  "minLauncher": "$MIN_LAUNCHER",
  "files": {
    "$JAR_ASSET": "$jar_sha"
  }
}
JSON

{ printf '_k0t1k-plugin\n'; cat "$out/plugin.json"; } > "$work/msg"
openssl pkeyutl -sign -rawin -inkey "$KEY" -in "$work/msg" | base64 -w0 > "$out/plugin.json.sig"

# Verify against the launcher's public key: SPKI DER = fixed Ed25519 prefix + raw key.
{ printf '\x30\x2a\x30\x05\x06\x03\x2b\x65\x70\x03\x21\x00'; printf '%s' "$PUBLIC_KEY_B64" | base64 -d; } > "$work/pub.der"
base64 -d "$out/plugin.json.sig" > "$work/sig.bin"
openssl pkeyutl -verify -rawin -pubin -keyform DER -inkey "$work/pub.der" \
    -in "$work/msg" -sigfile "$work/sig.bin" >/dev/null \
    || die "signature does not verify against the launcher public key (wrong signing key?)"

root="$plugins/$PLUGIN_ID"
target="$root/$version"
mkdir -p "$target"
cp "$out/$JAR_ASSET" "$out/plugin.json" "$out/plugin.json.sig" "$target/"
previous="$(cat "$root/active" 2>/dev/null || true)"
printf '%s' "$version" > "$root/active"

echo "installed $version (sha256 $jar_sha) into $target"
[ -z "$previous" ] || [ "$previous" = "$version" ] || echo "active was $previous, now $version"
echo "the launcher uses it only when it is not older than the version built into the launcher;"
echo "it takes effect on the next game start, a running game keeps its jar"

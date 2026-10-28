#!/usr/bin/env bash
# Publishes the overlay agent as a _k0t1k launcher plugin: a GitHub Release of
# this repo with plugin.json, plugin.json.sig and the agent jar.
# Requires JDK 21, openssl, gh (authenticated) and the plugin signing key.
set -euo pipefail

REPO="hhhhhffd/_k0t1k-overlay"
PLUGIN_ID="overlay"
API_VERSION=1
MIN_LAUNCHER="0.1.4"
JAR_ASSET="k0t1k-overlay.jar"
# Raw 32-byte Ed25519 public key (base64) embedded in the launcher.
PUBLIC_KEY_B64="b37Axim0D7VgDNWVIgvlnpdn2nVOMSLM/jIFbRFc/4k="
KEY="${K0T1K_PLUGIN_KEY:-$HOME/.config/k0t1k/plugin-signing-key.pem}"

die() { echo "release-plugin: $*" >&2; exit 1; }

cd "$(git rev-parse --show-toplevel)"

[ -r "$KEY" ] || die "signing key not readable: $KEY"
[ -z "$(git status --porcelain)" ] || die "working tree is not clean"

upstream="$(git rev-parse --abbrev-ref --symbolic-full-name '@{u}' 2>/dev/null)" \
    || die "current branch has no upstream"
git merge-base --is-ancestor HEAD "$upstream" \
    || die "HEAD is not pushed to $upstream"
head_sha="$(git rev-parse HEAD)"

version="$(sed -n 's/^version = "\(.*\)"$/\1/p' build.gradle.kts)"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "unexpected version: $version"
tag="v$version"

git rev-parse -q --verify "refs/tags/$tag" >/dev/null && die "local tag $tag already exists"
[ -z "$(git ls-remote --tags origin "refs/tags/$tag")" ] || die "remote tag $tag already exists"
if gh release view "$tag" --repo "$REPO" >/dev/null 2>&1; then
    die "release $tag already exists"
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
out="$work/out"
mkdir "$out"

# No daemon: a lingering Gradle JVM would look like a running game to the launcher.
./gradlew clean shadowJar --no-daemon --quiet
[ -z "$(git status --porcelain)" ] || die "build modified the working tree"
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

gh release create "$tag" \
    --repo "$REPO" \
    --target "$head_sha" \
    --title "Overlay $tag" \
    --notes "In-game overlay agent $version (plugin API $API_VERSION, built from $head_sha)." \
    "$out/plugin.json" "$out/plugin.json.sig" "$out/$JAR_ASSET"

echo "released $tag"

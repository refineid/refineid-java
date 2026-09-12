#!/usr/bin/env bash
#
# Build RefineID Signer.app for this Mac.
#
# The application menu, the Dock tile and the About box take their name
# from the bundle, which is why a development run shows "java" and this
# does not: -Xdock:name renames the Dock tile alone.
#
# macOS accepts at most three components in a bundle version, so the
# bundle carries YY.M.D and the jar keeps the full stamp including the
# ten-minute bucket.

set -euo pipefail
cd "$(dirname "$0")/.."

name="RefineID Signer"
main_class="fi.refineid.signer.ui.Launcher"

version="$(./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null ||
  mvn help:evaluate -Dexpression=project.version -q -DforceStdout)"
bundle_version="$(echo "$version" | cut -d. -f1-3)"

echo "packaging ${name} ${bundle_version} (from ${version})"

mvn -B -q clean package -DskipTests
mvn -B -q dependency:copy-dependencies -DoutputDirectory=target/lib
cp "target/refineid-signer-${version}.jar" target/lib/

# The card module travels inside the bundle. Installed in
# /usr/local/lib it needs an administrator, and an application that
# asks a person for their admin password before it will sign anything
# has already lost them. ssh needs the installed copy, because
# ssh-agent only loads a provider from a small allowlist; nothing
# loading a module by path does.
# Into the input directory, which is what jpackage copies to $APPDIR.
# --resource-dir is for jpackage's own templates and puts nothing in
# the application.
module_source="${REFINEID_MODULE_SOURCE:-/usr/local/lib/librefineid_pkcs11_sign.dylib}"
cp "$module_source" target/lib/

rm -rf "target/dist"
jpackage \
  --type app-image \
  --name "$name" \
  --app-version "$bundle_version" \
  --input target/lib \
  --main-jar "refineid-signer-${version}.jar" \
  --main-class "$main_class" \
  --dest target/dist \
  --vendor "RefineID" \
  --mac-package-identifier fi.refineid.signer \
  --java-options "-Drefineid.module=\$APPDIR/librefineid_pkcs11_sign.dylib"

# macOS takes at most three components in a version, and uses two
# fields for two jobs: the one a person reads, and the one builds are
# ordered by. The bucket is the second, exactly as the Apple build
# does it.
bucket="$(echo "$version" | cut -d. -f4)"
if [ -n "$bucket" ]; then
  /usr/libexec/PlistBuddy -c "Set :CFBundleVersion ${bucket}" \
    "target/dist/${name}.app/Contents/Info.plist"
fi

echo "built target/dist/${name}.app (${bundle_version}, build ${bucket:-none})"

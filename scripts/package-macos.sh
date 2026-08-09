#!/usr/bin/env bash
#
# Build ReFineID Signer.app for this Mac.
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

name="ReFineID Signer"
main_class="fi.refineid.signer.ui.Launcher"

version="$(./mvnw help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null ||
  mvn help:evaluate -Dexpression=project.version -q -DforceStdout)"
bundle_version="$(echo "$version" | cut -d. -f1-3)"

echo "packaging ${name} ${bundle_version} (from ${version})"

mvn -B -q clean package -DskipTests
mvn -B -q dependency:copy-dependencies -DoutputDirectory=target/lib
cp "target/refineid-signer-${version}.jar" target/lib/

rm -rf "target/dist"
jpackage \
  --type app-image \
  --name "$name" \
  --app-version "$bundle_version" \
  --input target/lib \
  --main-jar "refineid-signer-${version}.jar" \
  --main-class "$main_class" \
  --dest target/dist \
  --vendor "ReFineID" \
  --mac-package-identifier fi.refineid.signer

echo "built target/dist/${name}.app"

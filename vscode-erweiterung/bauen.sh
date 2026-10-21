#!/usr/bin/env bash
# Erweiterung bauen: Basiskit-Paket erzeugen, mitliefern, VSIX packen.
#   vscode-erweiterung/bauen.sh   →   vscode-erweiterung/kieselstein-addon-<version>.vsix
set -euo pipefail
HIER=$(cd "$(dirname "$0")" && pwd)
cd "$HIER/.."
[ -x "${JAVA_HOME:-}/bin/java" ] || JAVA_HOME=$(ls -d "$HOME"/.kieselstein/jdk/*jdk21* | head -1)
export JAVA_HOME
./gradlew -q --console=plain paket ${KS_CLIENT_LIB:+-PksClientLib=$KS_CLIENT_LIB}
# Projektvorlage für neue Addon-Projekte: Basisschicht, Addon-Vorlage, Gradle
VERSION=$(sed -n "s/^ext.basiskitVersion = '\(.*\)'/\1/p" build.gradle)
rm -rf "$HIER/projekt"
mkdir -p "$HIER/projekt"
cp -r projekt-vorlage/. "$HIER/projekt/"
mv "$HIER/projekt/gitignore" "$HIER/projekt/.gitignore"
sed -i "s/@BASISKIT_VERSION@/$VERSION/" "$HIER/projekt/build.gradle"
mkdir -p "$HIER/projekt/basis"
cp -r basis/build.gradle basis/src "$HIER/projekt/basis/"
cp -r vorlage gradlew gradle "$HIER/projekt/"
mkdir -p "$HIER/paket"
find "$HIER/paket" -name 'kieselstein-basiskit-*.tar.gz' -delete
cp build/paket/kieselstein-basiskit-*.tar.gz "$HIER/paket/"
cd "$HIER"
npx -y @vscode/vsce package --skip-license

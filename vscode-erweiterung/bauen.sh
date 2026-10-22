#!/usr/bin/env bash
# Erweiterung bauen: Basiskit-Paket erzeugen, mitliefern, VSIX packen.
#   vscode-erweiterung/bauen.sh   →   vscode-erweiterung/kieselstein-addon-<version>.vsix
set -euo pipefail
HIER=$(cd "$(dirname "$0")" && pwd)
cd "$HIER/.."
[ -x "${JAVA_HOME:-}/bin/java" ] || JAVA_HOME=$(ls -d "$HOME"/.kieselstein/jdk/*jdk21* | head -1)
export JAVA_HOME
./gradlew -q --console=plain paket ${KS_CLIENT_LIB:+-PksClientLib=$KS_CLIENT_LIB}
# Vorlage für neue Addon-Projekte (ein Addon = ein Repository): Vorlage, Gradle, Basisschicht als Bibliothek
VERSION=$(sed -n "s/^ext.basiskitVersion = '\\(.*\\)'/\\1/p" build.gradle)
./gradlew -q --console=plain :basis:jar :basis:sourcesJar ${KS_CLIENT_LIB:+-PksClientLib=$KS_CLIENT_LIB}
rm -rf "$HIER/projekt"
mkdir -p "$HIER/projekt/basiskit"
cp -r addon-vorlage/. "$HIER/projekt/"
mv "$HIER/projekt/gitignore" "$HIER/projekt/.gitignore"
sed -i "s/@BASISKIT_VERSION@/$VERSION/" "$HIER/projekt/build.gradle"
cp "basis/build/libs/basis-$VERSION.jar" "basis/build/libs/basis-$VERSION-sources.jar" "$HIER/projekt/basiskit/"
cp -r gradlew gradle "$HIER/projekt/"
mkdir -p "$HIER/paket"
find "$HIER/paket" -name 'kieselstein-basiskit-*.tar.gz' -delete
cp "build/paket/kieselstein-basiskit-$VERSION.tar.gz" "$HIER/paket/"
cd "$HIER"
npx -y @vscode/vsce package --skip-license

#!/usr/bin/env bash
# Erweiterung bauen: Basiskit-Paket erzeugen, mitliefern, VSIX packen.
#   vscode-erweiterung/bauen.sh   →   vscode-erweiterung/kieselstein-addon-<version>.vsix
set -euo pipefail
HIER=$(cd "$(dirname "$0")" && pwd)
cd "$HIER/.."
[ -x "${JAVA_HOME:-}/bin/java" ] || JAVA_HOME=$(ls -d "$HOME"/.kieselstein/jdk/*jdk21* | head -1)
export JAVA_HOME
./gradlew -q --console=plain paket ${KS_CLIENT_LIB:+-PksClientLib=$KS_CLIENT_LIB}
mkdir -p "$HIER/paket"
find "$HIER/paket" -name 'kieselstein-basiskit-*.tar.gz' -delete
cp build/paket/kieselstein-basiskit-*.tar.gz "$HIER/paket/"
cd "$HIER"
npx -y @vscode/vsce package --skip-license

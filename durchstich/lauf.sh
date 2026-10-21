#!/usr/bin/env bash
# Durchstich bauen und gegen einen Kieselstein-Server laufen lassen.
# lauf.sh <server:port> <benutzer> <kennwort> [mandant] [recht]
set -euo pipefail
cd "$(dirname "$0")"
KS_CLIENT_LIB=${KS_CLIENT_LIB:-$HOME/.kieselstein/clients/2.0.0/lib}
JAVA_HOME=${JAVA_HOME:-$(ls -d "$HOME"/.kieselstein/jdk/*jdk21* | head -1)}
mkdir -p build
"$JAVA_HOME/bin/javac" -proc:none -cp "$KS_CLIENT_LIB/*" -d build src/at/freudenthaler/kieselstein/addon/durchstich/Durchstich.java
exec "$JAVA_HOME/bin/java" -cp "build:$KS_CLIENT_LIB/*" at.freudenthaler.kieselstein.addon.durchstich.Durchstich "$@"

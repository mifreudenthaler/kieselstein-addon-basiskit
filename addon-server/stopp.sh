#!/usr/bin/env bash
# Addon-Server anhalten – über die Verwaltungsschnittstelle, nur von dieser Maschine.
set -euo pipefail
ADDONS=$(cd "$(dirname "$0")" && pwd)
# shellcheck disable=SC1091
source "$ADDONS/config/addon-server.conf"
JDK=${ADDON_JDK:-$(ls -d "$HOME"/.kieselstein/jdk/*jdk21* 2>/dev/null | head -1)}
export JAVA_HOME="$JDK"
exec "$ADDONS/server/bin/jboss-cli.sh" --connect \
  --controller="localhost:$((9990 + ${PORT_VERSATZ:-200}))" --command=":shutdown"

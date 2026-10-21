#!/usr/bin/env bash
# Addon-Server starten. Liest config/addon-server.conf.
#   start.sh              im Vordergrund (z. B. für einen Dienst)
#   start.sh --hintergrund   losgelöst, Ausgabe nach logs/start.out
set -euo pipefail
ADDONS=$(cd "$(dirname "$0")" && pwd)
# shellcheck disable=SC1091
source "$ADDONS/config/addon-server.conf"

if [ "${1:-}" = "--hintergrund" ]; then
  if pgrep -f -- "-Dkieselstein.addons.dir=$ADDONS " >/dev/null; then
    echo "läuft bereits"
    exit 0
  fi
  setsid nohup "$0" > "$ADDONS/logs/start.out" 2>&1 < /dev/null &
  echo "gestartet"
  exit 0
fi

# JDK 21: Einstellung, sonst das von Kieselstein, sonst ~/.kieselstein/jdk, sonst java im Pfad
JDK=${ADDON_JDK:-}
[ -n "$JDK" ] || JDK=$(ls -d "$ADDONS"/../dist/jdk* "$ADDONS"/../jdk* 2>/dev/null | head -1 || true)
[ -n "$JDK" ] || JDK=$(ls -d "$HOME"/.kieselstein/jdk/*jdk21* 2>/dev/null | head -1 || true)
[ -n "$JDK" ] && export JAVA_HOME="$JDK"
NAECHSTE=${NAECHSTE_STUFE_URL:-${PRODUKTION_URL:-}}
export JAVA_OPTS="-Xms64m -Xmx1024m --enable-preview -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF8 \
 -Dkieselstein.addons.deployments=$ADDONS/deployments \
 -Dkieselstein.addons.dir=$ADDONS \
 -Dkieselstein.addons.stufe=${STUFE:-test} \
 -Dkieselstein.server=${KIESELSTEIN_SERVER:-localhost:8080} \
 -Dkieselstein.addons.naechste=$NAECHSTE \
 -Djboss.server.log.dir=$ADDONS/logs"
exec "$ADDONS/server/bin/standalone.sh" -c addon-standalone.xml -b 0.0.0.0 \
  -Djboss.socket.binding.port-offset="${PORT_VERSATZ:-200}"

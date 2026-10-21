#!/usr/bin/env bash
# Addon-Server anhalten – über die Verwaltungsschnittstelle, nur von dieser Maschine.
set -euo pipefail
ADDONS=$(cd "$(dirname "$0")" && pwd)
# shellcheck disable=SC1091
source "$ADDONS/config/addon-server.conf"
if ! pgrep -f -- "-Dkieselstein.addons.dir=$ADDONS " >/dev/null; then
  echo "läuft nicht"
  exit 0
fi
JDK=${ADDON_JDK:-}
[ -n "$JDK" ] || JDK=$(ls -d "$ADDONS"/../dist/jdk* "$ADDONS"/../jdk* 2>/dev/null | head -1 || true)
[ -n "$JDK" ] || JDK=$(ls -d "$HOME"/.kieselstein/jdk/*jdk21* 2>/dev/null | head -1 || true)
[ -n "$JDK" ] && export JAVA_HOME="$JDK"
"$ADDONS/server/bin/jboss-cli.sh" --connect \
  --controller="localhost:$((9990 + ${PORT_VERSATZ:-200}))" --command=":shutdown" >/dev/null
for i in $(seq 1 30); do
  pgrep -f -- "-Dkieselstein.addons.dir=$ADDONS " >/dev/null || { echo "angehalten"; exit 0; }
  sleep 1
done
echo "hält nicht an" >&2
exit 1

#!/usr/bin/env bash
# Addon-Server starten. Liest config/addon-server.conf. Läuft im Vordergrund;
# wer ihn im Hintergrund will, startet ihn mit nohup/setsid oder als Dienst.
set -euo pipefail
ADDONS=$(cd "$(dirname "$0")" && pwd)
# shellcheck disable=SC1091
source "$ADDONS/config/addon-server.conf"
JDK=${ADDON_JDK:-$(ls -d "$HOME"/.kieselstein/jdk/*jdk21* 2>/dev/null | head -1)}
export JAVA_HOME="$JDK"
export JAVA_OPTS="-Xms64m -Xmx1024m --enable-preview -Djava.net.preferIPv4Stack=true -Dfile.encoding=UTF8 \
 -Dkieselstein.addons.deployments=$ADDONS/deployments \
 -Dkieselstein.addons.dir=$ADDONS \
 -Dkieselstein.addons.stufe=${STUFE:-test} \
 -Dkieselstein.server=${KIESELSTEIN_SERVER:-localhost:8080} \
 -Dkieselstein.addons.produktion=${PRODUKTION_URL:-} \
 -Djboss.server.log.dir=$ADDONS/logs"
exec "$ADDONS/server/bin/standalone.sh" -c addon-standalone.xml -b 0.0.0.0 \
  -Djboss.socket.binding.port-offset="${PORT_VERSATZ:-200}"

#!/usr/bin/env bash
# Addon-Server neben einer Kieselstein-Installation einrichten.
#
#   einrichten.sh <kieselstein-ordner> <kieselstein-client-lib>
#
# <kieselstein-ordner>      der Ordner mit dist/ und data/ – daneben entsteht addons/
# <kieselstein-client-lib>  lib/ des Kieselstein-Clients derselben Version
#                           (daraus kommen die Schnittstellen der Fachlogik)
#
# Legt an:
#   addons/server/       WildFly in der Version von Kieselstein (eigener, unveränderter Download)
#   addons/deployments/  hier liegen die Addon-Pakete (WAR)
#   addons/archiv/ config/ logs/
#
# Kieselstein selbst wird nicht angefasst. Mehrfach aufrufbar: Vorhandenes bleibt,
# nur das Kieselstein-Modul wird bei jedem Lauf neu gesetzt (für eine neue Kieselstein-Version).
set -euo pipefail

KS=${1:?Kieselstein-Ordner fehlt}
LIB=${2:?Client-Lib-Ordner fehlt}
WILDFLY_VERSION=26.1.2.Final
WILDFLY_ARCHIV=${WILDFLY_ARCHIV:-$HOME/.cache/kieselstein-addon/wildfly-$WILDFLY_VERSION.tar.gz}
WILDFLY_URL=https://github.com/wildfly/wildfly/releases/download/$WILDFLY_VERSION/wildfly-$WILDFLY_VERSION.tar.gz
HIER=$(cd "$(dirname "$0")" && pwd)

[ -d "$KS/dist" ] || { echo "In $KS liegt kein dist/ – ist das eine Kieselstein-Installation?" >&2; exit 1; }
KS_VERSION=$(sed 's/-.*//' < "$KS/dist/VERSION.txt" | cut -d. -f1-3)
EJB_JAR=$(ls "$LIB"/kieselstein-ejb-*.jar | head -1)
COMMON_JAR=$(ls "$LIB"/kieselstein-common-*.jar | head -1)
[ -f "$EJB_JAR" ] && [ -f "$COMMON_JAR" ] || { echo "In $LIB fehlen kieselstein-ejb/-common." >&2; exit 1; }

ADDONS="$KS/addons"
SERVER="$ADDONS/server"
mkdir -p "$ADDONS"/{deployments,archiv,config,logs}

# 1. WildFly – unverändert aus dem offiziellen Archiv
if [ ! -d "$SERVER" ]; then
  if [ ! -f "$WILDFLY_ARCHIV" ]; then
    mkdir -p "$(dirname "$WILDFLY_ARCHIV")"
    echo "Lade WildFly $WILDFLY_VERSION …"
    curl -fsSL -o "$WILDFLY_ARCHIV" "$WILDFLY_URL"
  fi
  echo "Entpacke WildFly nach $SERVER"
  tmp=$(mktemp -d "$ADDONS/.wildfly-XXXX")
  tar -xzf "$WILDFLY_ARCHIV" -C "$tmp"
  mv "$tmp/wildfly-$WILDFLY_VERSION" "$SERVER"
  rmdir "$tmp"
fi

# 2. Konfiguration des Addon-Servers: eigene Kopie, Pakete aus addons/deployments
CONF="$SERVER/standalone/configuration/addon-standalone.xml"
if [ ! -f "$CONF" ]; then
  sed 's#<deployment-scanner path="deployments" relative-to="jboss.server.base.dir"#<deployment-scanner path="${kieselstein.addons.deployments}"#' \
    "$SERVER/standalone/configuration/standalone.xml" > "$CONF"
  grep -q 'kieselstein.addons.deployments' "$CONF" || { echo "Deployment-Scanner ließ sich nicht umstellen." >&2; exit 1; }
fi

# 3. Kieselstein-Schnittstellen als Modul. Nicht ins Addon packen: das ejb-jar enthält
#    auch die Beans, und WildFly würde sie in jedem Addon selbst starten wollen.
#    Die Datenobjekte ziehen weitere Bibliotheken nach sich (etwa die Bildverarbeitung
#    in com.lp.util.Helper). Deshalb kommen die Bibliotheken des Clients mit – außer dem,
#    was WildFly selbst mitbringt (Schnittstellen, EJB-/Naming-Client, Netzwerk) und der
#    Swing-Oberfläche des Clients.
#    Grober Zwischenstand: das sind rund 300 MB. Später auf das Nötige eingrenzen.
AUSGENOMMEN='^(wildfly|jboss|javax|jakarta|jaxb|undertow|xnio|netty|kieselstein-client)'
MOD="$SERVER/modules/kieselstein/client/main"
rm -rf "$MOD"; mkdir -p "$MOD"
WURZELN=""
for jar in "$LIB"/*.jar; do
  name=$(basename "$jar")
  [[ "$name" =~ $AUSGENOMMEN ]] && continue
  cp "$jar" "$MOD/"
  WURZELN+="        <resource-root path=\"$name\"/>"$'\n'
done
awk -v wurzeln="$WURZELN" '/@WURZELN@/ { printf "%s", wurzeln; next } { print }' "$HIER/module.xml" > "$MOD/module.xml"
echo "$KS_VERSION" > "$ADDONS/config/kieselstein-version"

# 4. Einstellungen – nur anlegen, nie überschreiben
[ -f "$ADDONS/config/addon-server.conf" ] || cat > "$ADDONS/config/addon-server.conf" <<CONF
# Addon-Server – Einstellungen dieser Installation
# Stufe: test oder produktion
STUFE=test
# Wo Kieselstein läuft (remote+http, wie der Client)
KIESELSTEIN_SERVER=localhost:8080
# Versatz zu den WildFly-Standardports: 200 -> http 8280, Verwaltung 10190
PORT_VERSATZ=200
# Java 21, wie Kieselstein (leer: das JDK 21 aus ~/.kieselstein/jdk)
ADDON_JDK=
CONF

cp "$HIER/start.sh" "$HIER/stopp.sh" "$ADDONS/"
chmod +x "$ADDONS/start.sh" "$ADDONS/stopp.sh"
echo "Addon-Server eingerichtet in $ADDONS (Kieselstein $KS_VERSION)."
echo "Start: $ADDONS/start.sh"

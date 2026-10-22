#!/usr/bin/env bash
# Basiskit neben einer Kieselstein-Installation einrichten oder aktualisieren:
# Addon-Server (WildFly) und Addon-Zentrale.
#
#   einrichten.sh <kieselstein-ordner> [Optionen]
#
#   --stufe entwicklung|test|produktion   Stufe dieses Addon-Servers (Vorgabe beim ersten Mal: test)
#   --kieselstein-server host:port        wo Kieselstein läuft (Vorgabe: localhost:8080)
#   --naechste-stufe URL                  Zentrale der nächsten Stufe, z. B. http://erp-prod:8280
#   --port-versatz N                      Versatz zu den WildFly-Ports (Vorgabe 200 → 8280)
#   --schluessel-datei DATEI              gemeinsamer Freigabe-Schlüssel der Kette
#   --client-lib ORDNER                   Kieselstein-Client-Bibliotheken (Vorgabe: aus dist/clients)
#
# Legt neben dist/ und data/ den Ordner addons/ an. Kieselstein selbst wird
# nicht angefasst. Mehrfach aufrufbar: Einstellungen, Addons, Protokolle und
# Messwerte bleiben; Zentrale und Kieselstein-Modul werden auf den Stand dieses
# Pakets gebracht. Am Ende steht eine Zeile „OK …" – darauf wartet die
# VS-Code-Erweiterung.
set -euo pipefail

HIER=$(cd "$(dirname "$0")" && pwd)
KS=${1:?Aufruf: einrichten.sh <kieselstein-ordner> [Optionen]}
shift
STUFE="" KS_SERVER="" NAECHSTE="" VERSATZ="" SCHLUESSEL="" LIB=""
while [ $# -gt 0 ]; do
  case "$1" in
    --stufe) STUFE=$2; shift 2 ;;
    --kieselstein-server) KS_SERVER=$2; shift 2 ;;
    --naechste-stufe) NAECHSTE=$2; shift 2 ;;
    --port-versatz) VERSATZ=$2; shift 2 ;;
    --schluessel-datei) SCHLUESSEL=$2; shift 2 ;;
    --client-lib) LIB=$2; shift 2 ;;
    *) echo "Unbekannte Angabe: $1" >&2; exit 2 ;;
  esac
done
case "${STUFE:-test}" in entwicklung|test|produktion) ;; *) echo "Stufe: entwicklung, test oder produktion" >&2; exit 2 ;; esac

WILDFLY_VERSION=26.1.2.Final
WILDFLY_ARCHIV=${WILDFLY_ARCHIV:-$HOME/.cache/kieselstein-addon/wildfly-$WILDFLY_VERSION.tar.gz}
WILDFLY_URL=https://github.com/wildfly/wildfly/releases/download/$WILDFLY_VERSION/wildfly-$WILDFLY_VERSION.tar.gz
BASISKIT_VERSION=$(cat "$HIER/VERSION" 2>/dev/null || echo entwicklung)

# Die Zentrale: im Paket neben diesem Skript, im Repo aus dem Gradle-Bau
ZENTRALE_WAR="$HIER/zentrale.war"
[ -f "$ZENTRALE_WAR" ] || ZENTRALE_WAR="$HIER/../zentrale/build/libs/zentrale.war"
[ -f "$ZENTRALE_WAR" ] || { echo "zentrale.war fehlt (Paket unvollständig?)" >&2; exit 1; }

[ -f "$KS/dist/VERSION.txt" ] || { echo "In $KS liegt kein dist/VERSION.txt – ist das der Kieselstein-Ordner?" >&2; exit 1; }
KS_VERSION=$(sed 's/-.*//' < "$KS/dist/VERSION.txt" | cut -d. -f1-3)

ADDONS="$KS/addons"
SERVER="$ADDONS/server"
CONF="$ADDONS/config/addon-server.conf"
mkdir -p "$ADDONS"/{deployments,archiv,config,logs}

# 1. Kieselstein-Client-Bibliotheken: aus der Installation selbst (dist/clients)
AUFRAEUMEN=""
if [ -z "$LIB" ]; then
  CLIENT_TGZ=$(ls "$KS"/dist/clients/kieselstein-client-*.tar.gz 2>/dev/null | head -1 || true)
  [ -n "$CLIENT_TGZ" ] || { echo "Kein Kieselstein-Client unter $KS/dist/clients – bitte --client-lib angeben" >&2; exit 1; }
  AUFRAEUMEN=$(mktemp -d)
  tar -xzf "$CLIENT_TGZ" -C "$AUFRAEUMEN" --wildcards '*/lib/*.jar'
  LIB=$(dirname "$(ls "$AUFRAEUMEN"/*/lib/kieselstein-ejb-*.jar | head -1)")
fi
trap '[ -n "$AUFRAEUMEN" ] && rm -rf "$AUFRAEUMEN"' EXIT
ls "$LIB"/kieselstein-ejb-*.jar >/dev/null 2>&1 || { echo "In $LIB fehlt kieselstein-ejb-*.jar" >&2; exit 1; }

# 2. WildFly – unverändert aus dem offiziellen Archiv
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

# 3. Konfiguration des Addon-Servers: Pakete aus addons/deployments
SXML="$SERVER/standalone/configuration/addon-standalone.xml"
if [ ! -f "$SXML" ]; then
  sed 's#<deployment-scanner path="deployments" relative-to="jboss.server.base.dir"#<deployment-scanner path="${kieselstein.addons.deployments}"#' \
    "$SERVER/standalone/configuration/standalone.xml" > "$SXML"
  grep -q 'kieselstein.addons.deployments' "$SXML" || { echo "Deployment-Scanner ließ sich nicht umstellen." >&2; exit 1; }
fi

# 4. Kieselstein-Schnittstellen als Modul (nicht ins Addon: das ejb-jar enthält auch die Beans).
#    Mit dabei alle Client-Bibliotheken außer dem, was WildFly oder Java selbst mitbringen.
#    xml-apis und stax-api enthalten org.w3c.dom bzw. javax.xml.stream noch einmal; liegen sie im
#    Modul, passen die Typen der Fassaden (z. B. ArtikelFac, AuftragFac) nicht mehr zu denen des
#    Addons, und der Aufruf scheitert mit „org.w3c.dom.Document … not visible from class loader".
AUSGENOMMEN='^(wildfly|jboss|javax|jakarta|jaxb|undertow|xnio|netty|kieselstein-client|xml-apis-[0-9]|stax-api)'
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

# 5. Einstellungen dieses Addon-Servers – anlegen, und nur Angegebenes ändern
[ -f "$CONF" ] || cat > "$CONF" <<CONF
# Addon-Server – Einstellungen dieser Installation
# Stufe: entwicklung, test oder produktion
STUFE=test
# Wo Kieselstein läuft (remote+http, wie der Client)
KIESELSTEIN_SERVER=localhost:8080
# Versatz zu den WildFly-Standardports: 200 -> http 8280, Verwaltung 10190
PORT_VERSATZ=200
# Java 21, wie Kieselstein (leer: das JDK von Kieselstein bzw. ~/.kieselstein/jdk)
ADDON_JDK=
# Zentrale der nächsten Stufe (Test -> Produktion), leer in der Produktion
NAECHSTE_STUFE_URL=
CONF
# frühere Fassungen hießen PRODUKTION_URL
sed -i 's/^PRODUKTION_URL=/NAECHSTE_STUFE_URL=/' "$CONF"
setzen() { # <schlüssel> <wert>
  if grep -q "^$1=" "$CONF"; then sed -i "s#^$1=.*#$1=$2#" "$CONF"; else echo "$1=$2" >> "$CONF"; fi
}
[ -n "$STUFE" ] && setzen STUFE "$STUFE"
[ -n "$KS_SERVER" ] && setzen KIESELSTEIN_SERVER "$KS_SERVER"
[ -n "$VERSATZ" ] && setzen PORT_VERSATZ "$VERSATZ"
[ -n "$NAECHSTE" ] && setzen NAECHSTE_STUFE_URL "$NAECHSTE"
[ "$STUFE" = "produktion" ] && setzen NAECHSTE_STUFE_URL ""

# 6. Freigabe-Schlüssel der Kette: übernehmen, sonst einmal erzeugen
if [ -n "$SCHLUESSEL" ]; then
  (umask 077; tr -d ' \n' < "$SCHLUESSEL" > "$ADDONS/config/freigabe.schluessel")
elif [ ! -f "$ADDONS/config/freigabe.schluessel" ]; then
  (umask 077; head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$ADDONS/config/freigabe.schluessel")
fi

# 7. Skripte
for s in start.sh stopp.sh status.sh; do cp "$HIER/$s" "$ADDONS/$s"; chmod +x "$ADDONS/$s"; done

# 8. Zentrale: einsetzen, wenn sie fehlt oder dieses Paket eine andere hat
ZIEL="$ADDONS/deployments/zentrale.war"
if [ ! -f "$ZIEL" ] || ! cmp -s "$ZENTRALE_WAR" "$ZIEL"; then
  if [ -f "$ZIEL" ]; then
    cp "$ZIEL" "$ADDONS/archiv/zentrale-$(cat "$ADDONS/config/basiskit-version" 2>/dev/null || echo alt)-$(date +%Y%m%d-%H%M%S).war"
    rm -f "$ZIEL.deployed" "$ZIEL.failed"
  fi
  cp "$ZENTRALE_WAR" "$ADDONS/deployments/.zentrale.war.neu"
  mv "$ADDONS/deployments/.zentrale.war.neu" "$ZIEL"
  touch "$ZIEL.dodeploy"
  echo "Zentrale eingesetzt."
fi
echo "$BASISKIT_VERSION" > "$ADDONS/config/basiskit-version"

STUFE_JETZT=$(sed -n 's/^STUFE=//p' "$CONF")
echo "OK basiskit=$BASISKIT_VERSION kieselstein=$KS_VERSION stufe=$STUFE_JETZT ordner=$ADDONS"

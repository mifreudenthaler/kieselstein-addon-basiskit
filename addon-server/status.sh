#!/usr/bin/env bash
# Stand dieses Addon-Servers in einer Zeile je Angabe – für die VS-Code-Erweiterung.
ADDONS=$(cd "$(dirname "$0")" && pwd)
conf() { sed -n "s/^$1=//p" "$ADDONS/config/addon-server.conf" 2>/dev/null; }
echo "basiskit=$(cat "$ADDONS/config/basiskit-version" 2>/dev/null)"
echo "kieselstein=$(cat "$ADDONS/config/kieselstein-version" 2>/dev/null)"
echo "stufe=$(conf STUFE)"
echo "versatz=$(conf PORT_VERSATZ)"
echo "naechste=$(conf NAECHSTE_STUFE_URL)"
if pgrep -f -- "-Dkieselstein.addons.dir=$ADDONS " >/dev/null; then echo "laeuft=ja"; else echo "laeuft=nein"; fi
for w in "$ADDONS"/deployments/*.war; do
  [ -e "$w" ] || continue
  n=$(basename "$w" .war)
  z=startet
  [ -e "$w.deployed" ] && z=laeuft
  [ -d "$w" ] && [ -e "$w.deployed" ] && z=live
  [ -e "$w.failed" ] && z=fehler
  { [ -e "$w.skipdeploy" ] || [ -e "$w.undeployed" ]; } && z=abgeschaltet
  echo "addon=$n:$z"
done

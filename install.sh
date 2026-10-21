#!/usr/bin/env bash
# Kieselstein-Addon-Erweiterung in VS Code installieren (oder aktualisieren).
#
#   curl -fsSL https://raw.githubusercontent.com/mifreudenthaler/kieselstein-addon-basiskit/main/install.sh | bash
#
# Holt die neueste Erweiterung aus den GitHub-Releases und installiert sie mit
# dem VS-Code-Befehl „code". In einem Terminal von VS Code Remote-SSH landet
# sie damit auf dem Server, mit dem VS Code gerade verbunden ist.
# Das Installationspaket für Addon-Server und Zentrale steckt in der Erweiterung.
set -euo pipefail

REPO=${KS_REPO:-mifreudenthaler/kieselstein-addon-basiskit}

CODE=""
for c in code code-insiders codium; do
  if command -v "$c" >/dev/null 2>&1; then CODE=$c; break; fi
done
if [ -z "$CODE" ]; then
  echo "VS Code wurde nicht gefunden (Befehl „code\")." >&2
  echo "Bitte VS Code installieren: https://code.visualstudio.com/ – und dieses Skript erneut ausführen." >&2
  exit 1
fi

echo "Suche die neueste Fassung …"
URL=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" \
  | grep -o '"browser_download_url": *"[^"]*\.vsix"' | head -1 | sed 's/.*"\(https[^"]*\)"/\1/')
[ -n "$URL" ] || { echo "Keine Erweiterung im neuesten Release von $REPO gefunden." >&2; exit 1; }

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
DATEI="$TMP/$(basename "$URL")"
echo "Lade $(basename "$URL") …"
curl -fsSL -o "$DATEI" "$URL"

# KS_CODE_ARGS: zusätzliche Angaben für „code", z. B. ein eigener Erweiterungsordner zum Testen
# shellcheck disable=SC2086
"$CODE" ${KS_CODE_ARGS:-} --install-extension "$DATEI" --force
echo
echo "Fertig. In VS Code links auf das Kieselstein-Symbol klicken und „Server eintragen\" wählen."

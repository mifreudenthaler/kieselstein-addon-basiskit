#!/usr/bin/env bash
# Kieselstein-Addon-Erweiterung installieren oder aktualisieren – für VS Code, Cursor und VSCodium.
#
#   curl -fsSL https://raw.githubusercontent.com/mifreudenthaler/kieselstein-addon-basiskit/main/install.sh | bash
#
# Holt die neueste Erweiterung aus den GitHub-Releases und installiert sie in
# jeden gefundenen Editor (code, cursor, codium). Nur einen bestimmten:
# KS_EDITOR=cursor. In einem Terminal von Remote-SSH landet sie auf dem Server,
# mit dem der Editor gerade verbunden ist.
# Das Installationspaket für Addon-Server und Zentrale steckt in der Erweiterung.
set -euo pipefail

REPO=${KS_REPO:-mifreudenthaler/kieselstein-addon-basiskit}

EDITOREN=()
for c in ${KS_EDITOR:-code cursor codium code-insiders}; do
  command -v "$c" >/dev/null 2>&1 && EDITOREN+=("$c")
done
if [ ${#EDITOREN[@]} -eq 0 ]; then
  echo "Kein Editor gefunden (code, cursor oder codium)." >&2
  echo "Bitte VS Code (https://code.visualstudio.com/), Cursor (https://cursor.com/) oder VSCodium (https://vscodium.com/)" >&2
  echo "installieren – und diese Zeile erneut ausführen." >&2
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

for e in "${EDITOREN[@]}"; do
  echo "Installiere in $e …"
  # KS_CODE_ARGS: zusätzliche Angaben, z. B. ein eigener Erweiterungsordner zum Testen
  # shellcheck disable=SC2086
  "$e" ${KS_CODE_ARGS:-} --install-extension "$DATEI" --force
done
echo
echo "Fertig. Im Editor links auf das Kieselstein-Symbol klicken und „Server eintragen\" wählen."

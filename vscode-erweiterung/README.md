# Kieselstein-Addon-Entwicklung (VS Code)

Seitenleiste **Kieselstein-Addons** (Symbol links): die Server der Kette mit dem Stand ihrer Zentrale.

## Beim Kunden einrichten

1. VS Code installieren, diese Erweiterung installieren (`code --install-extension kieselstein-addon-<version>.vsix`).
2. **Server eintragen** – Standard: **Test** und **Produktion**, zusätzlich auf Wunsch **Entwicklung**.
   Je Server: SSH-Zugang (`benutzer@rechner`, leer = diese Maschine), Kieselstein-Ordner (mit `dist/` und `data/`),
   Port-Versatz (200 → 8280) und die Adresse der Zentrale.
3. In der Seitenleiste steht dann z. B. „Zentrale nicht installiert". **Basiskit einrichten** spielt Addon-Server
   und Zentrale auf (WildFly wird bei Bedarf mitgebracht – Kundenserver brauchen kein Internet), stellt die Stufe ein,
   trägt die nächste Stufe ein, verteilt den gemeinsamen Freigabe-Schlüssel und startet den Addon-Server.
   Derselbe Befehl aktualisiert später auf eine neue Basiskit-Version.

## Addons

- **Neues Addon anlegen** – aus `vorlage/`
- **Addon bauen und in den Test laden** – lädt immer in die **erste Stufe**: Entwicklung, wenn eingetragen, sonst Test
- **Tests**, **Addon im Browser öffnen**, **Fassade nachschlagen**

Weiter geht ein Addon nur über **„In … ausrollen"** in der Verwaltung der Zentrale der vorigen Stufe – durch einen
Menschen. Die Erweiterung bringt in die Produktion nur das Basiskit, nie ein Addon. Protokolle und Messwerte stehen
in der Zentrale.

## Bauen und prüfen

- `./bauen.sh` – Basiskit-Paket erzeugen, mitliefern, VSIX packen
- `xvfb-run -a node test/lauf.js <projektordner> <einstellungen.json>` – startet ein echtes VS Code und prüft
  Befehle, Seitenleiste, Laden in die erste Stufe, Einrichten und Schlüsselübernahme
  (braucht `@vscode/test-electron`, Pfad über `TEST_ELECTRON`)

Noch offen: Kundenserver mit Windows (Skripte als PowerShell-Fassung).

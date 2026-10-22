# Kieselstein-Addon-Entwicklung Basiskit

```bash
curl -fsSL https://raw.githubusercontent.com/mifreudenthaler/kieselstein-addon-basiskit/main/install.sh | bash
```

Windows (PowerShell):

```powershell
irm https://raw.githubusercontent.com/mifreudenthaler/kieselstein-addon-basiskit/main/install.ps1 | iex
```

Installiert die Erweiterung in **VS Code, Cursor und VSCodium** – in jeden Editor, der gefunden wird.

Das Basiskit macht es leicht, Zusatzprogramme (Addons) für Kieselstein ERP zu bauen und beim Kunden zu betreiben, ohne Kieselstein selbst anzufassen. Die Addons laufen in einem eigenen Addon-Server neben Kieselstein, sprechen Kieselstein nur über seine Fachlogik an, nutzen dieselbe Anmeldung und dieselben Rechte wie der Client und erscheinen gemeinsam in der Addon-Zentrale. Dort werden sie überwacht, eingestellt und von Stufe zu Stufe ausgerollt: Test → Produktion, auf Wunsch mit Entwicklung davor.

## Installation beim Kunden

- Editor installieren: VS Code, Cursor oder VSCodium
- Erweiterung installieren: Befehl oben im Terminal des Editors ausführen
- Seitenleiste „Kieselstein-Addons" → **Server eintragen**: Test und Produktion (Entwicklung optional), je SSH-Zugang und Kieselstein-Ordner
- Je Server **Basiskit einrichten** → Addon-Server und Zentrale werden installiert, verbunden und gestartet; ein später eingerichteter Server wird automatisch mit seiner Vorstufe verbunden
- Zentrale im Browser öffnen, mit dem Kieselstein-Benutzer anmelden
- **Neues Addon anlegen**: jedes Addon ist ein eigenes Projekt mit eigenem Git-Repository (Basisschicht als Bibliothek darin), angelegt unter `addons/projekte/` der ersten Stufe
- Entwickeln im **Live-Modus** (Flamme im Editor): Seiten in rund 2 s, Java in rund 3 s im Browser, die Seite lädt von selbst neu
- Addon bauen und in den Test laden (Editor), in die Produktion über **In Produktion ausrollen** in der Zentrale

Neue Fassungen meldet die Erweiterung selbst. Technische Details: [docs/technik.md](docs/technik.md)

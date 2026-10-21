# Kieselstein-Addon-Entwicklung Basiskit

Das Basiskit macht es leicht, Zusatzprogramme (Addons) für Kieselstein ERP zu bauen und beim Kunden zu betreiben, ohne Kieselstein selbst anzufassen. Die Addons laufen in einem eigenen Addon-Server neben Kieselstein, sprechen Kieselstein nur über seine Fachlogik an, nutzen dieselbe Anmeldung und dieselben Rechte wie der Client und erscheinen gemeinsam in der Addon-Zentrale. Dort werden sie überwacht, eingestellt und von Stufe zu Stufe ausgerollt: Test → Produktion, auf Wunsch mit Entwicklung davor.

## Installation beim Kunden

- VS Code installieren
- Erweiterung installieren – im Terminal:
  `curl -fsSL https://raw.githubusercontent.com/mifreudenthaler/kieselstein-addon-basiskit/main/install.sh | bash`
  (Windows, PowerShell: `irm https://raw.githubusercontent.com/mifreudenthaler/kieselstein-addon-basiskit/main/install.ps1 | iex`)
- Seitenleiste „Kieselstein-Addons" → **Server eintragen**: Test und Produktion (Entwicklung optional), je SSH-Zugang und Kieselstein-Ordner
- Je Server **Basiskit einrichten** → Addon-Server und Zentrale werden installiert, verbunden und gestartet
- Zentrale im Browser öffnen, mit dem Kieselstein-Benutzer anmelden
- Addon bauen und in den Test laden (VS Code), in die Produktion über **In Produktion ausrollen** in der Zentrale

Neue Fassungen meldet die Erweiterung selbst. Technische Details: [docs/technik.md](docs/technik.md)

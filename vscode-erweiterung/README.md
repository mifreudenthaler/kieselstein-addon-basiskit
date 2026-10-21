# Kieselstein-Addon-Entwicklung (VS Code)

Handgriffe bis zum **Test**-Addon-Server – für die Arbeit auf dem Testserver beim Kunden
(VS Code über Remote-SSH) oder auf einem eigenen Entwicklungsserver.

Befehle (Strg+Umschalt+P, „Kieselstein: …"):

- **Neues Addon anlegen** – aus `vorlage/`, trägt es in `settings.gradle` ein
- **Addon bauen und in den Test laden** – Gradle-Bau, WAR in `addons/deployments`, wartet auf den Start (auch als Knopf oben im Editor)
- **Tests des Addons ausführen**
- **Addon im Browser öffnen**, **Freigabe öffnen** (Verwaltung der Test-Zentrale)
- **Protokoll des Addon-Servers** – läuft mit
- **Fassade nachschlagen** – alle `*Fac`-Schnittstellen der Kieselstein-Version mit ihren Methoden

Statusleiste: Stufe und laufende Addons des Test-Addon-Servers.

In die Produktion führt hier **kein** Weg: Die Erweiterung lädt nur in einen Addon-Server mit
`STUFE=test`. Ausgerollt wird in der Verwaltung der Test-Zentrale, von einem Menschen.

Einstellungen: `kieselsteinAddon.addonsOrdner` (Pflicht), `kieselsteinAddon.adresse`,
`kieselsteinAddon.clientLib`, `kieselsteinAddon.jdk`.

Paket bauen: `npx @vscode/vsce package` in diesem Ordner, installieren mit
`code --install-extension kieselstein-addon-0.1.0.vsix`.

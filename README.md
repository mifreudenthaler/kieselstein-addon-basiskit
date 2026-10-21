# Kieselstein-Addon-Entwicklung Basiskit

Alles, was man braucht, um ein Kieselstein-Addon zu bauen, zu testen und auszurollen:
Addon-Server, Basisschicht, Addon-Zentrale, Vorlage, VS-Code-Erweiterung und Dokumentation.
Der Vorschlag dazu steht in Nexus (Kieselstein / Entwicklung, „Kieselstein-Addon-Entwicklung Basiskit").

## Stufen und Installation

- Kette: **Test → Produktion**, auf Wunsch zusätzlich **Entwicklung** davor. VS Code lädt Addons nur in die erste
  Stufe; jeder weitere Schritt ist „In … ausrollen" in der Zentrale der vorigen Stufe (signiert, mit Rückfall).
- Installationspaket: `./gradlew paket` → `build/paket/kieselstein-basiskit-<version>.tar.gz`
  (einrichten.sh, start.sh, stopp.sh, status.sh, module.xml, zentrale.war, VERSION).
- Eingerichtet wird über die VS-Code-Erweiterung (`vscode-erweiterung/`, Seitenleiste „Kieselstein-Addons"),
  von Hand ginge es so: `bash einrichten.sh <kieselstein-ordner> --stufe test --naechste-stufe http://erp-prod:8280`.
  Die Kieselstein-Client-Bibliotheken kommen aus `dist/clients` der Installation selbst.

## Aufbau

- `addon-server/` – richtet neben einer Kieselstein-Installation den Ordner `addons/` ein
  (WildFly 26.1.2 aus dem offiziellen Archiv, eigene Konfiguration, Kieselstein-Schnittstellen
  als Modul `kieselstein.client`, Start- und Stoppskript):

  ```
  addon-server/einrichten.sh ~/.kieselstein/versionen/2.0.0 ~/.kieselstein/clients/2.0.0/lib
  ~/.kieselstein/versionen/2.0.0/addons/start.sh      # http://<rechner>:8280/
  ```

- `basis/` – die Basisschicht: Weg zu Kieselstein (`Kieselstein.fassade`, `Kieselstein.rufe`),
  Anmeldung und Rechte (`Sitzung`, Rechte über TheJudgeFac am Server).
- `basis/` bringt auch den gemeinsamen Rahmen im Kieselstein-Design mit
  (`/basiskit/rahmen.xhtml`, Farben aus dem Kieselstein-Logo) und `Liste` für Kieselstein-Listen
  über den FastLaneReader – dieselben Spalten, Status und Einschränkungen wie im Client.
- `zentrale/` – die Addon-Zentrale (PrimeFaces): Anmeldung mit Kieselstein-Benutzer, Startseite
  mit den installierten Addons (Titel/Beschreibung aus `WEB-INF/classes/addon.properties` des Addons),
  Stufe Test/Produktion, Rechteprobe.
- `zentrale/` Verwaltung (`/verwaltung.xhtml`, oben rechts verlinkt, nur mit LP_SYSTEM_CUD):
  Reiter Addons (Karten mit Test/Produktion-Vergleich, neu starten, abschalten, Einstellungen,
  In Produktion ausrollen …), Ressourcen (je Addon Anfragen, Dauer davon Kieselstein, Rechenzeit,
  Speicher mit Anteil, langsamste Anfragen, Addon-Server, Speicheraufnahme), Protokolle (alle Addons
  plus Fehler/Warnungen des Addon-Servers, filterbar) und Verlauf (`addons/config/verlauf.log`).
- Einstellungen: Jedes Addon beschreibt sie in `src/main/resources/einstellungen.properties`
  (titel, hilfe, art text/zahl/schalter/auswahl/geheim, vorgabe, min/max, werte). Die Verwaltung
  zeigt daraus ein Formular und prüft die Eingaben; gespeichert werden nur Abweichungen in
  `addons/config/<addon>.properties`. Im Addon: `Einstellungen.fuer("auftraege").zahl("zeilen_je_seite")`.
- Messung und Protokoll (automatisch über `META-INF/web-fragment.xml` der Basisschicht):
  `addons/logs/messung/<addon>.tsv` (je Minute, 24 h), `addons/logs/addons/<addon>.log`.
  Eigene Einträge: `Protokoll.info/warnung/fehler(...)`. Schutz: höchstens
  `max_gleichzeitige_anfragen` (Vorgabe 25) gleichzeitig, darüber 503.
  Freigabe Test → Produktion: Die Test-Zentrale schickt das laufende WAR (Byte für Byte) an die
  Produktions-Zentrale (`PRODUKTION_URL`), signiert mit HMAC über `config/freigabe.schluessel`
  (bei der Einrichtung einmal von Test nach Produktion kopieren). Die Produktion prüft Stufe,
  Signatur, Zeit (±10 min) und Prüfsumme, legt das laufende Paket ins Archiv, setzt das neue ein
  und fällt bei gescheitertem Start von selbst auf das vorige zurück. „Zurück auf …" in der
  Produktions-Verwaltung. Die Zentrale selbst wird nicht über die Freigabe getauscht.
- `auftraege/` – erstes Addon: alle Aufträge mit aktuellem Status, seitenweise wie im Client
  (FastLaneReader, Anwendungsfall Auftrag), Leserecht AUFT_AUFTRAG_R.

Anmeldung einmal für alle: Die Zentrale meldet bei Kieselstein an und legt den Sitzungsschlüssel
in das Cookie `KS_ADDON_SITZUNG` (Pfad `/`). Jedes Addon holt damit dieselbe Anmeldung über
`TheClientFac.theClientFindByUserLoggedIn` ab, wie die REST-Schnittstelle. Abmelden macht den
Schlüssel bei Kieselstein ungültig, für alle Addons zugleich.

  ```
  ./gradlew :zentrale:war      # JAVA_HOME auf ein JDK 21
  ```

Warum die Kieselstein-Schnittstellen ein Modul sind und nicht im Addon liegen: Das ejb-jar
enthält auch die Beans. Läge es im WAR, würde WildFly die Kieselstein-Fachlogik im Addon selbst
starten wollen. Die Datenobjekte ziehen weitere Bibliotheken nach sich, deshalb nimmt das Modul
vorerst alle Client-Bibliotheken außer dem, was WildFly selbst mitbringt (grober Zwischenstand, ~300 MB).

## Stand

- `durchstich/` – Schritt 1: von außen über remote+http anmelden, eine Fassade lesen,
  ein Recht am Server abfragen (TheJudgeFac), abmelden. Schreibt nichts.

  ```
  durchstich/lauf.sh localhost:8080 Admin <kennwort> [mandant] [recht]
  ```

  Braucht die Client-Bibliotheken der Kieselstein-Version (Vorgabe: ~/.kieselstein/clients/2.0.0/lib)
  und ein JDK 21.

  Ergebnis am 21.10.2026 gegen den lokalen Kieselstein 2.0.0 (Datenbank BGtech):

  ```
  Anmeldung     ok  (848 ms) Benutzer Admin|addon-durchstich|, Mandant 001
  Fassade lesen ok  (27 ms) Mandant 001: BGtech Metallbau GmbH
  Recht am Server ok (6 ms) LP_DARF_PREISE_SEHEN_VERKAUF: ja
  Abmeldung     ok
  ```

  Ein falsches Kennwort weist der Server mit EJBExceptionLP ab.

  Wichtig für die Basisschicht: Die Anmeldung erwartet den Benutzer als `name|rechner|`,
  das Kennwort als MD5 über Name + Kennwort und die Client-Version (hier `2.0.0`).
  Nachgeschlagen wird wie im Client: `lpserver/ejb/<Schnittstelle>Bean!<voller Name>`.

# Kieselstein-Addon-Entwicklung Basiskit

Alles, was man braucht, um ein Kieselstein-Addon zu bauen, zu testen und auszurollen:
Addon-Server, Basisschicht, Addon-Zentrale, Vorlage, VS-Code-Erweiterung und Dokumentation.
Der Vorschlag dazu steht in Nexus (Kieselstein / Entwicklung, „Kieselstein-Addon-Entwicklung Basiskit").

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

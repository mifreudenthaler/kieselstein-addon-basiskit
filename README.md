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

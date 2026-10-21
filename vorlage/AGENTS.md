# Addon „__TITEL__" – Hinweise für Agenten

Dieses Addon läuft im Kieselstein-Addon-Server und ist mit dem Basiskit gebaut.

## Wo was hingehört

- Fachlogik: in `src/main/java/.../__PAKET__/`. Reine Logik ohne Kieselstein in eigene Klassen (wie `Texte`), damit sie Tests ohne Server haben.
- Seiten: `src/main/webapp/*.xhtml`, immer mit `template="/basiskit/rahmen.xhtml"`. Kein eigenes CSS, keine eigenen Farben.
- Beschreibung für die Zentrale: `src/main/resources/addon.properties` (titel, beschreibung).

## Was das Basiskit schon kann – benutzen, nicht nachbauen

- `Sitzung`: Anmeldung (kommt von der Zentrale), `hatRecht("…")` am Kieselstein-Server.
- `Kieselstein.fassade(XFac.class)` und `Kieselstein.rufe(() -> …)`: Aufrufe in die Kieselstein-Fachlogik.
- `Liste`: Listen wie im Client (FastLaneReader), z. B. `new Liste(QueryParameters.UC_ID_AUFTRAG, sitzung.getClient())`.
- `Einstellungen.fuer("__NAME__")`: Einstellungen dieser Installation, gepflegt in der Verwaltung der Zentrale.

## Regeln

- **Nie SQL schreiben.** Lesen über Listen oder Fassaden, schreiben nur über Fassaden.
- **Jede Funktion prüft ein Kieselstein-Recht** (`sitzung.hatRecht`) – dasselbe, das der Client an der Stelle prüft. Im Kieselstein-Quellcode nachsehen, welches.
- **Wie Kieselstein es selbst macht:** im Quellcode die Fassade und Methode suchen, die der Client für den Vorgang benutzt (`kieselstein-client/.../delegate/*Delegate.java`).
- **Nur bis zum Test.** In die Produktion kommt ein Addon ausschließlich über „Für Produktion freigeben" in der Test-Zentrale, durch einen Menschen.

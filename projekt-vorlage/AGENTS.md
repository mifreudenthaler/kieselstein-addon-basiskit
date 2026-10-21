# Kieselstein-Addons – Hinweise für Agenten

Jedes Unterverzeichnis außer `basis/` ist ein Addon mit eigener `AGENTS.md`. Dort steht, was das Basiskit
schon kann und welche Regeln gelten (kein SQL, Rechte prüfen, nur über Fassaden schreiben).

- `basis/` nicht ändern – das ist die Basisschicht, sie wird von der Erweiterung aktualisiert.
- Kieselstein-Schnittstellen: `.kieselstein-client/lib/kieselstein-ejb-*.jar` (Fassaden `*Fac`, Datenobjekte `*Dto`).
- Bauen: `./gradlew :<addon>:war`, Tests: `./gradlew :<addon>:test`.
- Ist der Live-Modus an, übernimmt die Erweiterung jede gespeicherte Datei von selbst.

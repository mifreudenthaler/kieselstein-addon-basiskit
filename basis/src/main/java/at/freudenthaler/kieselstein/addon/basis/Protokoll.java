package at.freudenthaler.kieselstein.addon.basis;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

/**
 * Das Protokoll eines Addons.
 *
 * Jedes Addon schreibt in seine eigene Datei
 * {@code addons/logs/addons/<addon>.log}, eine Zeile je Eintrag (Zeit, Stufe,
 * Benutzer, Anfrage, Text, Ausnahme – durch Tabulator getrennt). Die Zentrale
 * zeigt alle Protokolle zusammen, filterbar nach Addon und Stufe.
 *
 * Für Addons:
 * <pre>
 *   Protokoll.info("312 Kunden übernommen");
 *   Protokoll.warnung("Kunde ohne E-Mail: " + nr);
 *   Protokoll.fehler("Import abgebrochen", e);
 * </pre>
 * Unbehandelte Fehler, abgewiesene und langsame Anfragen und gescheiterte
 * Kieselstein-Aufrufe schreibt die Basisschicht selbst hinein.
 *
 * Die Datei wird bei 5 MB umbenannt (.1) und neu begonnen.
 */
public final class Protokoll {

    public enum Stufe { INFO, WARNUNG, FEHLER }

    private static final long MAX_GROESSE = 5L * 1024 * 1024;
    private static volatile String addon = "unbekannt";
    private static final ThreadLocal<String> BENUTZER = new ThreadLocal<>();
    private static final ThreadLocal<String> ANFRAGE = new ThreadLocal<>();

    private Protokoll() {
    }

    /** Der Name des Addons dieses Pakets – gesetzt beim Start (Basiskit). */
    static void addon(String name) {
        addon = name;
    }

    public static String addon() {
        return addon;
    }

    static void anfrage(String pfad) {
        if (pfad == null) {
            ANFRAGE.remove();
        } else {
            ANFRAGE.set(pfad);
        }
    }

    static void benutzer(String name) {
        if (name == null) {
            BENUTZER.remove();
        } else {
            BENUTZER.set(name);
        }
    }

    static String aktuellerBenutzer() {
        return BENUTZER.get();
    }

    public static void info(String text) {
        schreiben(Stufe.INFO, text, null);
    }

    public static void warnung(String text) {
        schreiben(Stufe.WARNUNG, text, null);
    }

    public static void fehler(String text, Throwable t) {
        schreiben(Stufe.FEHLER, text, t);
    }

    public static Path ordner() {
        return Path.of(System.getProperty("kieselstein.addons.dir", "."), "logs", "addons");
    }

    static synchronized void schreiben(Stufe stufe, String text, Throwable t) {
        String ausnahme = "";
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            ausnahme = sw.toString();
            if (ausnahme.length() > 8000) {
                ausnahme = ausnahme.substring(0, 8000) + " …";
            }
        }
        String zeile = String.join("\t",
                LocalDateTime.now().withNano(0).toString(),
                stufe.name(),
                flach(BENUTZER.get()),
                flach(ANFRAGE.get()),
                flach(text),
                flach(ausnahme)) + "\n";
        try {
            Path datei = Files.createDirectories(ordner()).resolve(addon + ".log");
            if (Files.exists(datei) && Files.size(datei) > MAX_GROESSE) {
                Files.move(datei, datei.resolveSibling(addon + ".log.1"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(datei, zeile, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Protokoll " + addon + ": " + zeile);
        }
    }

    /** Eine Zeile je Eintrag: Tabulatoren und Zeilenumbrüche werden maskiert. */
    private static String flach(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\r", "").replace("\n", "\\n");
    }
}

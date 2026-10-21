package at.freudenthaler.kieselstein.addon.basis;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Die Einstellungen eines Addons in dieser Installation.
 *
 * Was es einzustellen gibt, beschreibt das Addon selbst
 * ({@code einstellungen.properties}, siehe {@link Einstellung}); die Werte
 * dieser Installation liegen außerhalb des Pakets in
 * {@code addons/config/<addon>.properties}. Ein neues Paket ändert sie nicht,
 * Test und Produktion haben jeweils ihre eigenen. Gepflegt werden sie in der
 * Verwaltung der Zentrale, als Formular.
 *
 * Im Addon: {@code Einstellungen.fuer("auftraege").zahl("zeilen_je_seite")} –
 * ohne Angabe gilt die Vorgabe aus der Beschreibung.
 */
public final class Einstellungen {

    /** Was jedes Addon einstellen kann, ohne es selbst zu beschreiben. */
    public static final String ALLGEMEIN =
            "max_gleichzeitige_anfragen.titel=Höchstens gleichzeitige Anfragen\n"
            + "max_gleichzeitige_anfragen.hilfe=Werden es mehr, weist das Addon weitere Anfragen kurz ab. So kann ein Addon den Addon-Server nicht allein belegen.\n"
            + "max_gleichzeitige_anfragen.art=zahl\n"
            + "max_gleichzeitige_anfragen.min=1\n"
            + "max_gleichzeitige_anfragen.max=500\n"
            + "max_gleichzeitige_anfragen.vorgabe=25\n";

    private final String addon;
    private final Properties werte;
    private final Map<String, Einstellung> beschreibung;

    private Einstellungen(String addon, Properties werte, Map<String, Einstellung> beschreibung) {
        this.addon = addon;
        this.werte = werte;
        this.beschreibung = beschreibung;
    }

    /** Der Ordner config/ des Addon-Servers. */
    public static Path ordner() {
        return Path.of(System.getProperty("kieselstein.addons.dir", "."), "config");
    }

    public static Path datei(String addon) {
        if (!addon.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("Ungültiger Addon-Name: " + addon);
        }
        return ordner().resolve(addon + ".properties");
    }

    /** Die Einstellungen eines Addons – aus seiner eigenen Sicht (Beschreibung aus dem eigenen Paket). */
    public static Einstellungen fuer(String addon) {
        return new Einstellungen(addon, werteLesen(addon), beschreibungAusPaket());
    }

    /** Die eigene Beschreibung des Addons, aus dem Paket, plus die allgemeinen Einstellungen. */
    private static Map<String, Einstellung> beschreibungAusPaket() {
        Map<String, Einstellung> m = new LinkedHashMap<>();
        try {
            for (Einstellung e : Einstellung.lesen(new StringReader(ALLGEMEIN))) {
                m.put(e.getSchluessel(), e);
            }
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            try (InputStream in = cl == null ? null : cl.getResourceAsStream("einstellungen.properties")) {
                if (in != null) {
                    for (Einstellung e : Einstellung.lesen(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        m.put(e.getSchluessel(), e);
                    }
                }
            }
        } catch (IOException e) {
            Protokoll.warnung("Beschreibung der Einstellungen nicht lesbar: " + e.getMessage());
        }
        return m;
    }

    public static Properties werteLesen(String addon) {
        Properties p = new Properties();
        Path d = datei(addon);
        if (Files.exists(d)) {
            try (Reader r = Files.newBufferedReader(d, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                throw new IllegalStateException("Einstellungen von " + addon + " nicht lesbar", e);
            }
        }
        return p;
    }

    private String roh(String schluessel) {
        String w = werte.getProperty(schluessel);
        if (w != null && !w.isBlank()) {
            return w.trim();
        }
        Einstellung e = beschreibung.get(schluessel);
        return e == null ? null : e.getVorgabe();
    }

    /** Text – ohne eigenen Wert die Vorgabe aus der Beschreibung. */
    public String text(String schluessel) {
        String w = roh(schluessel);
        return w == null ? "" : w;
    }

    public String text(String schluessel, String vorgabe) {
        String w = werte.getProperty(schluessel);
        return w == null || w.isBlank() ? vorgabe : w.trim();
    }

    public int zahl(String schluessel) {
        return zahl(schluessel, 0);
    }

    public int zahl(String schluessel, int vorgabe) {
        try {
            String w = roh(schluessel);
            return w == null || w.isBlank() ? vorgabe : Integer.parseInt(w);
        } catch (NumberFormatException e) {
            return vorgabe;
        }
    }

    public boolean schalter(String schluessel) {
        return schalter(schluessel, false);
    }

    public boolean schalter(String schluessel, boolean vorgabe) {
        String w = roh(schluessel);
        return w == null || w.isBlank() ? vorgabe : w.equalsIgnoreCase("ja") || w.equalsIgnoreCase("true") || w.equals("1");
    }

    public String getAddon() {
        return addon;
    }

    /**
     * Schreibt die Werte eines Addons. Leere Werte und Werte gleich der Vorgabe
     * werden nicht gespeichert – dann gilt die Vorgabe, auch wenn das Addon sie
     * später ändert. Werte ohne Beschreibung bleiben erhalten.
     */
    public static void speichern(String addon, List<Einstellung> beschreibung, Map<String, String> neu) throws IOException {
        Properties alt = werteLesen(addon);
        Map<String, String> behalten = new LinkedHashMap<>();
        List<String> bekannt = new ArrayList<>();
        for (Einstellung e : beschreibung) {
            bekannt.add(e.getSchluessel());
            String w = neu.get(e.getSchluessel());
            if (e.getArt() == Einstellung.Art.GEHEIM && (w == null || w.isEmpty())) {
                w = alt.getProperty(e.getSchluessel()); // leer gelassen = unverändert
            }
            if (w != null && !w.isBlank() && !w.trim().equals(e.getVorgabe())) {
                behalten.put(e.getSchluessel(), w.trim());
            }
        }
        for (String k : alt.stringPropertyNames()) {
            if (!bekannt.contains(k)) {
                behalten.put(k, alt.getProperty(k));
            }
        }
        Path d = datei(addon);
        Files.createDirectories(d.getParent());
        Path tmp = Files.createTempFile(d.getParent(), addon, ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write("# Einstellungen von " + addon + " – gepflegt in der Verwaltung der Zentrale (" + LocalDateTime.now().withNano(0) + ")\n");
            w.write("# Nur Abweichungen von der Vorgabe stehen hier.\n");
            Properties p = new Properties();
            behalten.forEach(p::setProperty);
            java.io.StringWriter sw = new java.io.StringWriter();
            p.store(sw, null);
            for (String zeile : sw.toString().split("\n")) {
                if (!zeile.startsWith("#")) {
                    w.write(zeile + "\n");
                }
            }
        }
        Files.move(tmp, d, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}

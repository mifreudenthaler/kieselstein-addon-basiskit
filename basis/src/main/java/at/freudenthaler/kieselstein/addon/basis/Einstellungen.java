package at.freudenthaler.kieselstein.addon.basis;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * Die Einstellungen eines Addons in dieser Installation.
 *
 * Sie liegen in {@code addons/config/<addon>.properties} – außerhalb des
 * Pakets. Ein neues Paket (Freigabe, Update) ändert sie nicht, und Test und
 * Produktion haben jeweils ihre eigenen. Gepflegt werden sie in der
 * Verwaltung der Zentrale.
 *
 * Beispiel: {@code Einstellungen.fuer("auftraege").text("standardfilter", "offen")}.
 */
public final class Einstellungen {

    private final String addon;
    private final Properties werte;

    private Einstellungen(String addon, Properties werte) {
        this.addon = addon;
        this.werte = werte;
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

    public static Einstellungen fuer(String addon) {
        Properties p = new Properties();
        Path d = datei(addon);
        if (Files.exists(d)) {
            try (Reader r = Files.newBufferedReader(d, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                throw new IllegalStateException("Einstellungen von " + addon + " nicht lesbar", e);
            }
        }
        return new Einstellungen(addon, p);
    }

    public String text(String schluessel, String vorgabe) {
        return werte.getProperty(schluessel, vorgabe);
    }

    public int zahl(String schluessel, int vorgabe) {
        try {
            return Integer.parseInt(werte.getProperty(schluessel, "").trim());
        } catch (NumberFormatException e) {
            return vorgabe;
        }
    }

    public boolean schalter(String schluessel, boolean vorgabe) {
        String w = werte.getProperty(schluessel);
        return w == null ? vorgabe : w.trim().equalsIgnoreCase("true") || w.trim().equals("1") || w.trim().equalsIgnoreCase("ja");
    }

    public String getAddon() {
        return addon;
    }

    /** Die Datei als Text – so wird sie in der Verwaltung bearbeitet. */
    public static String rohLesen(String addon) throws IOException {
        Path d = datei(addon);
        return Files.exists(d) ? Files.readString(d, StandardCharsets.UTF_8) : "";
    }

    /** Schreibt die Datei neu, nach Prüfung, dass sie sich als Properties lesen lässt. */
    public static void rohSchreiben(String addon, String inhalt) throws IOException {
        new Properties().load(new java.io.StringReader(inhalt)); // wirft bei kaputtem Inhalt
        Path d = datei(addon);
        Files.createDirectories(d.getParent());
        Path tmp = Files.createTempFile(d.getParent(), addon, ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            w.write(inhalt);
        }
        Files.move(tmp, d, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}

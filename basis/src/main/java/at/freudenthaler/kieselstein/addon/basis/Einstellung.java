package at.freudenthaler.kieselstein.addon.basis;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.io.Serializable;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Eine Einstellung, wie ein Addon sie beschreibt – damit die Zentrale ein
 * verständliches Formular zeigen kann statt einer Liste von Variablen.
 *
 * Jedes Addon legt dazu {@code src/main/resources/einstellungen.properties}
 * an, je Einstellung ein paar Zeilen mit demselben Schlüssel vorn:
 * <pre>
 *   zeilen_je_seite.titel=Zeilen je Seite
 *   zeilen_je_seite.hilfe=Wie viele Aufträge eine Seite der Liste zeigt.
 *   zeilen_je_seite.art=auswahl
 *   zeilen_je_seite.werte=25,50,100
 *   zeilen_je_seite.vorgabe=50
 * </pre>
 * Arten: {@code text}, {@code zahl} (mit {@code min}/{@code max}),
 * {@code schalter} (ja/nein), {@code auswahl} (mit {@code werte}),
 * {@code geheim} (Kennwort, wird nie angezeigt).
 * Die Reihenfolge in der Datei ist die Reihenfolge im Formular.
 */
public final class Einstellung implements Serializable {

    private static final long serialVersionUID = 1L;

    public enum Art { TEXT, ZAHL, SCHALTER, AUSWAHL, GEHEIM }

    private final String schluessel;
    private final String titel;
    private final String hilfe;
    private final Art art;
    private final String vorgabe;
    private final List<String> werte;
    private final Long min;
    private final Long max;

    Einstellung(String schluessel, Map<String, String> angaben) {
        this.schluessel = schluessel;
        this.titel = angaben.getOrDefault("titel", schluessel);
        this.hilfe = angaben.getOrDefault("hilfe", "");
        Art a;
        try {
            a = Art.valueOf(angaben.getOrDefault("art", "text").trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            a = Art.TEXT;
        }
        this.art = a;
        this.vorgabe = angaben.getOrDefault("vorgabe", art == Art.SCHALTER ? "nein" : "");
        this.werte = angaben.containsKey("werte")
                ? Arrays.stream(angaben.get("werte").split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList())
                : List.of();
        this.min = zahlOderNull(angaben.get("min"));
        this.max = zahlOderNull(angaben.get("max"));
    }

    private static Long zahlOderNull(String s) {
        try {
            return s == null ? null : Long.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Liest eine Beschreibung; die Reihenfolge der ersten Nennung bleibt erhalten. */
    public static List<Einstellung> lesen(Reader r) throws IOException {
        Map<String, Map<String, String>> gruppen = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();
        BufferedReader br = new BufferedReader(r);
        String zeile;
        while ((zeile = br.readLine()) != null) {
            text.append(zeile).append('\n');
            String z = zeile.trim();
            int gleich = z.indexOf('=');
            int punkt = z.lastIndexOf('.', gleich < 0 ? z.length() : gleich);
            if (z.isEmpty() || z.startsWith("#") || gleich < 0 || punkt < 0) {
                continue;
            }
            gruppen.computeIfAbsent(z.substring(0, punkt).trim(), k -> new LinkedHashMap<>());
        }
        // Werte über Properties, damit Umlaute (\\u…) und Fortsetzungszeilen stimmen
        Properties p = new Properties();
        p.load(new StringReader(text.toString()));
        for (String name : p.stringPropertyNames()) {
            int punkt = name.lastIndexOf('.');
            if (punkt > 0 && gruppen.containsKey(name.substring(0, punkt))) {
                gruppen.get(name.substring(0, punkt)).put(name.substring(punkt + 1), p.getProperty(name));
            }
        }
        List<Einstellung> liste = new ArrayList<>();
        gruppen.forEach((k, v) -> liste.add(new Einstellung(k, v)));
        return liste;
    }

    /** Ist dieser Wert erlaubt? Gibt eine verständliche Begründung zurück, sonst null. */
    public String pruefen(String wert) {
        String w = wert == null ? "" : wert.trim();
        switch (art) {
            case ZAHL:
                if (w.isEmpty()) {
                    return null;
                }
                try {
                    long z = Long.parseLong(w);
                    if (min != null && z < min) {
                        return titel + ": mindestens " + min;
                    }
                    if (max != null && z > max) {
                        return titel + ": höchstens " + max;
                    }
                } catch (NumberFormatException e) {
                    return titel + ": bitte eine ganze Zahl";
                }
                return null;
            case AUSWAHL:
                return w.isEmpty() || werte.contains(w) ? null : titel + ": nur " + String.join(", ", werte);
            case SCHALTER:
                return w.isEmpty() || w.equals("ja") || w.equals("nein") ? null : titel + ": ja oder nein";
            default:
                return null;
        }
    }

    public String getSchluessel() { return schluessel; }
    public String getTitel() { return titel; }
    public String getHilfe() { return hilfe; }
    public Art getArt() { return art; }
    public String getArtName() { return art.name().toLowerCase(); }
    public String getVorgabe() { return vorgabe; }
    public List<String> getWerte() { return werte; }
    public Long getMin() { return min; }
    public Long getMax() { return max; }

    /** „mindestens 1 · höchstens 500" – leer, wenn es keine Grenzen gibt. */
    public String getGrenzenText() {
        StringBuilder sb = new StringBuilder();
        if (min != null) {
            sb.append(" · mindestens ").append(min);
        }
        if (max != null) {
            sb.append(" · höchstens ").append(max);
        }
        return sb.toString();
    }

    /** Die Vorgabe so, wie ein Mensch sie liest. */
    public String getVorgabeText() {
        if (art == Art.GEHEIM) {
            return "keine";
        }
        return vorgabe.isEmpty() ? "leer" : vorgabe;
    }
}

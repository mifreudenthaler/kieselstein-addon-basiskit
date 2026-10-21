package at.freudenthaler.kieselstein.addon.zentrale;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.faces.view.ViewScoped;
import javax.inject.Named;

import at.freudenthaler.kieselstein.addon.basis.Protokoll;

/**
 * Die Protokolle aller Addons an einer Stelle – dazu die Fehler und Warnungen
 * des Addon-Servers selbst (WildFly), die sonst nur in logs/server.log stehen.
 */
@Named
@ViewScoped
public class Protokolle implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final int ENDE_BYTES = 400_000;
    private static final Pattern SERVER_ZEILE =
            Pattern.compile("^(\\d{4}-\\d{2}-\\d{2}) (\\d{2}:\\d{2}:\\d{2}),\\d+ (ERROR|WARN)\\s+\\[([^\\]]+)\\] \\(([^)]*)\\) (.*)$");

    private String addon = "";
    private String stufe = "";
    private String suche = "";

    public String getAddon() { return addon; }
    public void setAddon(String a) { this.addon = a == null ? "" : a; }
    public String getStufe() { return stufe; }
    public void setStufe(String s) { this.stufe = s == null ? "" : s; }
    public String getSuche() { return suche; }
    public void setSuche(String s) { this.suche = s == null ? "" : s; }

    /** Auswahl für den Filter: die Addons, deren Protokoll es gibt, und der Server. */
    public List<String> getQuellen() {
        List<String> q = new ArrayList<>();
        try (Stream<Path> s = Files.list(Protokoll.ordner())) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".log"))
                    .map(n -> n.substring(0, n.length() - 4)).sorted().forEach(q::add);
        } catch (IOException e) {
            // noch keine Protokolle
        }
        q.add("server");
        return q;
    }

    public List<Eintrag> getEintraege() {
        List<Eintrag> alle = new ArrayList<>();
        for (String quelle : getQuellen()) {
            if (!addon.isEmpty() && !addon.equals(quelle)) {
                continue;
            }
            if ("server".equals(quelle)) {
                serverLesen(alle);
            } else {
                addonLesen(quelle, alle);
            }
        }
        String s = suche.toLowerCase();
        return alle.stream()
                .filter(e -> stufe.isEmpty() || ("FEHLER".equals(stufe) ? "FEHLER".equals(e.stufe) : !"INFO".equals(e.stufe)))
                .filter(e -> s.isEmpty() || (e.text + " " + e.benutzer + " " + e.anfrage + " " + e.ausnahme).toLowerCase().contains(s))
                .sorted((a, b) -> b.zeit.compareTo(a.zeit))
                .limit(300)
                .collect(Collectors.toList());
    }

    private static void addonLesen(String quelle, List<Eintrag> ziel) {
        for (String zeile : ende(Protokoll.ordner().resolve(quelle + ".log"))) {
            String[] t = zeile.split("\t", -1);
            if (t.length >= 6) {
                ziel.add(new Eintrag(t[0].replace('T', ' '), t[1], quelle, auf(t[2]), auf(t[3]), auf(t[4]), auf(t[5])));
            }
        }
    }

    private static void serverLesen(List<Eintrag> ziel) {
        Path datei = AddonOrdner.addons().resolve("logs").resolve("server.log");
        Eintrag letzter = null;
        for (String zeile : ende(datei)) {
            Matcher m = SERVER_ZEILE.matcher(zeile);
            if (m.matches()) {
                letzter = new Eintrag(m.group(1) + " " + m.group(2), "ERROR".equals(m.group(3)) ? "FEHLER" : "WARNUNG",
                        "server", "", m.group(4), m.group(6), "");
                ziel.add(letzter);
            } else if (letzter != null && !zeile.matches("^\\d{4}-\\d{2}-\\d{2} .*") && letzter.ausnahme.length() < 6000) {
                letzter.ausnahme += zeile + "\n";
            } else {
                letzter = null;
            }
        }
    }

    /** Die letzten Zeilen einer Datei, ohne sie ganz zu lesen. */
    private static List<String> ende(Path datei) {
        if (!Files.exists(datei)) {
            return List.of();
        }
        try (RandomAccessFile f = new RandomAccessFile(datei.toFile(), "r")) {
            long laenge = f.length();
            long ab = Math.max(0, laenge - ENDE_BYTES);
            byte[] b = new byte[(int) (laenge - ab)];
            f.seek(ab);
            f.readFully(b);
            List<String> zeilen = new ArrayList<>(List.of(new String(b, StandardCharsets.UTF_8).split("\n")));
            if (ab > 0 && !zeilen.isEmpty()) {
                zeilen.remove(0); // angeschnitten
            }
            return zeilen;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static String auf(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\");
    }

    public static final class Eintrag implements Serializable {
        private static final long serialVersionUID = 1L;
        final String zeit;
        final String stufe;
        final String quelle;
        final String benutzer;
        final String anfrage;
        final String text;
        String ausnahme;

        Eintrag(String zeit, String stufe, String quelle, String benutzer, String anfrage, String text, String ausnahme) {
            this.zeit = zeit;
            this.stufe = stufe;
            this.quelle = quelle;
            this.benutzer = benutzer;
            this.anfrage = anfrage;
            this.text = text;
            this.ausnahme = ausnahme == null ? "" : ausnahme;
        }

        /** Eindeutig genug für die aufklappbaren Zeilen: Zeit, Quelle und Inhalt. */
        public String getSchluessel() {
            return Integer.toHexString((zeit + "|" + quelle + "|" + text + "|" + anfrage).hashCode());
        }
        public String getZeit() { return zeit; }
        public String getStufe() { return stufe; }
        public String getQuelle() { return quelle; }
        public String getBenutzer() { return benutzer; }
        public String getAnfrage() { return anfrage; }
        public String getText() { return text; }
        public String getAusnahme() { return ausnahme; }
        public boolean isMitAusnahme() { return !ausnahme.isBlank(); }
        public String getStufeKlasse() {
            return "FEHLER".equals(stufe) ? "ks-status ks-stufe-fehler" : "WARNUNG".equals(stufe) ? "ks-status ks-stufe-warnung" : "ks-status ks-stufe-info";
        }
    }
}

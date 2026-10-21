package at.freudenthaler.kieselstein.addon.zentrale;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import at.freudenthaler.kieselstein.addon.basis.Messung;

/**
 * Die Messwerte eines Addons über einen Zeitraum – aus addons/logs/messung/<addon>.tsv.
 */
final class Auswertung implements Serializable {

    private static final long serialVersionUID = 1L;

    final String addon;
    long anfragen;
    long fehler;
    long abgewiesen;
    long dauerMs;
    long kieselsteinMs;
    long kieselsteinAufrufe;
    long cpuMs;
    long speicherBytes;
    long maxDauerMs;
    /** Anfragen je Abschnitt, für den Verlauf (älteste zuerst). */
    final long[] verlauf;
    final List<String[]> langsam = new ArrayList<>();

    private Auswertung(String addon, int abschnitte) {
        this.addon = addon;
        this.verlauf = new long[abschnitte];
    }

    /** Auswerten über die letzten {@code minuten} Minuten, Verlauf in {@code abschnitte} Teilen. */
    static Auswertung von(String addon, int minuten, int abschnitte) {
        Auswertung a = new Auswertung(addon, abschnitte);
        Messung.Gelesen g = Messung.lesen(Messung.ordner().resolve(addon + ".tsv"));
        long jetzt = System.currentTimeMillis() / 60000;
        long ab = jetzt - minuten + 1;
        for (Map.Entry<Long, long[]> e : g.minuten.entrySet()) {
            long m = e.getKey();
            if (m < ab) {
                continue;
            }
            long[] w = e.getValue();
            a.anfragen += w[1];
            a.fehler += w[2];
            a.abgewiesen += w[3];
            a.dauerMs += w[4];
            a.kieselsteinMs += w[5];
            a.kieselsteinAufrufe += w[6];
            a.cpuMs += w[7];
            a.speicherBytes += w[8];
            a.maxDauerMs = Math.max(a.maxDauerMs, w[9]);
            int i = (int) ((m - ab) * abschnitte / minuten);
            if (i >= 0 && i < abschnitte) {
                a.verlauf[i] += w[1];
            }
        }
        long grenze = System.currentTimeMillis() - minuten * 60000L;
        for (String[] l : g.langsam) {
            try {
                if (Long.parseLong(l[0]) >= grenze) {
                    a.langsam.add(l);
                }
            } catch (NumberFormatException ex) {
                // überspringen
            }
        }
        return a;
    }

    static Path datei(String addon) {
        return Messung.ordner().resolve(addon + ".tsv");
    }

    long schnitt(long summe) {
        return anfragen == 0 ? 0 : Math.round((double) summe / anfragen);
    }

    long eigeneMs() {
        return Math.max(0, dauerMs - kieselsteinMs);
    }

    /** Ein kleiner Verlauf als SVG-Linie, 120 × 28. */
    String linie() {
        long max = 1;
        for (long v : verlauf) {
            max = Math.max(max, v);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < verlauf.length; i++) {
            double x = verlauf.length == 1 ? 0 : i * 120.0 / (verlauf.length - 1);
            double y = 26 - verlauf[i] * 24.0 / max;
            sb.append(i == 0 ? "" : " ").append(String.format(Locale.ROOT, "%.1f,%.1f", x, y));
        }
        return sb.toString();
    }
}

package at.freudenthaler.kieselstein.addon.basis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Was ein Addon verbraucht – gezählt je Minute, für die letzten 24 Stunden.
 *
 * Gemessen wird jede Anfrage an das Addon (der Messfilter der Basisschicht):
 * Dauer, davon Wartezeit auf Kieselstein, Rechenzeit (CPU) und der Speicher,
 * den der Arbeitsfaden dabei angelegt hat. Damit lässt sich sagen, ob
 * Kieselstein langsam ist oder das Addon, und welches Addon den Speicher und
 * die Rechenzeit des gemeinsamen Addon-Servers verbraucht.
 *
 * Jede Minute schreibt die Basisschicht den Stand nach
 * {@code addons/logs/messung/<addon>.tsv}; die Zentrale liest diese Dateien.
 * Nach einem Neustart wird dort weitergezählt.
 */
public final class Messung {

    /** Spalten einer Minute, in dieser Reihenfolge auch in der Datei. */
    public static final String[] SPALTEN = { "minute", "anfragen", "fehler", "abgewiesen",
            "dauerMs", "kieselsteinMs", "kieselsteinAufrufe", "cpuMs", "speicherBytes", "maxDauerMs" };

    private static final int MINUTEN = 24 * 60;
    private static final ConcurrentSkipListMap<Long, AtomicLong[]> EIMER = new ConcurrentSkipListMap<>();
    private static final ThreadLocal<long[]> KIESELSTEIN = new ThreadLocal<>();
    private static final List<String[]> LANGSAM = new ArrayList<>();
    static final AtomicLong AKTIV = new AtomicLong();

    private Messung() {
    }

    // --- Wartezeit auf Kieselstein, je Anfrage --------------------------------

    static void anfrageBeginnt() {
        KIESELSTEIN.set(new long[2]);
    }

    /** Aus Kieselstein.rufe: so lange hat dieser Aufruf auf Kieselstein gewartet. */
    static void kieselstein(long nanos) {
        long[] k = KIESELSTEIN.get();
        if (k != null) {
            k[0] += nanos;
            k[1]++;
        }
    }

    static long[] anfrageEndet() {
        long[] k = KIESELSTEIN.get();
        KIESELSTEIN.remove();
        return k == null ? new long[2] : k;
    }

    // --- Zählen -------------------------------------------------------------

    static void zaehlen(long dauerMs, long kieselsteinMs, long kieselsteinAufrufe, long cpuMs, long speicher,
            boolean fehler, String pfad, String benutzer) {
        AtomicLong[] e = eimer(System.currentTimeMillis() / 60000);
        e[1].incrementAndGet();
        if (fehler) {
            e[2].incrementAndGet();
        }
        e[4].addAndGet(dauerMs);
        e[5].addAndGet(kieselsteinMs);
        e[6].addAndGet(kieselsteinAufrufe);
        e[7].addAndGet(cpuMs);
        e[8].addAndGet(speicher);
        e[9].accumulateAndGet(dauerMs, Math::max);
        langsamMerken(dauerMs, kieselsteinMs, speicher, pfad, benutzer);
    }

    static void abgewiesen() {
        eimer(System.currentTimeMillis() / 60000)[3].incrementAndGet();
    }

    private static AtomicLong[] eimer(long minute) {
        return EIMER.computeIfAbsent(minute, m -> {
            AtomicLong[] a = new AtomicLong[SPALTEN.length];
            for (int i = 0; i < a.length; i++) {
                a[i] = new AtomicLong();
            }
            a[0].set(m);
            return a;
        });
    }

    /** Die 20 langsamsten Anfragen der letzten 24 Stunden. */
    private static synchronized void langsamMerken(long dauer, long ks, long speicher, String pfad, String benutzer) {
        if (LANGSAM.size() >= 20 && dauer <= Long.parseLong(LANGSAM.get(LANGSAM.size() - 1)[1])) {
            return;
        }
        LANGSAM.add(new String[] { String.valueOf(System.currentTimeMillis()), String.valueOf(dauer),
                String.valueOf(ks), String.valueOf(speicher), pfad == null ? "" : pfad, benutzer == null ? "" : benutzer });
        LANGSAM.sort((a, b) -> Long.compare(Long.parseLong(b[1]), Long.parseLong(a[1])));
        long grenze = System.currentTimeMillis() - MINUTEN * 60000L;
        LANGSAM.removeIf(x -> Long.parseLong(x[0]) < grenze);
        while (LANGSAM.size() > 20) {
            LANGSAM.remove(LANGSAM.size() - 1);
        }
    }

    // --- Datei --------------------------------------------------------------

    public static Path ordner() {
        return Path.of(System.getProperty("kieselstein.addons.dir", "."), "logs", "messung");
    }

    /** Schreibt den Stand des Addons (Minuten und langsamste Anfragen). */
    static synchronized void sichern(String addon) {
        long grenze = System.currentTimeMillis() / 60000 - MINUTEN;
        EIMER.headMap(grenze).clear();
        StringBuilder sb = new StringBuilder("# ").append(String.join("\t", SPALTEN)).append('\n');
        for (AtomicLong[] e : EIMER.values()) {
            for (int i = 0; i < e.length; i++) {
                sb.append(i == 0 ? "" : "\t").append(e[i].get());
            }
            sb.append('\n');
        }
        for (String[] l : LANGSAM) {
            sb.append("L\t").append(String.join("\t", l)).append('\n');
        }
        try {
            Path o = Files.createDirectories(ordner());
            Path tmp = o.resolve("." + addon + ".tsv.neu");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, o.resolve(addon + ".tsv"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Protokoll.warnung("Messung nicht gesichert: " + e.getMessage());
        }
    }

    /** Liest einen früheren Stand, damit nach einem Neustart weitergezählt wird. */
    static synchronized void laden(String addon) {
        Gelesen g = lesen(ordner().resolve(addon + ".tsv"));
        for (long[] m : g.minuten.values()) {
            AtomicLong[] e = eimer(m[0]);
            for (int i = 1; i < m.length; i++) {
                e[i].addAndGet(m[i]);
            }
        }
        LANGSAM.addAll(g.langsam);
    }

    /** Eine Messdatei, gelesen – auch für die Zentrale. */
    public static final class Gelesen {
        public final Map<Long, long[]> minuten = new TreeMap<>();
        public final List<String[]> langsam = new ArrayList<>();
    }

    public static Gelesen lesen(Path datei) {
        Gelesen g = new Gelesen();
        if (!Files.exists(datei)) {
            return g;
        }
        try {
            for (String zeile : Files.readAllLines(datei, StandardCharsets.UTF_8)) {
                if (zeile.isBlank() || zeile.startsWith("#")) {
                    continue;
                }
                String[] t = zeile.split("\t", -1);
                if ("L".equals(t[0])) {
                    g.langsam.add(java.util.Arrays.copyOfRange(t, 1, t.length));
                } else if (t.length == SPALTEN.length) {
                    long[] m = new long[t.length];
                    for (int i = 0; i < t.length; i++) {
                        m[i] = Long.parseLong(t[i]);
                    }
                    g.minuten.put(m[0], m);
                }
            }
        } catch (IOException | NumberFormatException e) {
            // unvollständige Datei – mit dem Gelesenen weiter
        }
        return g;
    }
}

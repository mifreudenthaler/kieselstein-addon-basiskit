package at.freudenthaler.kieselstein.addon.zentrale;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Der Ordner addons/ dieser Installation – was dort liegt, was läuft, was früher lief.
 *
 * WildFly steuert die Pakete über Markierungsdateien neben dem WAR:
 * {@code .dodeploy} lädt (neu), das Löschen von {@code .deployed} nimmt ein
 * Paket heraus, {@code .skipdeploy} verhindert, dass es von selbst wieder
 * startet. Genau das tut diese Klasse, und sie schreibt jede Handlung in den
 * Verlauf ({@code config/verlauf.log}).
 */
final class AddonOrdner {

    static final String ZENTRALE = "zentrale";
    private static final DateTimeFormatter STEMPEL = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private AddonOrdner() {
    }

    static Path addons() {
        return Path.of(System.getProperty("kieselstein.addons.dir", "."));
    }

    static Path deployments() {
        return Path.of(System.getProperty("kieselstein.addons.deployments", addons().resolve("deployments").toString()));
    }

    static Path archiv() throws IOException {
        return Files.createDirectories(addons().resolve("archiv"));
    }

    static Path war(String name) {
        pruefeName(name);
        return deployments().resolve(name + ".war");
    }

    static void pruefeName(String name) {
        if (name == null || !name.matches("[a-z0-9][a-z0-9-]*")) {
            throw new IllegalArgumentException("Ungültiger Addon-Name: " + name);
        }
    }

    static List<Startseite.Addon> alle() {
        try (Stream<Path> dateien = Files.list(deployments())) {
            return dateien.filter(p -> p.toString().endsWith(".war"))
                    .map(Startseite.Addon::aus)
                    .sorted((a, b) -> a.getTitel().compareToIgnoreCase(b.getTitel()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Neu laden – WildFly startet das Paket neu, die anderen laufen weiter. */
    static void neuStarten(String name, String wer) throws IOException {
        Path war = war(name);
        Files.deleteIfExists(marke(war, ".skipdeploy"));
        Files.deleteIfExists(marke(war, ".undeployed"));
        Files.deleteIfExists(marke(war, ".failed"));
        Files.deleteIfExists(marke(war, ".deployed"));
        Files.writeString(marke(war, ".dodeploy"), "");
        verlauf(wer, "neu gestartet", name, version(war));
    }

    /** Herausnehmen – das Paket bleibt liegen, startet aber nicht mehr von selbst. */
    static void abschalten(String name, String wer) throws IOException {
        if (ZENTRALE.equals(name)) {
            throw new IllegalArgumentException("Die Zentrale lässt sich nicht abschalten – sonst kommt niemand mehr hinein.");
        }
        Path war = war(name);
        Files.writeString(marke(war, ".skipdeploy"), "");
        Files.deleteIfExists(marke(war, ".deployed"));
        Files.deleteIfExists(marke(war, ".failed"));
        verlauf(wer, "abgeschaltet", name, version(war));
    }

    static void einschalten(String name, String wer) throws IOException {
        neuStarten(name, wer);
    }

    static Path marke(Path war, String endung) {
        return war.resolveSibling(war.getFileName() + endung);
    }

    /**
     * Wartet, bis WildFly das Paket gestartet hat oder daran gescheitert ist.
     *
     * Wer ein Paket (neu) einsetzt, entfernt vorher die alte Markierung
     * {@code .deployed}; eine neue erscheint erst, wenn WildFly fertig ist.
     * Auf Zeitstempel ist kein Verlass: WildFly setzt die Markierung auf die
     * Zeit des Pakets, auf die Millisekunde gekürzt.
     */
    static boolean wartenBisGestartet(String name, long sekunden) throws InterruptedException {
        Path war = war(name);
        long ende = System.currentTimeMillis() + sekunden * 1000;
        while (System.currentTimeMillis() < ende) {
            if (Files.exists(marke(war, ".failed"))) {
                return false;
            }
            if (Files.exists(marke(war, ".deployed")) && !Files.exists(marke(war, ".dodeploy"))
                    && !Files.exists(marke(war, ".isdeploying")) && !Files.exists(marke(war, ".pending"))) {
                return true;
            }
            Thread.sleep(500);
        }
        return false;
    }

    /** Die Version aus addon.properties im Paket. */
    static String version(Path war) {
        return angaben(war).getProperty("version", "?");
    }

    static Properties angaben(Path war) {
        Properties p = new Properties();
        if (!Files.exists(war)) {
            return p;
        }
        try (ZipFile zip = new ZipFile(war.toFile())) {
            ZipEntry e = zip.getEntry("WEB-INF/classes/addon.properties");
            if (e != null) {
                try (Reader r = new java.io.InputStreamReader(zip.getInputStream(e), StandardCharsets.UTF_8)) {
                    p.load(r);
                }
            }
        } catch (IOException e) {
            // ohne Angaben
        }
        return p;
    }

    static String sha256(Path datei) throws IOException {
        try (InputStream in = Files.newInputStream(datei)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] puffer = new byte[65536];
            int n;
            while ((n = in.read(puffer)) > 0) {
                md.update(puffer, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Legt das laufende Paket ins Archiv, damit man darauf zurück kann. */
    static Path archivieren(String name) throws IOException {
        Path war = war(name);
        if (!Files.exists(war)) {
            return null;
        }
        Path ziel = archiv().resolve(name + "-" + version(war) + "-" + LocalDateTime.now().format(STEMPEL) + ".war");
        Files.copy(war, ziel, StandardCopyOption.REPLACE_EXISTING);
        return ziel;
    }

    /** Die archivierten Pakete eines Addons, neueste zuerst. */
    static List<Path> archivFuer(String name) {
        pruefeName(name);
        try (Stream<Path> s = Files.list(archiv())) {
            return s.filter(p -> p.getFileName().toString().startsWith(name + "-") && p.toString().endsWith(".war"))
                    .sorted((a, b) -> zeitstempel(b).compareTo(zeitstempel(a)))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Der Zeitstempel am Ende des Archivnamens (…-yyyyMMdd-HHmmss.war) – danach wird sortiert, nicht nach Version. */
    private static String zeitstempel(Path p) {
        String n = p.getFileName().toString();
        return n.length() > 19 ? n.substring(n.length() - 19) : n;
    }

    /**
     * Ein Paket an seinen Platz legen: erst daneben schreiben, dann in einem
     * Zug umbenennen – WildFly sieht nie ein halbes WAR.
     */
    static void einsetzen(String name, Path neu) throws IOException {
        Path war = war(name);
        Path tmp = deployments().resolve("." + name + ".war.neu");
        Files.copy(neu, tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.deleteIfExists(marke(war, ".skipdeploy"));
        Files.deleteIfExists(marke(war, ".failed"));
        Files.deleteIfExists(marke(war, ".undeployed"));
        Files.deleteIfExists(marke(war, ".deployed"));
        Files.move(tmp, war, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(marke(war, ".dodeploy"), "");
    }

    static synchronized void verlauf(String wer, String was, String addon, String version) {
        String zeile = String.join("\t", LocalDateTime.now().withNano(0).toString(),
                wer == null ? "-" : wer, was, addon, version == null ? "" : version) + "\n";
        try {
            Path d = Files.createDirectories(addons().resolve("config")).resolve("verlauf.log");
            Files.writeString(d, zeile, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // Der Verlauf darf eine Handlung nicht verhindern.
        }
    }

    /** Die letzten Einträge des Verlaufs, neueste zuerst. */
    static List<String[]> verlaufLesen(int wieviele) {
        Path d = addons().resolve("config").resolve("verlauf.log");
        if (!Files.exists(d)) {
            return List.of();
        }
        try {
            List<String> zeilen = Files.readAllLines(d, StandardCharsets.UTF_8);
            List<String[]> ergebnis = new ArrayList<>();
            for (int i = zeilen.size() - 1; i >= 0 && ergebnis.size() < wieviele; i--) {
                if (!zeilen.get(i).isBlank()) {
                    ergebnis.add(zeilen.get(i).split("\t", -1));
                }
            }
            return ergebnis;
        } catch (IOException e) {
            return List.of();
        }
    }
}

package at.freudenthaler.kieselstein.addon.zentrale;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import at.freudenthaler.kieselstein.addon.basis.Kieselstein;

/**
 * Ausrollen von Test nach Produktion („Freigabe").
 *
 * Freigeben heißt ausrollen: Das Paket wird in die Produktion gebracht,
 * dort eingesetzt und gestartet.
 *
 * Die Test-Zentrale schickt das Paket, das im Test läuft – Byte für Byte –
 * an die Produktions-Zentrale. Beide stehen beim Kunden. Ausgewiesen wird
 * mit einem gemeinsamen Schlüssel ({@code config/freigabe.schluessel}, bei der
 * Einrichtung einmal von Test nach Produktion kopiert): Jede Übergabe trägt
 * eine HMAC-Signatur über Addon, Version, Prüfsumme, Freigebenden und Zeit.
 * Die Produktion nimmt nur signierte, frische Übergaben an und sonst nichts.
 */
final class Freigabe {

    static final long GUELTIG_SEKUNDEN = 600;

    private Freigabe() {
    }

    /** Stufen der Kette, in dieser Reihenfolge. Entwicklung ist zusätzlich, Standard sind Test und Produktion. */
    static String stufe() {
        String s = Kieselstein.stufe().toLowerCase();
        return s.equals("entwicklung") || s.equals("produktion") ? s : "test";
    }

    static String stufenName(String s) {
        switch (s == null ? "" : s.toLowerCase()) {
            case "entwicklung": return "Entwicklung";
            case "produktion": return "Produktion";
            default: return "Test";
        }
    }

    /** Darf dieser Addon-Server an eine nächste Stufe ausrollen? (nicht die Produktion) */
    static boolean kannSenden() {
        return !"produktion".equals(stufe());
    }

    /** Nimmt dieser Addon-Server Pakete der vorigen Stufe an? (nicht die Entwicklung) */
    static boolean kannEmpfangen() {
        return !"entwicklung".equals(stufe());
    }

    static boolean istTest() {
        return kannSenden();
    }

    /** Die Adresse der Zentrale der nächsten Stufe, z. B. http://erp-addons:8280. */
    static String produktion() {
        String u = System.getProperty("kieselstein.addons.naechste",
                System.getProperty("kieselstein.addons.produktion", "")).trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    static boolean eingerichtet() {
        return Files.exists(schluesselDatei());
    }

    static Path schluesselDatei() {
        return AddonOrdner.addons().resolve("config").resolve("freigabe.schluessel");
    }

    static String signatur(String... teile) throws IOException {
        byte[] schluessel = Files.readString(schluesselDatei(), StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(schluessel, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(String.join("|", teile).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean signaturStimmt(String erwartet, String... teile) throws IOException {
        if (erwartet == null || !eingerichtet()) {
            return false;
        }
        return MessageDigest.isEqual(signatur(teile).getBytes(StandardCharsets.US_ASCII),
                erwartet.getBytes(StandardCharsets.US_ASCII));
    }

    static boolean frisch(String zeit) {
        try {
            long t = Long.parseLong(zeit);
            return Math.abs(System.currentTimeMillis() / 1000 - t) <= GUELTIG_SEKUNDEN;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** Ergebnis einer Übergabe, so wie die Produktion es meldet. */
    static final class Ergebnis {
        final boolean ok;
        final String text;

        Ergebnis(boolean ok, String text) {
            this.ok = ok;
            this.text = text;
        }
    }

    /** Das laufende Test-Paket an die Produktion übergeben. */
    static Ergebnis senden(String name, String wer) throws IOException, InterruptedException {
        if (!istTest()) {
            throw new IllegalStateException("Die Produktion ist die letzte Stufe – von hier wird nichts weiter ausgerollt.");
        }
        if (produktion().isEmpty() || !eingerichtet()) {
            throw new IllegalStateException("Die nächste Stufe ist nicht eingerichtet (NAECHSTE_STUFE_URL, freigabe.schluessel).");
        }
        Path war = AddonOrdner.war(name);
        if (Files.isDirectory(war)) {
            throw new IllegalStateException(name + " läuft im Live-Modus – erst im Editor beenden, dann ausrollen.");
        }
        if (!Files.exists(AddonOrdner.marke(war, ".deployed"))) {
            throw new IllegalStateException(name + " läuft hier nicht – ausgerollt wird nur, was hier läuft.");
        }
        String version = AddonOrdner.version(war);
        String sha = AddonOrdner.sha256(war);
        String zeit = String.valueOf(System.currentTimeMillis() / 1000);
        HttpRequest anfrage = HttpRequest.newBuilder(URI.create(produktion() + "/freigabe/paket"))
                .timeout(Duration.ofSeconds(180))
                .header("X-Addon", name)
                .header("X-Version", version)
                .header("X-Sha256", sha)
                .header("X-Von", wer)
                .header("X-Zeit", zeit)
                .header("X-Signatur", signatur(name, version, sha, wer, zeit))
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofFile(war))
                .build();
        HttpResponse<String> antwort = client().send(anfrage, HttpResponse.BodyHandlers.ofString());
        boolean ok = antwort.statusCode() == 200;
        AddonOrdner.verlauf(wer, ok ? "in die nächste Stufe ausgerollt" : "Ausrollen gescheitert: " + antwort.body().replace('\t', ' '), name, version);
        return new Ergebnis(ok, antwort.body());
    }

    /** Was in der Produktion läuft: Addon → {Version, Zustand, Prüfsumme}. Leer, wenn nicht erreichbar. */
    static Map<String, String[]> standProduktion() {
        Map<String, String[]> stand = new LinkedHashMap<>();
        if (produktion().isEmpty() || !eingerichtet()) {
            return stand;
        }
        try {
            String zeit = String.valueOf(System.currentTimeMillis() / 1000);
            HttpRequest anfrage = HttpRequest.newBuilder(URI.create(produktion() + "/freigabe/stand"))
                    .timeout(Duration.ofSeconds(5))
                    .header("X-Zeit", zeit)
                    .header("X-Signatur", signatur("stand", zeit))
                    .GET().build();
            HttpResponse<String> antwort = client().send(anfrage, HttpResponse.BodyHandlers.ofString());
            if (antwort.statusCode() == 200) {
                for (String zeile : antwort.body().split("\n")) {
                    String[] t = zeile.split("\t");
                    if (t.length >= 2 && "#stufe".equals(t[0])) {
                        stand.put("#stufe", new String[] { t[1] });
                        continue;
                    }
                    if (t.length >= 4) {
                        stand.put(t[0], new String[] { t[1], t[2], t[3] });
                    }
                }
            }
        } catch (IOException | InterruptedException e) {
            // Produktion nicht erreichbar – die Verwaltung zeigt das an
        }
        return stand;
    }
}

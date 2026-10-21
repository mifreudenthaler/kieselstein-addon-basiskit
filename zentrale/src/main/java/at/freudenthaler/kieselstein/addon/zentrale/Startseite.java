package at.freudenthaler.kieselstein.addon.zentrale;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.inject.Named;

import com.lp.server.system.service.MandantDto;
import com.lp.server.system.service.MandantFac;

import at.freudenthaler.kieselstein.addon.basis.Kieselstein;
import at.freudenthaler.kieselstein.addon.basis.KieselsteinVersion;
import at.freudenthaler.kieselstein.addon.basis.Sitzung;

/**
 * Die Startseite der Zentrale: wer angemeldet ist, welche Addons installiert
 * sind, und – als Probe der Rechte-Brücke – was der Benutzer darf.
 */
@Named
@RequestScoped
public class Startseite {

    /** Das Recht, mit dem man Addons verwalten und freigeben darf (Entscheidung 21.10.2026). */
    static final String RECHT_VERWALTEN = at.freudenthaler.kieselstein.addon.basis.Rahmen.RECHT_VERWALTEN;

    @Inject
    Sitzung sitzung;

    private MandantDto mandant;

    public String getStufe() {
        return Kieselstein.stufe();
    }

    public boolean isTest() {
        return !"produktion".equalsIgnoreCase(getStufe());
    }

    public String getKieselsteinServer() {
        return Kieselstein.server();
    }

    public String getKieselsteinVersion() {
        return KieselsteinVersion.lesen();
    }

    public String getMandantName() {
        if (mandant == null && sitzung.isAngemeldet()) {
            mandant = Kieselstein.rufe(() -> Kieselstein.fassade(MandantFac.class)
                    .mandantFindByPrimaryKey(sitzung.getMandant(), sitzung.getClient()));
        }
        return mandant == null || mandant.getPartnerDto() == null ? sitzung.getMandant()
                : mandant.getPartnerDto().formatFixName1Name2();
    }

    public boolean isDarfVerwalten() {
        return sitzung.hatRecht(RECHT_VERWALTEN);
    }

    /** Installiert ist, was im Deployments-Ordner liegt und von WildFly gestartet wurde. */
    public List<Addon> getAddons() {
        String ordner = System.getProperty("kieselstein.addons.deployments");
        if (ordner == null) {
            return List.of();
        }
        try (Stream<Path> dateien = Files.list(Path.of(ordner))) {
            return dateien.filter(p -> p.toString().endsWith(".war"))
                    .map(p -> Addon.aus(p))
                    .filter(a -> !"zentrale".equals(a.getName()))
                    .sorted((a, b) -> a.getTitel().compareToIgnoreCase(b.getTitel()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * Ein installiertes Addon, wie es im Deployments-Ordner liegt.
     *
     * Titel, Beschreibung und Version stehen im Addon selbst, in
     * WEB-INF/classes/addon.properties – so beschreibt sich jedes Addon, ohne
     * dass die Zentrale es kennen muss.
     */
    public static final class Addon {
        private final String name;
        private final String pfad;
        private final String zustand;
        private final java.util.Properties angaben;

        Addon(String name, String pfad, String zustand, java.util.Properties angaben) {
            this.name = name;
            this.pfad = pfad;
            this.zustand = zustand;
            this.angaben = angaben;
        }

        static Addon aus(Path war) {
            String datei = war.getFileName().toString();
            String name = datei.substring(0, datei.length() - 4);
            boolean live = Files.isDirectory(war);
            String zustand = Files.exists(war.resolveSibling(datei + ".failed")) ? "Fehler beim Start"
                    : live && Files.exists(war.resolveSibling(datei + ".deployed")) ? "läuft (Live-Modus)"
                    : Files.exists(war.resolveSibling(datei + ".deployed")) ? "läuft"
                    : Files.exists(war.resolveSibling(datei + ".failed")) ? "Fehler beim Start"
                    : Files.exists(war.resolveSibling(datei + ".undeployed")) ? "abgeschaltet"
                    : "startet";
            java.util.Properties angaben = AddonOrdner.angaben(war);
            if (angaben.isEmpty()) try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(war.toFile())) {
                java.util.zip.ZipEntry e = zip.getEntry("WEB-INF/classes/addon.properties");
                if (e != null) {
                    try (java.io.Reader r = new java.io.InputStreamReader(zip.getInputStream(e), java.nio.charset.StandardCharsets.UTF_8)) {
                        angaben.load(r);
                    }
                }
            } catch (IOException e) {
                // ohne Beschreibung anzeigen
            }
            return new Addon(name, "zentrale".equals(name) ? "/" : "/" + name + "/", zustand, angaben);
        }

        public String getName() { return name; }
        public String getTitel() { return angaben.getProperty("titel", name); }
        public String getBeschreibung() { return angaben.getProperty("beschreibung", ""); }
        public String getVersion() { return angaben.getProperty("version", "?"); }
        public String getPfad() { return pfad; }
        public String getZustand() { return zustand; }
        public boolean isLaeuft() { return zustand.startsWith("läuft"); }
        public boolean isLive() { return zustand.endsWith("(Live-Modus)"); }
    }
}

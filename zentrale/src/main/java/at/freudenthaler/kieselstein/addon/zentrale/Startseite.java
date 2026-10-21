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
    static final String RECHT_VERWALTEN = "LP_SYSTEM_CUD";

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
                    .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Ein installiertes Addon, wie es im Deployments-Ordner liegt. */
    public static final class Addon {
        private final String name;
        private final String pfad;
        private final String zustand;

        Addon(String name, String pfad, String zustand) {
            this.name = name;
            this.pfad = pfad;
            this.zustand = zustand;
        }

        static Addon aus(Path war) {
            String datei = war.getFileName().toString();
            String name = datei.substring(0, datei.length() - 4);
            String zustand = Files.exists(war.resolveSibling(datei + ".deployed")) ? "läuft"
                    : Files.exists(war.resolveSibling(datei + ".failed")) ? "Fehler beim Start"
                    : Files.exists(war.resolveSibling(datei + ".undeployed")) ? "abgeschaltet"
                    : "startet";
            return new Addon(name, "zentrale".equals(name) ? "/" : "/" + name + "/", zustand);
        }

        public String getName() { return name; }
        public String getPfad() { return pfad; }
        public String getZustand() { return zustand; }
        public boolean isLaeuft() { return "läuft".equals(zustand); }
    }
}

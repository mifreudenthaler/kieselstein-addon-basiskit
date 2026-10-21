package at.freudenthaler.kieselstein.addon.zentrale;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.faces.application.FacesMessage;
import javax.faces.context.FacesContext;
import javax.faces.view.ViewScoped;
import javax.inject.Inject;
import javax.inject.Named;

import at.freudenthaler.kieselstein.addon.basis.Einstellungen;
import at.freudenthaler.kieselstein.addon.basis.Kieselstein;
import at.freudenthaler.kieselstein.addon.basis.Sitzung;

/**
 * Die Verwaltung der Addons in der Zentrale.
 *
 * Nur für Kieselstein-Benutzer mit Administrationsrecht (LP_SYSTEM_CUD) –
 * geprüft bei jeder Handlung, nicht nur beim Anzeigen. Jede Handlung landet
 * im Verlauf.
 */
@Named
@ViewScoped
public class Verwaltung implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    @Inject
    Sitzung sitzung;

    private Boolean darf;
    private String gewaehlt;
    private String einstellungenText;
    private Map<String, String> produktion;

    public boolean isDarf() {
        if (darf == null) {
            darf = sitzung.hatRecht(Startseite.RECHT_VERWALTEN);
        }
        return darf;
    }

    private void pruefen() {
        if (!sitzung.hatRecht(Startseite.RECHT_VERWALTEN)) {
            throw new SecurityException("Dafür fehlt in Kieselstein das Recht " + Startseite.RECHT_VERWALTEN);
        }
    }

    public boolean isTest() {
        return Freigabe.istTest();
    }

    public String getStufe() {
        return Kieselstein.stufe();
    }

    public boolean isFreigabeMoeglich() {
        return isTest() && !Freigabe.produktion().isEmpty() && Freigabe.eingerichtet();
    }

    public String getProduktionsAdresse() {
        return Freigabe.produktion();
    }

    public List<Zeile> getAddons() {
        if (produktion == null && isFreigabeMoeglich()) {
            produktion = Freigabe.standProduktion();
        }
        return AddonOrdner.alle().stream().map(a -> new Zeile(a, produktion)).collect(Collectors.toList());
    }

    public boolean isProduktionErreichbar() {
        if (produktion == null && isFreigabeMoeglich()) {
            produktion = Freigabe.standProduktion();
        }
        return produktion != null && !produktion.isEmpty();
    }

    public List<String[]> getVerlauf() {
        return AddonOrdner.verlaufLesen(30);
    }

    // --- Handlungen -------------------------------------------------------

    public void neuStarten(String name) {
        tun(() -> AddonOrdner.neuStarten(name, sitzung.getBenutzer()), name + " wird neu gestartet");
    }

    public void abschalten(String name) {
        tun(() -> AddonOrdner.abschalten(name, sitzung.getBenutzer()), name + " ist abgeschaltet");
    }

    public void einschalten(String name) {
        tun(() -> AddonOrdner.einschalten(name, sitzung.getBenutzer()), name + " wird gestartet");
    }

    public void freigeben(String name) {
        pruefenUndMelden(() -> {
            Freigabe.Ergebnis e = Freigabe.senden(name, sitzung.getBenutzer());
            melden(e.ok ? FacesMessage.SEVERITY_INFO : FacesMessage.SEVERITY_ERROR, e.text.replaceFirst("^ok\t", "").replace('\t', ' '));
            produktion = null;
        });
    }

    public void zurueck(String name, String archivDatei) {
        pruefenUndMelden(() -> {
            Path p = AddonOrdner.archivFuer(name).stream()
                    .filter(x -> x.getFileName().toString().equals(archivDatei)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Archivpaket nicht gefunden"));
            AddonOrdner.archivieren(name);
            AddonOrdner.einsetzen(name, p);
            AddonOrdner.verlauf(sitzung.getBenutzer(), "zurück auf " + archivDatei, name, AddonOrdner.version(p));
            boolean ok = AddonOrdner.wartenBisGestartet(name, 120);
            melden(ok ? FacesMessage.SEVERITY_INFO : FacesMessage.SEVERITY_ERROR,
                    ok ? name + " läuft wieder mit " + AddonOrdner.version(p) : name + " startet mit " + archivDatei + " nicht");
        });
    }

    // --- Einstellungen ----------------------------------------------------

    public void einstellungenOeffnen(String name) {
        pruefenUndMelden(() -> {
            gewaehlt = name;
            einstellungenText = Einstellungen.rohLesen(name);
        });
    }

    public void einstellungenSichern() {
        pruefenUndMelden(() -> {
            Einstellungen.rohSchreiben(gewaehlt, einstellungenText == null ? "" : einstellungenText);
            AddonOrdner.verlauf(sitzung.getBenutzer(), "Einstellungen geändert", gewaehlt, "");
            melden(FacesMessage.SEVERITY_INFO, "Einstellungen von " + gewaehlt + " gesichert – wirksam beim nächsten Zugriff des Addons");
        });
    }

    public String getGewaehlt() { return gewaehlt; }
    public String getEinstellungenText() { return einstellungenText; }
    public void setEinstellungenText(String t) { this.einstellungenText = t; }

    // --- Hilfen -----------------------------------------------------------

    @FunctionalInterface
    interface Handlung {
        void tun() throws Exception;
    }

    private void tun(Handlung h, String erfolg) {
        pruefenUndMelden(() -> {
            h.tun();
            melden(FacesMessage.SEVERITY_INFO, erfolg);
        });
    }

    private void pruefenUndMelden(Handlung h) {
        try {
            pruefen();
            h.tun();
        } catch (Exception e) {
            melden(FacesMessage.SEVERITY_ERROR, e.getMessage());
        }
    }

    private static void melden(FacesMessage.Severity s, String text) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(s, text, null));
    }

    /** Eine Zeile der Verwaltungstabelle. */
    public static final class Zeile implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Startseite.Addon addon;
        private final String produktion;
        private final String geaendert;
        private final List<String> archiv;
        private final String vorigeVersion;

        Zeile(Startseite.Addon a, Map<String, String> prod) {
            this.addon = a;
            this.produktion = prod == null ? null : prod.getOrDefault(a.getName(), "nicht installiert");
            String g;
            try {
                g = LocalDateTime.ofInstant(Files.getLastModifiedTime(AddonOrdner.war(a.getName())).toInstant(),
                        ZoneId.systemDefault()).format(ZEIT);
            } catch (IOException e) {
                g = "";
            }
            this.geaendert = g;
            // Nur Stände, die sich vom laufenden Paket unterscheiden – eine Kopie
            // der laufenden Version wäre kein Zurück.
            String laufend;
            try {
                laufend = AddonOrdner.sha256(AddonOrdner.war(a.getName()));
            } catch (IOException e) {
                laufend = "";
            }
            final String jetzt = laufend;
            this.archiv = AddonOrdner.archivFuer(a.getName()).stream()
                    .filter(p -> {
                        try {
                            return !AddonOrdner.sha256(p).equals(jetzt);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(p -> p.getFileName().toString())
                    .limit(10).collect(Collectors.toList());
            this.vorigeVersion = archiv.isEmpty() ? null : AddonOrdner.version(AddonOrdner.archivFuer(a.getName()).stream()
                    .filter(p -> p.getFileName().toString().equals(archiv.get(0))).findFirst().orElse(null));
        }

        public String getName() { return addon.getName(); }
        public String getTitel() { return addon.getTitel(); }
        public String getVersion() { return addon.getVersion(); }
        public String getZustand() { return addon.getZustand(); }
        public boolean isLaeuft() { return addon.isLaeuft(); }
        public boolean isAbgeschaltet() { return "abgeschaltet".equals(addon.getZustand()); }
        public boolean isZentrale() { return AddonOrdner.ZENTRALE.equals(addon.getName()); }
        public String getProduktion() { return produktion; }
        public String getGeaendert() { return geaendert; }
        public List<String> getArchiv() { return archiv; }
        public String getVorigeVersion() { return vorigeVersion; }
    }
}

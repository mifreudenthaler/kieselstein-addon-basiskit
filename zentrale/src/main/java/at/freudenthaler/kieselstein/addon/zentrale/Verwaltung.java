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

import at.freudenthaler.kieselstein.addon.basis.Einstellung;
import at.freudenthaler.kieselstein.addon.basis.Einstellungen;
import at.freudenthaler.kieselstein.addon.basis.Kieselstein;
import at.freudenthaler.kieselstein.addon.basis.Sitzung;

/**
 * Die Verwaltung der Addons in der Zentrale.
 *
 * Nur für Kieselstein-Benutzer mit Administrationsrecht (LP_SYSTEM_CUD) –
 * geprüft bei jeder Handlung, nicht nur beim Anzeigen. Jede Handlung landet
 * im Verlauf.
 *
 * „In Produktion ausrollen" (nur in der Test-Stufe): Das Paket, das hier im
 * Test läuft, geht Byte für Byte an die Produktions-Zentrale, wird dort
 * eingesetzt und gestartet; startet es nicht, läuft dort von selbst wieder die
 * vorige Version.
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
    private List<Einstellung> formular;
    private Map<String, String> eingabe;
    private java.util.Set<String> geheimGesetzt;
    private Map<String, String[]> produktion;
    private List<Zeile> zeilen;
    private Zeile ausrollen;
    private boolean geprueft;

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

    /** Nimmt diese Stufe Pakete an (Test, Produktion) – dann gibt es auch ein Zurück. */
    public boolean isEmpfaengt() {
        return Freigabe.kannEmpfangen();
    }

    public String getStufe() {
        return Freigabe.stufenName(Freigabe.stufe());
    }

    public boolean isAusrollenEingerichtet() {
        return Freigabe.kannSenden() && !Freigabe.produktion().isEmpty() && Freigabe.eingerichtet();
    }

    /** Name der nächsten Stufe, wie sie sich selbst meldet – sonst aus der Kette geschlossen. */
    public String getNaechsteStufe() {
        String[] s = produktion().get("#stufe");
        if (s != null && s.length > 0) {
            return Freigabe.stufenName(s[0]);
        }
        return "entwicklung".equals(Freigabe.stufe()) ? "Test" : "Produktion";
    }

    public String getProduktionsAdresse() {
        return Freigabe.produktion();
    }

    private Map<String, String[]> produktion() {
        if (produktion == null) {
            produktion = isAusrollenEingerichtet() ? Freigabe.standProduktion() : Map.of();
        }
        return produktion;
    }

    public boolean isProduktionErreichbar() {
        return !produktion().isEmpty();
    }

    public List<Zeile> getAddons() {
        if (zeilen == null) {
            zeilen = AddonOrdner.alle().stream().map(a -> new Zeile(a, produktion(), isAusrollenEingerichtet(), getNaechsteStufe()))
                    .sorted((p, q) -> Boolean.compare(p.isZentrale(), q.isZentrale()))
                    .collect(Collectors.toList());
        }
        return zeilen;
    }

    private void neuLaden() {
        zeilen = null;
        produktion = null;
    }

    public List<String[]> getVerlauf() {
        return AddonOrdner.verlaufLesen(50);
    }

    public String getKieselstein() {
        return Kieselstein.server();
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

    /** Öffnet den Dialog „In Produktion ausrollen" für ein Addon. */
    public void ausrollenVorbereiten(String name) {
        geprueft = false;
        ausrollen = getAddons().stream().filter(z -> z.getName().equals(name)).findFirst().orElse(null);
    }

    public void ausrollenBestaetigt() {
        if (ausrollen == null) {
            return;
        }
        if (!geprueft) {
            melden(FacesMessage.SEVERITY_ERROR, "Nicht ausgerollt: Bitte bestätigen, dass " + ausrollen.getTitel() + " hier geprüft ist.");
            return;
        }
        String name = ausrollen.getName();
        pruefenUndMelden(() -> {
            Freigabe.Ergebnis e = Freigabe.senden(name, sitzung.getBenutzer());
            melden(e.ok ? FacesMessage.SEVERITY_INFO : FacesMessage.SEVERITY_ERROR,
                    e.text.replaceFirst("^ok\t", "").replace('\t', ' '));
        });
        ausrollen = null;
        neuLaden();
    }

    public Zeile getAusrollen() { return ausrollen; }
    public boolean isGeprueft() { return geprueft; }
    public void setGeprueft(boolean g) { this.geprueft = g; }

    public void zurueck(String name, String archivDatei) {
        pruefenUndMelden(() -> {
            Path p = AddonOrdner.archivFuer(name).stream()
                    .filter(x -> x.getFileName().toString().equals(archivDatei)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Archivpaket nicht gefunden"));
            AddonOrdner.archivieren(name);
            AddonOrdner.einsetzen(name, p);
            AddonOrdner.verlauf(sitzung.getBenutzer(), "zurück auf " + AddonOrdner.version(p), name, AddonOrdner.version(p));
            boolean ok = AddonOrdner.wartenBisGestartet(name, 120);
            melden(ok ? FacesMessage.SEVERITY_INFO : FacesMessage.SEVERITY_ERROR,
                    ok ? name + " läuft wieder mit " + AddonOrdner.version(p) : name + " startet mit " + archivDatei + " nicht");
        });
        neuLaden();
    }

    // --- Einstellungen ----------------------------------------------------

    /** Öffnet das Formular mit den Einstellungen eines Addons. */
    public void einstellungenOeffnen(String name) {
        pruefenUndMelden(() -> {
            gewaehlt = name;
            formular = AddonOrdner.einstellungen(name);
            java.util.Properties werte = Einstellungen.werteLesen(name);
            eingabe = new java.util.HashMap<>();
            geheimGesetzt = new java.util.HashSet<>();
            for (Einstellung e : formular) {
                String w = werte.getProperty(e.getSchluessel(), "");
                if (e.getArt() == Einstellung.Art.GEHEIM) {
                    if (!w.isEmpty()) {
                        geheimGesetzt.add(e.getSchluessel());
                    }
                    eingabe.put(e.getSchluessel(), "");
                } else {
                    eingabe.put(e.getSchluessel(), w);
                }
            }
        });
    }

    /** Ein Feld zurück auf die Vorgabe des Addons. */
    public void vorgabe(String schluessel) {
        if (eingabe != null) {
            eingabe.put(schluessel, "");
        }
    }

    public void einstellungenSichern() {
        pruefenUndMelden(() -> {
            List<String> fehler = new java.util.ArrayList<>();
            for (Einstellung e : formular) {
                String grund = e.pruefen(eingabe.get(e.getSchluessel()));
                if (grund != null) {
                    fehler.add(grund);
                }
            }
            if (!fehler.isEmpty()) {
                FacesContext.getCurrentInstance().validationFailed();
                melden(FacesMessage.SEVERITY_ERROR, "Nicht gesichert – " + String.join("; ", fehler));
                return;
            }
            java.util.Properties vorher = Einstellungen.werteLesen(gewaehlt);
            Einstellungen.speichern(gewaehlt, formular, eingabe);
            java.util.Properties nachher = Einstellungen.werteLesen(gewaehlt);
            int geaendert = 0;
            for (Einstellung e : formular) {
                String a = wirksam(e, vorher.getProperty(e.getSchluessel()));
                String b = wirksam(e, nachher.getProperty(e.getSchluessel()));
                if (!a.equals(b)) {
                    geaendert++;
                    AddonOrdner.verlauf(sitzung.getBenutzer(), e.getArt() == Einstellung.Art.GEHEIM
                            ? "Einstellung „" + e.getTitel() + "“ geändert"
                            : "Einstellung „" + e.getTitel() + "“: " + a + " → " + b, gewaehlt, "");
                }
            }
            melden(FacesMessage.SEVERITY_INFO, geaendert == 0 ? "Nichts geändert."
                    : geaendert + (geaendert == 1 ? " Einstellung" : " Einstellungen") + " von " + gewaehlt
                            + " gesichert – das Addon liest sie beim nächsten Zugriff.");
        });
    }

    /** Der Wert, der tatsächlich gilt – ohne eigenen Wert die Vorgabe. */
    private static String wirksam(Einstellung e, String wert) {
        return wert == null || wert.isBlank() ? e.getVorgabeText() : (e.getArt() == Einstellung.Art.GEHEIM ? "gesetzt" : wert.trim());
    }

    public String getGewaehlt() { return gewaehlt; }
    public String getGewaehltTitel() {
        return getAddons().stream().filter(z -> z.getName().equals(gewaehlt)).map(Zeile::getTitel).findFirst().orElse(gewaehlt);
    }
    public List<Einstellung> getFormular() { return formular; }
    public Map<String, String> getEingabe() { return eingabe; }
    public boolean geheimGesetzt(String schluessel) { return geheimGesetzt != null && geheimGesetzt.contains(schluessel); }

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
        neuLaden();
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

    /** Ein Addon in der Verwaltung: Test, Produktion, Verbrauch der letzten Stunde, Archiv. */
    public static final class Zeile implements Serializable {
        private static final long serialVersionUID = 1L;
        private final Startseite.Addon addon;
        private final String[] prod;
        private final boolean ausrollbar;
        private final String sha;
        private final String geaendert;
        private final List<String> archiv;
        private final String vorigeVersion;
        private final Auswertung stunde;
        private final String naechste;

        Zeile(Startseite.Addon a, Map<String, String[]> produktion, boolean ausrollbar, String naechste) {
            this.addon = a;
            this.naechste = naechste;
            this.prod = produktion.get(a.getName());
            this.ausrollbar = ausrollbar;
            Path war = AddonOrdner.war(a.getName());
            String s;
            String g;
            try {
                s = AddonOrdner.sha256(war);
                g = LocalDateTime.ofInstant(Files.getLastModifiedTime(war).toInstant(), ZoneId.systemDefault()).format(ZEIT);
            } catch (IOException e) {
                s = "";
                g = "";
            }
            this.sha = s;
            this.geaendert = g;
            // Nur Stände, die sich vom laufenden Paket unterscheiden – eine Kopie der laufenden Version wäre kein Zurück.
            final String laufend = s;
            List<Path> staende = AddonOrdner.archivFuer(a.getName()).stream().filter(p -> {
                try {
                    return !AddonOrdner.sha256(p).equals(laufend);
                } catch (IOException e) {
                    return false;
                }
            }).limit(10).collect(Collectors.toList());
            this.archiv = staende.stream().map(p -> p.getFileName().toString()).collect(Collectors.toList());
            this.vorigeVersion = staende.isEmpty() ? null : AddonOrdner.version(staende.get(0));
            this.stunde = Auswertung.von(a.getName(), 60, 30);
        }

        public String getName() { return addon.getName(); }
        public String getTitel() { return addon.getTitel(); }
        public String getBeschreibung() { return addon.getBeschreibung(); }
        public String getVersion() { return addon.getVersion(); }
        public String getZustand() { return addon.getZustand(); }
        public boolean isLaeuft() { return addon.isLaeuft(); }
        public boolean isAbgeschaltet() { return "abgeschaltet".equals(addon.getZustand()); }
        public boolean isZentrale() { return AddonOrdner.ZENTRALE.equals(addon.getName()); }
        public String getGeaendert() { return geaendert; }
        public String getPruefsumme() { return sha.length() > 12 ? sha.substring(0, 12) : sha; }
        public List<String> getArchiv() { return archiv; }
        public String getVorigeVersion() { return vorigeVersion; }
        public String getVorigeDatei() { return archiv.isEmpty() ? null : archiv.get(0); }

        public String getZustandKlasse() {
            return isLaeuft() ? "ks-status ks-status-erledigt" : isAbgeschaltet() ? "ks-status ks-status-angelegt" : "ks-status ks-stufe-fehler";
        }

        // Vergleich mit der Produktion (nur in der Test-Stufe)
        public boolean isMitProduktion() { return ausrollbar; }
        public String getNaechste() { return naechste; }
        public String getProduktionVersion() { return prod == null ? null : prod[0]; }
        public boolean isInProduktion() { return prod != null; }
        public boolean isGleichWieProduktion() { return prod != null && prod.length > 2 && prod[2].equals(sha); }
        public boolean isAusrollbar() { return ausrollbar && isLaeuft() && !isZentrale() && !isGleichWieProduktion(); }
        public String getVergleich() {
            if (!ausrollbar) {
                return null;
            }
            if (prod == null) {
                return "noch nicht installiert";
            }
            return isGleichWieProduktion() ? "gleich wie hier (" + prod[0] + ")" : "läuft mit " + prod[0] + " – hier ist neuer";
        }

        // Verbrauch der letzten Stunde
        public long getAnfragen() { return stunde.anfragen; }
        public long getFehler() { return stunde.fehler; }
        public long getSchnittMs() { return stunde.schnitt(stunde.dauerMs); }
        public String getLinie() { return stunde.linie(); }
    }
}

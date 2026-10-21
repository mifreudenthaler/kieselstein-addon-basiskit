package at.freudenthaler.kieselstein.addon.zentrale;

import java.io.IOException;
import java.util.Locale;

import javax.enterprise.context.RequestScoped;
import javax.faces.application.FacesMessage;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;
import javax.inject.Inject;
import javax.inject.Named;

import at.freudenthaler.kieselstein.addon.basis.Kieselstein;
import at.freudenthaler.kieselstein.addon.basis.Sitzung;

/**
 * Anmelden und Abmelden an der Zentrale – für alle Addons zugleich.
 *
 * Kommt man aus einem Addon hierher, steht dessen Adresse in „zurueck"; nach
 * der Anmeldung geht es dorthin zurück. Erlaubt sind nur Adressen auf diesem
 * Server, damit niemand die Anmeldung als Sprungbrett nach draußen benutzt.
 */
@Named
@RequestScoped
public class Anmeldung {

    @Inject
    Sitzung sitzung;

    private String benutzer;
    private String kennwort;
    private String mandant;
    private String zurueck;

    public String anmelden() throws IOException {
        try {
            sitzung.anmelden(benutzer, kennwort, mandant, new Locale("de", "AT"));
        } catch (Sitzung.AnmeldungAbgelehnt | Kieselstein.KieselsteinNichtErreichbar e) {
            FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_ERROR, e.getMessage(), null));
            return null;
        }
        if (istHier(zurueck)) {
            FacesContext.getCurrentInstance().getExternalContext().redirect(zurueck);
            return null;
        }
        return "/index.xhtml?faces-redirect=true";
    }

    /** Aufgerufen von /abmelden.xhtml – aus jedem Addon über den Rahmen erreichbar. */
    public void abmelden() throws IOException {
        sitzung.abmelden();
        ExternalContext ec = FacesContext.getCurrentInstance().getExternalContext();
        ec.invalidateSession();
        ec.redirect("/");
    }

    static boolean istHier(String adresse) {
        return adresse != null && adresse.startsWith("/") && !adresse.startsWith("//") && !adresse.contains("\\");
    }

    public String getBenutzer() { return benutzer; }
    public void setBenutzer(String benutzer) { this.benutzer = benutzer; }
    public String getKennwort() { return kennwort; }
    public void setKennwort(String kennwort) { this.kennwort = kennwort; }
    public String getMandant() { return mandant; }
    public void setMandant(String mandant) { this.mandant = mandant; }
    public String getZurueck() { return zurueck; }
    public void setZurueck(String zurueck) { this.zurueck = zurueck; }
}

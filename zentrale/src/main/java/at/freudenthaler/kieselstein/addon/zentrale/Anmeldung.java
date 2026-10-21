package at.freudenthaler.kieselstein.addon.zentrale;

import java.util.Locale;

import javax.enterprise.context.RequestScoped;
import javax.faces.application.FacesMessage;
import javax.faces.context.FacesContext;
import javax.inject.Inject;
import javax.inject.Named;

import at.freudenthaler.kieselstein.addon.basis.Kieselstein;
import at.freudenthaler.kieselstein.addon.basis.Sitzung;

/** Das Anmeldeformular der Zentrale – Kieselstein-Benutzer, Kennwort, Mandant. */
@Named
@RequestScoped
public class Anmeldung {

    @Inject
    Sitzung sitzung;

    private String benutzer;
    private String kennwort;
    private String mandant;

    public String anmelden() {
        try {
            sitzung.anmelden(benutzer, kennwort, mandant, new Locale("de", "AT"));
            return "/index.xhtml?faces-redirect=true";
        } catch (Sitzung.AnmeldungAbgelehnt e) {
            melden(e.getMessage());
        } catch (Kieselstein.KieselsteinNichtErreichbar e) {
            melden(e.getMessage());
        }
        return null;
    }

    public String abmelden() {
        sitzung.abmelden();
        FacesContext.getCurrentInstance().getExternalContext().invalidateSession();
        return "/index.xhtml?faces-redirect=true";
    }

    private static void melden(String text) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(FacesMessage.SEVERITY_ERROR, text, null));
    }

    public String getBenutzer() { return benutzer; }
    public void setBenutzer(String benutzer) { this.benutzer = benutzer; }
    public String getKennwort() { return kennwort; }
    public void setKennwort(String kennwort) { this.kennwort = kennwort; }
    public String getMandant() { return mandant; }
    public void setMandant(String mandant) { this.mandant = mandant; }
}

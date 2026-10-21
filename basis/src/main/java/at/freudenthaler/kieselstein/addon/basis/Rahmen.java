package at.freudenthaler.kieselstein.addon.basis;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import javax.faces.context.FacesContext;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;

/** Was der gemeinsame Rahmen über die Seite wissen muss. */
@Named
@RequestScoped
public class Rahmen {

    /** Wer Addons verwalten darf (Kieselstein-Administrationsrecht). */
    public static final String RECHT_VERWALTEN = "LP_SYSTEM_CUD";

    @Inject
    Sitzung sitzung;

    private Boolean verwalten;

    /** Verweis „Verwaltung" im Kopf – nur für Benutzer mit Administrationsrecht. */
    public boolean isVerwalten() {
        if (verwalten == null) {
            verwalten = sitzung.isAngemeldet() && sitzung.hatRecht(RECHT_VERWALTEN);
        }
        return verwalten;
    }

    /** Live-Modus: Die Seite lädt sich neu, sobald die Erweiterung eine Änderung übernommen hat. */
    public boolean isLive() {
        return LiveStand.aktiv();
    }

    public String getLiveStand() {
        try {
            return java.nio.file.Files.readString(LiveStand.datei(Protokoll.addon())).trim();
        } catch (java.io.IOException e) {
            return "";
        }
    }

    public boolean isTest() {
        return !"produktion".equalsIgnoreCase(Kieselstein.stufe());
    }

    /** „Test" oder „Entwicklung" – steht im Kopf jeder Seite, nur nicht in der Produktion. */
    public String getStufeText() {
        return "entwicklung".equalsIgnoreCase(Kieselstein.stufe()) ? "Entwicklung" : "Test";
    }

    /** Die Anmeldeseite der Zentrale zeigt sich auch ohne Anmeldung. */
    public boolean isAnmeldeseite() {
        HttpServletRequest r = anfrage();
        return r != null && r.getContextPath().isEmpty()
                && ("/".equals(r.getServletPath()) || r.getServletPath().startsWith("/index"));
    }

    /** Die aktuelle Adresse – damit die Zentrale nach der Anmeldung hierher zurückführt. */
    public String getHier() {
        HttpServletRequest r = anfrage();
        String uri = r == null ? "/" : r.getRequestURI();
        return URLEncoder.encode(uri, StandardCharsets.UTF_8);
    }

    private static HttpServletRequest anfrage() {
        FacesContext fc = FacesContext.getCurrentInstance();
        return fc == null ? null : (HttpServletRequest) fc.getExternalContext().getRequest();
    }
}

package at.freudenthaler.kieselstein.addon.basis;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import javax.enterprise.context.RequestScoped;
import javax.faces.context.FacesContext;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;

/** Was der gemeinsame Rahmen über die Seite wissen muss. */
@Named
@RequestScoped
public class Rahmen {

    public boolean isTest() {
        return !"produktion".equalsIgnoreCase(Kieselstein.stufe());
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

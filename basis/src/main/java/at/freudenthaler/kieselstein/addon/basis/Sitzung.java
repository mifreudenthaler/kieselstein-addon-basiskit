package at.freudenthaler.kieselstein.addon.basis;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Locale;
import java.util.Map;

import javax.annotation.PreDestroy;
import javax.enterprise.context.SessionScoped;
import javax.faces.context.ExternalContext;
import javax.faces.context.FacesContext;
import javax.inject.Named;
import javax.servlet.http.Cookie;

import com.lp.server.benutzer.service.LogonFac;
import com.lp.server.system.service.TheClientDto;
import com.lp.server.system.service.TheClientFac;
import com.lp.server.system.service.TheJudgeFac;
import com.lp.util.Helper;

/**
 * Die Kieselstein-Anmeldung eines Menschen im Addon-Server.
 *
 * Angemeldet wird einmal, an der Zentrale, wie im Client: Benutzer als
 * {@code name|rechner|}, Kennwort als MD5 über Name und Kennwort, dazu Mandant
 * und Client-Version. Kieselstein gibt dafür einen Sitzungsschlüssel aus. Die
 * Zentrale legt ihn in ein Cookie für den ganzen Addon-Server; jedes Addon
 * holt sich damit dieselbe Anmeldung bei Kieselstein ab
 * (TheClientFac.theClientFindByUserLoggedIn – derselbe Weg wie die REST-Schnittstelle).
 * Meldet man sich ab, ist der Schlüssel bei Kieselstein ungültig, für alle Addons zugleich.
 *
 * Rechte fragt die Sitzung am Kieselstein-Server ab (TheJudgeFac). Das ist die
 * Brücke, bis Kieselstein die Rechte hinter den Fassaden selbst prüft.
 */
@Named
@SessionScoped
public class Sitzung implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Das Cookie mit dem Kieselstein-Sitzungsschlüssel, gültig für alle Addons dieses Servers. */
    public static final String COOKIE = "KS_ADDON_SITZUNG";

    /** Unter diesem Rechnernamen erscheint der Addon-Server in Kieselstein. */
    static final String RECHNER = "addon-server";

    static String clientVersion() {
        return System.getProperty("kieselstein.client.version", KieselsteinVersion.lesen());
    }

    private TheClientDto client;

    /** Angemeldet – hier oder an der Zentrale, deren Anmeldung übernommen wird. */
    public boolean isAngemeldet() {
        if (client == null) {
            uebernehmen();
        }
        return client != null;
    }

    public TheClientDto getClient() {
        if (!isAngemeldet()) {
            throw new IllegalStateException("Nicht angemeldet");
        }
        return client;
    }

    public String getBenutzer() {
        return isAngemeldet() ? client.getBenutzername().split("\\|")[0].trim() : null;
    }

    public String getMandant() {
        return isAngemeldet() ? client.getMandant() : null;
    }

    /**
     * Anmelden (nur die Zentrale tut das). Wirft eine Ausnahme, wenn Benutzer,
     * Kennwort oder Mandant nicht passen.
     */
    public void anmelden(String benutzer, String kennwort, String mandant, Locale sprache) {
        LogonFac logon = Kieselstein.fassade(LogonFac.class);
        String voll = benutzer + LogonFac.USERNAMEDELIMITER + RECHNER + LogonFac.USERNAMEDELIMITER;
        try {
            client = logon.logon(voll, Helper.getMD5Hash(benutzer + kennwort).toCharArray(), sprache,
                    mandant == null || mandant.isBlank() ? null : mandant.trim(),
                    clientVersion(), new Timestamp(System.currentTimeMillis()));
        } catch (Exception e) {
            throw new AnmeldungAbgelehnt(e);
        }
        cookieSetzen(client.getIDUser(), -1);
    }

    /** Hat der angemeldete Benutzer dieses Kieselstein-Recht? Geprüft am Server. */
    public boolean hatRecht(String recht) {
        return isAngemeldet() && Kieselstein.rufe(() -> Kieselstein.fassade(TheJudgeFac.class).hatRecht(recht, client));
    }

    /** Abmelden – bei Kieselstein, und damit in allen Addons. */
    public void abmelden() {
        if (client != null) {
            try {
                Kieselstein.fassade(LogonFac.class).logout(client);
            } catch (Exception e) {
                // Kieselstein räumt verwaiste Anmeldungen selbst auf.
            }
            client = null;
        }
        cookieSetzen("", 0);
    }

    /**
     * Die Sitzung dieses Addons endet (Zeitablauf). Bei Kieselstein bleibt die
     * Anmeldung bestehen – sie gehört der Zentrale und gilt für alle Addons.
     */
    @PreDestroy
    void vergessen() {
        client = null;
    }

    private void uebernehmen() {
        String schluessel = cookieLesen();
        if (schluessel == null || schluessel.isBlank()) {
            return;
        }
        try {
            client = Kieselstein.fassade(TheClientFac.class).theClientFindByUserLoggedIn(schluessel);
        } catch (Exception e) {
            client = null; // abgemeldet oder abgelaufen
        }
    }

    private static String cookieLesen() {
        FacesContext fc = FacesContext.getCurrentInstance();
        if (fc == null) {
            return null;
        }
        Object c = fc.getExternalContext().getRequestCookieMap().get(COOKIE);
        return c instanceof Cookie ? ((Cookie) c).getValue() : null;
    }

    private static void cookieSetzen(String wert, int maxAlter) {
        FacesContext fc = FacesContext.getCurrentInstance();
        if (fc == null) {
            return;
        }
        ExternalContext ec = fc.getExternalContext();
        ec.addResponseCookie(COOKIE, wert, Map.of("path", "/", "maxAge", maxAlter, "httpOnly", true));
    }

    /** Kieselstein hat die Anmeldung abgelehnt. */
    public static final class AnmeldungAbgelehnt extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AnmeldungAbgelehnt(Throwable grund) {
            super("Anmeldung abgelehnt – Benutzer, Kennwort oder Mandant stimmen nicht", grund);
        }
    }
}

package at.freudenthaler.kieselstein.addon.basis;

import java.io.Serializable;
import java.sql.Timestamp;
import java.util.Locale;

import javax.annotation.PreDestroy;
import javax.enterprise.context.SessionScoped;
import javax.inject.Named;

import com.lp.server.benutzer.service.LogonFac;
import com.lp.server.system.service.TheClientDto;
import com.lp.server.system.service.TheJudgeFac;
import com.lp.util.Helper;

/**
 * Die Kieselstein-Anmeldung eines Menschen im Addon-Server.
 *
 * Angemeldet wird wie im Client: Benutzer als {@code name|rechner|}, Kennwort
 * als MD5 über Name und Kennwort, dazu Mandant und Client-Version. Das
 * Ergebnis ist das Kieselstein-Benutzerobjekt, mit dem jeder weitere
 * Fassadenaufruf läuft. Eine eigene Benutzerverwaltung gibt es nicht.
 *
 * Rechte fragt die Sitzung am Kieselstein-Server ab (TheJudgeFac). Das ist die
 * Brücke, bis Kieselstein die Rechte hinter den Fassaden selbst prüft.
 */
@Named
@SessionScoped
public class Sitzung implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Unter diesem Rechnernamen erscheint der Addon-Server in Kieselstein. */
    static final String RECHNER = "addon-server";

    /** Die Client-Version, mit der sich der Addon-Server anmeldet. */
    static String clientVersion() {
        return System.getProperty("kieselstein.client.version", KieselsteinVersion.lesen());
    }

    private TheClientDto client;

    public boolean isAngemeldet() {
        return client != null;
    }

    public TheClientDto getClient() {
        if (client == null) {
            throw new IllegalStateException("Nicht angemeldet");
        }
        return client;
    }

    public String getBenutzer() {
        return client == null ? null : client.getBenutzername().split("\\|")[0];
    }

    public String getMandant() {
        return client == null ? null : client.getMandant();
    }

    /**
     * Anmelden. Wirft eine Ausnahme mit Kieselsteins eigener Begründung, wenn
     * Benutzer, Kennwort oder Mandant nicht passen.
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
    }

    /** Hat der angemeldete Benutzer dieses Kieselstein-Recht? Geprüft am Server. */
    public boolean hatRecht(String recht) {
        return isAngemeldet() && Kieselstein.rufe(() -> Kieselstein.fassade(TheJudgeFac.class).hatRecht(recht, client));
    }

    @PreDestroy
    public void abmelden() {
        if (client != null) {
            try {
                Kieselstein.fassade(LogonFac.class).logout(client);
            } catch (Exception e) {
                // Kieselstein räumt verwaiste Anmeldungen selbst auf; Abmelden ist Höflichkeit.
            }
            client = null;
        }
    }

    /** Kieselstein hat die Anmeldung abgelehnt. */
    public static final class AnmeldungAbgelehnt extends RuntimeException {
        private static final long serialVersionUID = 1L;

        AnmeldungAbgelehnt(Throwable grund) {
            super("Anmeldung abgelehnt – Benutzer, Kennwort oder Mandant stimmen nicht", grund);
        }
    }
}

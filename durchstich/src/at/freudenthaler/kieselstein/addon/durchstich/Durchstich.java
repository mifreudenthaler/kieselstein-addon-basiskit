package at.freudenthaler.kieselstein.addon.durchstich;

import java.sql.Timestamp;
import java.util.Locale;
import java.util.Properties;

import javax.naming.Context;
import javax.naming.InitialContext;

import com.lp.server.benutzer.service.LogonFac;
import com.lp.server.system.service.MandantFac;
import com.lp.server.system.service.TheClientDto;
import com.lp.server.system.service.TheJudgeFac;
import com.lp.util.Helper;

/*
 * Durchstich für das Basiskit (21.10.2026).
 *
 * Beweist den Weg, auf dem jedes Addon mit Kieselstein spricht: von außen
 * über remote+http, wie der Kieselstein-Client. Anmelden, eine Fassade lesen,
 * ein Recht am Server abfragen, abmelden. Geschrieben wird nichts.
 *
 * Aufruf: Durchstich <server> <benutzer> <kennwort> [mandant] [recht]
 */
public class Durchstich {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Durchstich <server> <benutzer> <kennwort> [mandant] [recht]");
            System.exit(2);
        }
        String server = args[0];
        String benutzer = args[1];
        String kennwort = args[2];
        String mandant = args.length > 3 ? args[3] : null;
        String recht = args.length > 4 ? args[4] : "LP_DARF_PREISE_SEHEN_VERKAUF";

        Properties p = new Properties();
        p.put(Context.INITIAL_CONTEXT_FACTORY, "org.wildfly.naming.client.WildFlyInitialContextFactory");
        p.put(Context.PROVIDER_URL, "remote+http://" + server);
        Context ctx = new InitialContext(p);

        LogonFac logon = fassade(ctx, LogonFac.class);
        TheJudgeFac judge = fassade(ctx, TheJudgeFac.class);
        MandantFac mandantFac = fassade(ctx, MandantFac.class);

        // Wie der Client: "name|rechner|", Kennwort als MD5 über Name + Kennwort.
        String voll = benutzer + LogonFac.USERNAMEDELIMITER + "addon-durchstich" + LogonFac.USERNAMEDELIMITER;
        long t0 = System.nanoTime();
        TheClientDto client = logon.logon(voll, Helper.getMD5Hash(benutzer + kennwort).toCharArray(),
                new Locale("de", "AT"), mandant, "2.0.0", new Timestamp(System.currentTimeMillis()));
        System.out.printf("Anmeldung     ok  (%d ms) Benutzer %s, Mandant %s%n",
                ms(t0), client.getBenutzername(), client.getMandant());

        try {
            t0 = System.nanoTime();
            var m = mandantFac.mandantFindByPrimaryKey(client.getMandant(), client);
            System.out.printf("Fassade lesen ok  (%d ms) Mandant %s: %s%n",
                    ms(t0), m.getCNr(), m.getPartnerDto() != null ? m.getPartnerDto().formatFixName1Name2() : "?");

            t0 = System.nanoTime();
            boolean darf = judge.hatRecht(recht, client);
            System.out.printf("Recht am Server ok (%d ms) %s: %s%n", ms(t0), recht, darf ? "ja" : "nein");
        } finally {
            logon.logout(client);
            System.out.println("Abmeldung     ok");
        }
    }

    /** Nachschlagen wie der Client: lpserver/ejb/<Schnittstelle>Bean!<voller Name>. */
    @SuppressWarnings("unchecked")
    static <T> T fassade(Context ctx, Class<T> schnittstelle) throws Exception {
        return (T) ctx.lookup("lpserver/ejb/" + schnittstelle.getSimpleName() + "Bean!" + schnittstelle.getName());
    }

    static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }
}

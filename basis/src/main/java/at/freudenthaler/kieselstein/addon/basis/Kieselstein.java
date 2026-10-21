package at.freudenthaler.kieselstein.addon.basis;

import java.util.Properties;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

/**
 * Der Weg zu Kieselstein – für alle Addons an genau einer Stelle.
 *
 * Gesprochen wird von außen über remote+http, so wie der Kieselstein-Client
 * es tut. Nachgeschlagen wird mit demselben Namen wie im Client:
 * {@code lpserver/ejb/<Schnittstelle>Bean!<voller Name>}. Ändert Kieselstein
 * daran etwas, wird es hier angepasst und nicht in jedem Addon.
 */
public final class Kieselstein {

    /** Wo Kieselstein läuft: Systemeigenschaft kieselstein.server, gesetzt vom Startskript. */
    public static String server() {
        return System.getProperty("kieselstein.server", "localhost:8080");
    }

    /** Test oder Produktion – steht in jeder Seite, damit niemand die beiden verwechselt. */
    public static String stufe() {
        return System.getProperty("kieselstein.addons.stufe", "test");
    }

    private Kieselstein() {
    }

    /** Eine Fassade der Kieselstein-Fachlogik, z. B. {@code fassade(KundeFac.class)}. */
    public static <T> T fassade(Class<T> schnittstelle) {
        try {
            Object o = kontext().lookup("lpserver/ejb/" + schnittstelle.getSimpleName() + "Bean!" + schnittstelle.getName());
            return schnittstelle.cast(o);
        } catch (NamingException e) {
            throw new KieselsteinNichtErreichbar(server(), schnittstelle.getSimpleName(), e);
        }
    }

    /**
     * Ein Aufruf in die Fachlogik. Die Fassaden werfen geprüfte Ausnahmen
     * (RemoteException, Kieselsteins eigene Fehler); hier werden sie zu einer
     * ungeprüften {@link KieselsteinFehler} mit Kieselsteins Begründung.
     * Die Wartezeit zählt in die Messung des Addons (davon Kieselstein).
     */
    public static <T> T rufe(Aufruf<T> aufruf) {
        long t0 = System.nanoTime();
        try {
            return aufruf.ausfuehren();
        } catch (RuntimeException e) {
            Protokoll.warnung("Kieselstein-Aufruf gescheitert: " + e);
            throw e;
        } catch (Exception e) {
            Protokoll.warnung("Kieselstein-Aufruf gescheitert: " + e);
            throw new KieselsteinFehler(e);
        } finally {
            Messung.kieselstein(System.nanoTime() - t0);
        }
    }

    /** Antwortet Kieselstein? Gibt die Antwortzeit in ms zurück, -1 wenn nicht. */
    public static long ping() {
        long t0 = System.nanoTime();
        try {
            fassade(com.lp.server.system.service.SystemFac.class).getServerPathSeperator();
            return (System.nanoTime() - t0) / 1_000_000;
        } catch (Exception e) {
            return -1;
        }
    }

    /** Ein Aufruf in die Fachlogik, der geprüfte Ausnahmen werfen darf. */
    @FunctionalInterface
    public interface Aufruf<T> {
        T ausfuehren() throws Exception;
    }

    /** Kieselstein hat einen Aufruf abgelehnt oder ist dabei gescheitert. */
    public static final class KieselsteinFehler extends RuntimeException {
        private static final long serialVersionUID = 1L;

        KieselsteinFehler(Throwable grund) {
            super("Kieselstein meldet: " + grund.getMessage(), grund);
        }
    }

    private static Context kontext() throws NamingException {
        Properties p = new Properties();
        p.put(Context.INITIAL_CONTEXT_FACTORY, "org.wildfly.naming.client.WildFlyInitialContextFactory");
        p.put(Context.PROVIDER_URL, "remote+http://" + server());
        return new InitialContext(p);
    }

    /** Kieselstein antwortet nicht oder kennt die Fassade nicht. */
    public static final class KieselsteinNichtErreichbar extends RuntimeException {
        private static final long serialVersionUID = 1L;

        KieselsteinNichtErreichbar(String server, String fassade, Throwable grund) {
            super("Kieselstein unter " + server + " ist nicht erreichbar (" + fassade + ")", grund);
        }
    }
}

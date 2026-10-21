package at.freudenthaler.kieselstein.addon.basis;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/**
 * Start und Ende eines Addons im Addon-Server.
 *
 * Merkt sich den Namen des Addons (aus dem Pfad: „/auftraege" → auftraege,
 * „/" → zentrale), lädt den früheren Messstand, sichert ihn jede Minute und
 * beim Beenden und vermerkt Start und Ende im Protokoll.
 *
 * Eingebunden über META-INF/web-fragment.xml der Basisschicht.
 */
public class Basiskit implements ServletContextListener {

    private static volatile int maxGleichzeitig = 25;
    private ScheduledExecutorService takt;
    private String addon;

    static int maxGleichzeitig() {
        return maxGleichzeitig;
    }

    @Override
    public void contextInitialized(ServletContextEvent e) {
        String pfad = e.getServletContext().getContextPath();
        addon = pfad == null || pfad.isEmpty() || "/".equals(pfad) ? "zentrale" : pfad.substring(1);
        Protokoll.addon(addon);
        Messung.laden(addon);
        einstellungenLesen();
        takt = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "basiskit-messung-" + addon);
            t.setDaemon(true);
            return t;
        });
        takt.scheduleAtFixedRate(() -> {
            Messung.sichern(addon);
            einstellungenLesen();
        }, 60, 60, TimeUnit.SECONDS);
        Protokoll.info("gestartet");
    }

    private void einstellungenLesen() {
        try {
            maxGleichzeitig = Math.max(1, Einstellungen.fuer(addon).zahl("max_gleichzeitige_anfragen", 25));
        } catch (RuntimeException ex) {
            maxGleichzeitig = 25;
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent e) {
        if (takt != null) {
            takt.shutdownNow();
        }
        Messung.sichern(addon);
        Protokoll.info("beendet");
    }
}

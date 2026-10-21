package at.freudenthaler.kieselstein.addon.basis;

import java.io.IOException;
import java.lang.management.ManagementFactory;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Misst jede Anfrage an ein Addon und hält ein Addon im Zaum.
 *
 * Gemessen werden Dauer, Wartezeit auf Kieselstein, Rechenzeit und der
 * Speicher, den der Arbeitsfaden angelegt hat (über die Thread-Messung der
 * JVM). Unbehandelte Fehler und langsame Anfragen landen im Protokoll.
 *
 * Schutzgeländer: Ein Addon bekommt höchstens so viele gleichzeitige
 * Anfragen, wie in seinen Einstellungen steht ({@code max_gleichzeitige_anfragen},
 * Vorgabe 25). Darüber hinaus wird abgewiesen (503) – ein durchgehendes Addon
 * bremst sich selbst und nicht die anderen.
 *
 * Eingebunden über META-INF/web-fragment.xml der Basisschicht; ein Addon muss
 * dafür nichts tun.
 */
public class Messfilter implements Filter {

    static final long LANGSAM_MS = 5000;

    private static final java.lang.management.ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final com.sun.management.ThreadMXBean THREADS_SUN =
            THREADS instanceof com.sun.management.ThreadMXBean ? (com.sun.management.ThreadMXBean) THREADS : null;

    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain kette) throws IOException, ServletException {
        HttpServletRequest anfrage = (HttpServletRequest) req;
        String pfad = anfrage.getRequestURI();
        // Bilder, Stile, Skripte und die Abfrage des Live-Modus zählen nicht – sie sagen nichts über das Addon.
        if (pfad.contains("/javax.faces.resource/") || pfad.endsWith("/basiskit/live")) {
            kette.doFilter(req, res);
            return;
        }
        long aktiv = Messung.AKTIV.incrementAndGet();
        try {
            if (aktiv > Basiskit.maxGleichzeitig()) {
                Messung.abgewiesen();
                Protokoll.anfrage(pfad);
                Protokoll.warnung("Anfrage abgewiesen: mehr als " + Basiskit.maxGleichzeitig() + " gleichzeitig");
                ((HttpServletResponse) res).sendError(503, "Dieses Addon ist gerade ausgelastet – bitte gleich noch einmal.");
                return;
            }
            messen(anfrage, (HttpServletResponse) res, kette, pfad);
        } finally {
            Messung.AKTIV.decrementAndGet();
            Protokoll.anfrage(null);
            Protokoll.benutzer(null);
        }
    }

    private void messen(HttpServletRequest anfrage, HttpServletResponse antwort, FilterChain kette, String pfad)
            throws IOException, ServletException {
        long id = Thread.currentThread().getId();
        long cpu0 = THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : 0;
        long sp0 = THREADS_SUN != null ? THREADS_SUN.getThreadAllocatedBytes(id) : 0;
        long t0 = System.nanoTime();
        Protokoll.anfrage(pfad);
        Messung.anfrageBeginnt();
        boolean fehler = false;
        try {
            kette.doFilter(anfrage, antwort);
            fehler = antwort.getStatus() >= 500;
        } catch (IOException | ServletException | RuntimeException e) {
            fehler = true;
            Protokoll.fehler("Unbehandelter Fehler", e);
            throw e;
        } finally {
            long dauer = (System.nanoTime() - t0) / 1_000_000;
            long[] ks = Messung.anfrageEndet();
            long cpu = THREADS.isCurrentThreadCpuTimeSupported() ? (THREADS.getCurrentThreadCpuTime() - cpu0) / 1_000_000 : 0;
            long speicher = THREADS_SUN != null ? Math.max(0, THREADS_SUN.getThreadAllocatedBytes(id) - sp0) : 0;
            Messung.zaehlen(dauer, ks[0] / 1_000_000, ks[1], cpu, speicher, fehler, pfad, Protokoll.aktuellerBenutzer());
            if (dauer >= LANGSAM_MS) {
                Protokoll.warnung(String.format("Langsame Anfrage: %d ms, davon %d ms Kieselstein, %d MB Speicher",
                        dauer, ks[0] / 1_000_000, speicher / (1024 * 1024)));
            }
        }
    }
}

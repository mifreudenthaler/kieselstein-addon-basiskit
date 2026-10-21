package at.freudenthaler.kieselstein.addon.basis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Der Stand eines Addons im Live-Modus – für das automatische Neuladen im Browser.
 *
 * Im Live-Modus schreibt die Editor-Erweiterung nach jeder übernommenen
 * Änderung einen neuen Stand nach {@code addons/logs/live/<addon>.stand}. Der
 * Rahmen jeder Seite fragt hier jede Sekunde nach; ändert sich der Stand, lädt
 * die Seite neu. Ohne Live-Modus antwortet die Adresse mit 404, und der
 * Rahmen hört auf zu fragen. In der Produktion gibt es das nie.
 *
 * Eingebunden über META-INF/web-fragment.xml unter /basiskit/live.
 */
public class LiveStand extends HttpServlet {

    private static final long serialVersionUID = 1L;

    static Path datei(String addon) {
        return Path.of(System.getProperty("kieselstein.addons.dir", "."), "logs", "live", addon + ".stand");
    }

    /** Läuft dieses Addon im Live-Modus? */
    public static boolean aktiv() {
        return !"produktion".equalsIgnoreCase(Kieselstein.stufe()) && Files.exists(datei(Protokoll.addon()));
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setHeader("Cache-Control", "no-store");
        if (!aktiv()) {
            res.sendError(404);
            return;
        }
        res.setContentType("text/plain; charset=UTF-8");
        res.getOutputStream().write(Files.readString(datei(Protokoll.addon()), StandardCharsets.UTF_8).trim().getBytes(StandardCharsets.UTF_8));
    }
}

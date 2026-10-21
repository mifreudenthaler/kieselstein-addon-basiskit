package at.freudenthaler.kieselstein.addon.zentrale;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Die Tür der Produktions-Zentrale für freigegebene Pakete.
 *
 * Nimmt nur an, wenn dieser Addon-Server die Stufe „produktion" hat, die
 * Signatur der Test-Zentrale stimmt, die Übergabe frisch ist und die
 * Prüfsumme zum Paket passt. Dann: laufendes Paket ins Archiv, neues
 * einsetzen, auf den Start warten. Scheitert der Start, kommt das vorige
 * Paket von selbst zurück.
 *
 *   POST /freigabe/paket   (Kopfzeilen X-Addon, X-Version, X-Sha256, X-Von, X-Zeit, X-Signatur)
 *   GET  /freigabe/stand   (X-Zeit, X-Signatur über „stand|zeit") → Addon, Version, Zustand je Zeile
 */
@WebServlet("/freigabe/*")
public class FreigabeEmpfang extends HttpServlet {

    private static final long serialVersionUID = 1L;
    private static final long MAX_GROESSE = 200L * 1024 * 1024;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
        if (!"/stand".equals(req.getPathInfo())) {
            res.sendError(404);
            return;
        }
        if (!zulassen(req, res, "stand", req.getHeader("X-Zeit"))) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Startseite.Addon a : AddonOrdner.alle()) {
            sb.append(a.getName()).append('\t').append(a.getVersion()).append('\t').append(a.getZustand()).append('\n');
        }
        antworten(res, 200, sb.toString());
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse res) throws IOException {
        if (!"/paket".equals(req.getPathInfo())) {
            res.sendError(404);
            return;
        }
        String name = req.getHeader("X-Addon");
        String version = req.getHeader("X-Version");
        String sha = req.getHeader("X-Sha256");
        String von = req.getHeader("X-Von");
        String zeit = req.getHeader("X-Zeit");
        if (!zulassen(req, res, name, version, sha, von, zeit)) {
            return;
        }
        try {
            AddonOrdner.pruefeName(name);
        } catch (IllegalArgumentException e) {
            antworten(res, 400, e.getMessage());
            return;
        }
        if (AddonOrdner.ZENTRALE.equals(name)) {
            antworten(res, 400, "Die Zentrale wird mit dem Basiskit aktualisiert, nicht über die Freigabe.");
            return;
        }

        Path eingang = Files.createTempFile(AddonOrdner.archiv(), "." + name, ".eingang");
        try {
            try (InputStream in = req.getInputStream()) {
                Files.copy(in, eingang, StandardCopyOption.REPLACE_EXISTING);
            }
            if (Files.size(eingang) > MAX_GROESSE) {
                antworten(res, 413, "Paket zu groß");
                return;
            }
            if (!AddonOrdner.sha256(eingang).equalsIgnoreCase(sha)) {
                antworten(res, 400, "Prüfsumme passt nicht – das Paket ist unterwegs verändert worden");
                return;
            }
            Path vorher = AddonOrdner.archivieren(name);
            AddonOrdner.einsetzen(name, eingang);
            AddonOrdner.verlauf(von, "Freigabe eingesetzt", name, version);
            if (AddonOrdner.wartenBisGestartet(name, 120)) {
                AddonOrdner.verlauf(von, "läuft in Produktion", name, version);
                antworten(res, 200, "ok\t" + name + " " + version + " läuft in Produktion");
                return;
            }
            if (vorher != null) {
                AddonOrdner.einsetzen(name, vorher);
                boolean zurueck = AddonOrdner.wartenBisGestartet(name, 120);
                AddonOrdner.verlauf(von, zurueck ? "Start gescheitert – vorige Version läuft wieder" : "Start gescheitert – Rückfall auch gescheitert",
                        name, AddonOrdner.version(vorher));
                antworten(res, 409, "Start in Produktion gescheitert – " + (zurueck
                        ? "die vorige Version " + AddonOrdner.version(vorher) + " läuft wieder"
                        : "auch die vorige Version startet nicht, bitte nachsehen"));
            } else {
                AddonOrdner.verlauf(von, "Start gescheitert", name, version);
                antworten(res, 409, "Start in Produktion gescheitert (es gab keine vorige Version)");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            antworten(res, 500, "unterbrochen");
        } finally {
            Files.deleteIfExists(eingang);
        }
    }

    private static boolean zulassen(HttpServletRequest req, HttpServletResponse res, String... teile) throws IOException {
        if (Freigabe.istTest()) {
            antworten(res, 403, "Dieser Addon-Server ist ein Test – Freigaben nimmt nur die Produktion an.");
            return false;
        }
        String zeit = teile[teile.length - 1];
        for (String t : teile) {
            if (t == null || t.isBlank()) {
                antworten(res, 400, "Angaben unvollständig");
                return false;
            }
        }
        if (!Freigabe.frisch(zeit) || !Freigabe.signaturStimmt(req.getHeader("X-Signatur"), teile)) {
            AddonOrdner.verlauf(req.getRemoteAddr(), "Freigabe abgewiesen (Signatur oder Zeit)", String.valueOf(teile[0]), "");
            antworten(res, 403, "Signatur oder Zeit stimmt nicht");
            return false;
        }
        return true;
    }

    private static void antworten(HttpServletResponse res, int status, String text) throws IOException {
        res.setStatus(status);
        res.setContentType("text/plain; charset=UTF-8");
        res.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
    }
}

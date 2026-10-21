package at.freudenthaler.kieselstein.addon.__PAKET__;

import java.io.Serializable;

import javax.faces.view.ViewScoped;
import javax.inject.Inject;
import javax.inject.Named;

import at.freudenthaler.kieselstein.addon.basis.Einstellungen;
import at.freudenthaler.kieselstein.addon.basis.Sitzung;

/**
 * Die Seite des Addons „__TITEL__".
 *
 * Hier gehört die Fachlogik hin. Was das Basiskit schon erledigt:
 * Anmeldung (Sitzung), Rechte (sitzung.hatRecht), Fassaden
 * (Kieselstein.fassade / Kieselstein.rufe), Listen wie im Client (Liste),
 * Einstellungen dieser Installation (Einstellungen.fuer), Rahmen und Design.
 */
@Named
@ViewScoped
public class Seite implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Das Kieselstein-Recht, ohne das man hier nichts sieht – anpassen. */
    static final String RECHT_LESEN = "LP_SYSTEM_R";

    @Inject
    Sitzung sitzung;

    private Boolean darf;

    public boolean isDarf() {
        if (darf == null) {
            darf = sitzung.hatRecht(RECHT_LESEN);
        }
        return darf;
    }

    public String getBegruessung() {
        return Texte.begruessung(sitzung.getBenutzer(),
                Einstellungen.fuer("__NAME__").text("gruss", "Willkommen"));
    }
}

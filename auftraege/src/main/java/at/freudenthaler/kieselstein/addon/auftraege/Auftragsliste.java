package at.freudenthaler.kieselstein.addon.auftraege;

import java.awt.Color;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.annotation.PreDestroy;
import javax.faces.view.ViewScoped;
import javax.inject.Inject;
import javax.inject.Named;

import org.primefaces.model.FilterMeta;
import org.primefaces.model.LazyDataModel;
import org.primefaces.model.SortMeta;

import com.lp.server.util.fastlanereader.service.query.QueryParameters;

import at.freudenthaler.kieselstein.addon.basis.Liste;
import at.freudenthaler.kieselstein.addon.basis.Sitzung;

/**
 * Die Auftragsliste – dieselbe Abfrage wie die Auftragsliste im Kieselstein-Client
 * (FastLaneReader, Anwendungsfall Auftrag), seitenweise in eine Tabelle geholt.
 *
 * Gelesen wird nur, wer das Leserecht auf Aufträge hat (AUFT_AUFTRAG_R) – am
 * Kieselstein-Server geprüft.
 */
@Named
@ViewScoped
public class Auftragsliste implements Serializable {

    private static final long serialVersionUID = 1L;

    static final String RECHT_LESEN = "AUFT_AUFTRAG_R";

    @Inject
    Sitzung sitzung;

    private Liste liste;
    private final Modell modell = new Modell();
    private Boolean darfLesen;

    public boolean isDarfLesen() {
        if (darfLesen == null) {
            darfLesen = sitzung.hatRecht(RECHT_LESEN);
        }
        return darfLesen;
    }

    public LazyDataModel<Auftrag> getModell() {
        return modell;
    }

    public long getAnzahl() {
        return liste == null ? 0 : liste.getAnzahl();
    }

    private Liste liste() {
        if (liste == null) {
            liste = new Liste(QueryParameters.UC_ID_AUFTRAG, sitzung.getClient());
            liste.oeffnen();
        }
        return liste;
    }

    @PreDestroy
    void schliessen() {
        if (liste != null) {
            liste.schliessen();
        }
    }

    /** Liest die Spalten über ihre Namen – die Reihenfolge darf sich in Kieselstein ändern. */
    private Auftrag auftrag(Object[] z) {
        Liste l = liste();
        Auftrag a = new Auftrag();
        a.id = (Integer) wert(z, l.spalte("flrauftrag.i_id"));
        a.nummer = text(wert(z, l.spalte("flrauftrag.c_nr")));
        a.kunde = text(wert(z, l.spalteNachUeberschrift("Kunde")));
        a.ort = text(wert(z, l.spalteNachUeberschrift("Ort")));
        a.projekt = text(wert(z, l.spalte("flrauftrag.c_bez")));
        a.termin = (Date) wert(z, l.spalte("flrauftrag.t_liefertermin"));
        a.datum = (Date) wert(z, l.spalte("flrauftrag.t_belegdatum"));
        a.vertreter = text(wert(z, l.spalte("flrauftrag.flrvertreter.c_kurzzeichen")));
        Object status = wert(z, l.spalte("flrauftrag.auftragstatus_c_nr"));
        if (status instanceof Object[]) {
            Object[] s = (Object[]) status;
            a.statusCode = text(s.length > 0 ? s[0] : null);
            a.statusText = text(s.length > 1 ? s[1] : s[0]);
            a.versendet = s.length > 2 && s[2] instanceof Date ? (Date) s[2] : null;
            a.versandArt = s.length > 3 ? text(s[3]) : null;
        } else {
            a.statusCode = a.statusText = text(status);
        }
        Object w = wert(z, l.spalte("flrauftrag.n_gesamtauftragswertinauftragswaehrung"));
        a.wert = w instanceof BigDecimal ? (BigDecimal) w : null;
        a.waehrung = text(wert(z, l.spalte("flrauftrag.waehrung_c_nr_auftragswaehrung")));
        Object f = z[z.length - 1];
        a.farbe = f instanceof Color ? (Color) f : null;
        return a;
    }

    private static Object wert(Object[] z, int i) {
        return i >= 0 && i < z.length ? z[i] : null;
    }

    private static String text(Object o) {
        return o == null ? null : o.toString().trim();
    }

    /** Die Tabelle holt nur die Seite, die sie gerade zeigt. */
    final class Modell extends LazyDataModel<Auftrag> {
        private static final long serialVersionUID = 1L;

        @Override
        public int count(Map<String, FilterMeta> filter) {
            return (int) liste().getAnzahl();
        }

        @Override
        public List<Auftrag> load(int erste, int wieviele, Map<String, SortMeta> sortierung, Map<String, FilterMeta> filter) {
            List<Auftrag> seite = new ArrayList<>(wieviele);
            for (Object[] z : liste().zeilen(erste, wieviele)) {
                seite.add(auftrag(z));
            }
            return seite;
        }

        @Override
        public String getRowKey(Auftrag a) {
            return String.valueOf(a.getId());
        }
    }
}

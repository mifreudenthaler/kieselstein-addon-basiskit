package at.freudenthaler.kieselstein.addon.basis;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.UUID;

import com.lp.server.system.fastlanereader.service.FastLaneReader;
import com.lp.server.system.service.TheClientDto;
import com.lp.server.util.fastlanereader.service.query.FilterBlock;
import com.lp.server.util.fastlanereader.service.query.FilterKriterium;
import com.lp.server.util.fastlanereader.service.query.QueryParameters;
import com.lp.server.util.fastlanereader.service.query.QueryResult;
import com.lp.server.util.fastlanereader.service.query.SortierKriterium;
import com.lp.server.util.fastlanereader.service.query.TableInfo;

/**
 * Eine Liste, wie Kieselstein sie im Client zeigt – über den FastLaneReader am Server.
 *
 * Damit gelten dieselben Spalten, dieselbe Übersetzung des Status und
 * dieselben Einschränkungen wie im Client, ohne eine Zeile SQL. Die Liste
 * lebt am Server unter einer eigenen Kennung und wird seitenweise geholt;
 * {@link #schliessen()} gibt sie dort wieder frei.
 *
 * Beispiel: {@code new Liste(QueryParameters.UC_ID_AUFTRAG, sitzung.getClient())}.
 */
public class Liste implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int useCase;
    private final String kennung = UUID.randomUUID().toString();
    private final transient TheClientDto client;
    private String[] datenbankSpalten;
    private Object[] ueberschriften;
    private long anzahl = -1;

    public Liste(int useCase, TheClientDto client) {
        this.useCase = useCase;
        this.client = client;
    }

    private FastLaneReader flr() {
        return Kieselstein.fassade(FastLaneReader.class);
    }

    /** Erste Abfrage – ohne Filter, in der Sortierung des Clients. */
    public QueryResult oeffnen() {
        QueryParameters q = new QueryParameters(useCase, new SortierKriterium[0],
                new FilterBlock(new FilterKriterium[0], FilterBlock.Operator.AND), 0, new ArrayList<Object>());
        QueryResult r = Kieselstein.rufe(() -> flr().setQuery(kennung, useCase, q, client));
        anzahl = r.getRowCount();
        TableInfo info = Kieselstein.rufe(() -> flr().getTableInfo(kennung, useCase, null, client));
        datenbankSpalten = info.getDataBaseColumnNames();
        ueberschriften = info.getColumnHeaderValues();
        return r;
    }

    /** Die Seite, in der diese Zeile liegt (Kieselstein liefert Seiten zu 50 Zeilen). */
    public QueryResult seiteAb(long zeile) {
        if (anzahl < 0) {
            oeffnen();
        }
        return Kieselstein.rufe(() -> flr().getPageAt(kennung, useCase, (int) zeile, client));
    }

    /**
     * Zeilen ab einer Position, so viele wie gewünscht. Kieselstein liefert
     * Seiten mit eigenen Grenzen; hier werden sie zu genau dem Ausschnitt
     * zusammengesetzt, den eine Tabelle anfordert.
     */
    public java.util.List<Object[]> zeilen(long ab, int wieviele) {
        if (anzahl < 0) {
            oeffnen();
        }
        java.util.List<Object[]> ergebnis = new ArrayList<>(wieviele);
        long naechste = ab;
        long ende = Math.min(ab + wieviele, anzahl);
        while (naechste < ende) {
            QueryResult seite = seiteAb(naechste);
            Object[][] daten = seite.getRowData();
            if (daten == null || daten.length == 0) {
                break;
            }
            long erste = seite.getIndexOfFirstRow();
            int vorher = ergebnis.size();
            for (int i = 0; i < daten.length; i++) {
                long pos = erste + i;
                if (pos >= naechste && pos < ende) {
                    ergebnis.add(daten[i]);
                }
            }
            if (ergebnis.size() == vorher) {
                break; // keine neuen Zeilen – nicht im Kreis laufen
            }
            naechste = ab + ergebnis.size();
        }
        return ergebnis;
    }

    public long getAnzahl() {
        return anzahl;
    }

    /** Spaltennummer zu einem Datenbanknamen wie {@code flrauftrag.c_nr}, oder -1. */
    public int spalte(String datenbankName) {
        for (int i = 0; i < datenbankSpalten.length; i++) {
            if (datenbankName.equals(datenbankSpalten[i] == null ? null : datenbankSpalten[i].trim())) {
                return i;
            }
        }
        return -1;
    }

    /** Spaltennummer zu einer Überschrift wie im Client, z. B. „Kunde", oder -1. */
    public int spalteNachUeberschrift(String ueberschrift) {
        for (int i = 0; i < ueberschriften.length; i++) {
            if (ueberschriften[i] != null && ueberschrift.equals(ueberschriften[i].toString().trim())) {
                return i;
            }
        }
        return -1;
    }

    public void schliessen() {
        try {
            flr().cleanup(kennung, useCase, client);
        } catch (Exception e) {
            // Kieselstein räumt liegengebliebene Listen selbst ab.
        }
    }
}

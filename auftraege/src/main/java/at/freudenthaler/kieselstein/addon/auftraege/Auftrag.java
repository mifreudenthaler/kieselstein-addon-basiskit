package at.freudenthaler.kieselstein.addon.auftraege;

import java.awt.Color;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

/**
 * Eine Zeile der Auftragsliste – genau das, was der Kieselstein-Client in
 * seiner Auftragsliste zeigt, aus derselben Abfrage am Server.
 */
public class Auftrag implements Serializable {

    private static final long serialVersionUID = 1L;

    Integer id;
    String nummer;
    String kunde;
    String ort;
    String projekt;
    Date termin;
    Date datum;
    String vertreter;
    String statusCode;
    String statusText;
    Date versendet;
    String versandArt;
    BigDecimal wert;
    String waehrung;
    Color farbe;

    public Integer getId() { return id; }
    public String getNummer() { return nummer; }
    public String getKunde() { return kunde; }
    public String getOrt() { return ort; }
    public String getProjekt() { return projekt; }
    public Date getTermin() { return termin; }
    public Date getDatum() { return datum; }
    public String getVertreter() { return vertreter; }
    public String getStatusText() { return statusText; }
    public Date getVersendet() { return versendet; }
    public String getVersandArt() { return versandArt; }
    public BigDecimal getWert() { return wert; }
    public String getWaehrung() { return waehrung; }

    /** CSS-Klasse für den Status-Chip, aus dem Kieselstein-Statuscode (z. B. „Offen"). */
    public String getStatusKlasse() {
        String c = statusCode == null ? "" : statusCode.trim().toLowerCase()
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace(" ", "");
        return "ks-status ks-status-" + c;
    }

    /** Noch nicht erledigt und der Termin ist vorbei. */
    public boolean isUeberfaellig() {
        if (termin == null || statusCode == null) {
            return false;
        }
        String c = statusCode.trim();
        boolean laeuft = c.equalsIgnoreCase("Offen") || c.equalsIgnoreCase("Teilerledigt") || c.equalsIgnoreCase("Angelegt");
        LocalDate t = termin.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return laeuft && t.isBefore(LocalDate.now());
    }

    /**
     * Die Zeilenfarbe wie im Client: Rot mit Pönale, Magenta mit Pönale und
     * Eingangsrechnungen, Blau mit Eingangsrechnungen, Grau nicht verrechenbar.
     */
    public String getZeilenStil() {
        if (Color.RED.equals(farbe)) {
            return "color:#b3261e";
        }
        if (Color.MAGENTA.equals(farbe)) {
            return "color:#a0307a";
        }
        if (Color.BLUE.equals(farbe)) {
            return "color:#1f4fa3";
        }
        if (Color.LIGHT_GRAY.equals(farbe)) {
            return "color:#8a9499";
        }
        return "";
    }

    public String getMerkmal() {
        if (farbe == null) {
            return null;
        }
        if (Color.RED.equals(farbe)) {
            return "Pönale";
        }
        if (Color.MAGENTA.equals(farbe)) {
            return "Pönale · ER";
        }
        if (Color.BLUE.equals(farbe)) {
            return "ER";
        }
        if (Color.LIGHT_GRAY.equals(farbe)) {
            return "nicht verrechenbar";
        }
        return null;
    }
}

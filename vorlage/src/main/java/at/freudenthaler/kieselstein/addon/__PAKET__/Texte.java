package at.freudenthaler.kieselstein.addon.__PAKET__;

/** Reine Fachlogik ohne Kieselstein – so lässt sie sich schnell testen. */
final class Texte {

    private Texte() {
    }

    static String begruessung(String benutzer, String gruss) {
        return gruss + ", " + (benutzer == null || benutzer.isBlank() ? "unbekannt" : benutzer.trim()) + ".";
    }
}

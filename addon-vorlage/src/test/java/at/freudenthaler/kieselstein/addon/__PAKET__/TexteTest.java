package at.freudenthaler.kieselstein.addon.__PAKET__;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TexteTest {

    @Test
    void begruessungMitName() {
        assertEquals("Willkommen, Admin.", Texte.begruessung(" Admin ", "Willkommen"));
    }

    @Test
    void begruessungOhneName() {
        assertEquals("Hallo, unbekannt.", Texte.begruessung(null, "Hallo"));
    }
}

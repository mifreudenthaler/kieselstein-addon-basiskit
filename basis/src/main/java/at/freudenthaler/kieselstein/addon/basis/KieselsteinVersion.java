package at.freudenthaler.kieselstein.addon.basis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Gegen welche Kieselstein-Version dieser Addon-Server eingerichtet ist.
 *
 * Das Einrichtungsskript schreibt sie nach config/kieselstein-version, aus
 * denselben Client-Bibliotheken, die als Modul im Server liegen. Damit passt
 * die Version, mit der sich der Addon-Server anmeldet, zu den Schnittstellen,
 * die er benutzt.
 */
public final class KieselsteinVersion {

    private KieselsteinVersion() {
    }

    public static String lesen() {
        String ordner = System.getProperty("kieselstein.addons.dir");
        if (ordner != null) {
            try {
                return Files.readString(Path.of(ordner, "config", "kieselstein-version")).trim();
            } catch (IOException e) {
                // fällt unten auf den Vorgabewert
            }
        }
        return "2.0.0";
    }
}

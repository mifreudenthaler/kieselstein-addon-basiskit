package at.freudenthaler.kieselstein.addon.zentrale;

import java.io.Serializable;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import javax.faces.application.FacesMessage;
import javax.faces.context.FacesContext;
import javax.faces.view.ViewScoped;
import javax.inject.Inject;
import javax.inject.Named;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import at.freudenthaler.kieselstein.addon.basis.Kieselstein;
import at.freudenthaler.kieselstein.addon.basis.Sitzung;

/**
 * Was die Addons verbrauchen, und wer schuld ist, wenn es langsam wird.
 *
 * Je Addon: Anfragen, Fehler, abgewiesene Anfragen, mittlere Dauer – getrennt
 * in Wartezeit auf Kieselstein und eigene Zeit –, Rechenzeit und angelegter
 * Speicher, jeweils mit Anteil am ganzen Addon-Server. Dazu der Zustand des
 * Addon-Servers selbst und die langsamsten Anfragen.
 *
 * Der Speicher ist eine Zuordnung über die Anfragen: Was ein Addon während
 * seiner Anfragen anlegt, zählt zu ihm. Für den Ernstfall gibt es die
 * Speicheraufnahme: Sie zählt, was gerade im Speicher liegt, nach Herkunft.
 */
@Named
@ViewScoped
public class Ressourcen implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("dd.MM. HH:mm:ss");

    @Inject
    Sitzung sitzung;

    private String zeitraum = "60";
    private List<Zeile> zeilen;
    private List<Aufnahme> aufnahme;
    private String aufnahmeZeit;

    public String getZeitraum() { return zeitraum; }
    public void setZeitraum(String z) { this.zeitraum = z; this.zeilen = null; }

    private int minuten() {
        return "1440".equals(zeitraum) ? 1440 : 60;
    }

    public List<Zeile> getZeilen() {
        if (zeilen == null) {
            List<Auswertung> a = AddonOrdner.alle().stream()
                    .map(x -> Auswertung.von(x.getName(), minuten(), minuten() == 60 ? 30 : 48))
                    .collect(Collectors.toList());
            long cpuSumme = Math.max(1, a.stream().mapToLong(x -> x.cpuMs).sum());
            long speicherSumme = Math.max(1, a.stream().mapToLong(x -> x.speicherBytes).sum());
            zeilen = new ArrayList<>();
            for (Auswertung x : a) {
                zeilen.add(new Zeile(x, titel(x.addon), 100.0 * x.cpuMs / cpuSumme, 100.0 * x.speicherBytes / speicherSumme));
            }
            zeilen.sort((p, q) -> Long.compare(q.a.cpuMs + q.a.speicherBytes / 1_000_000, p.a.cpuMs + p.a.speicherBytes / 1_000_000));
        }
        return zeilen;
    }

    private static String titel(String name) {
        return AddonOrdner.alle().stream().filter(a -> a.getName().equals(name)).map(Startseite.Addon::getTitel)
                .findFirst().orElse(name);
    }

    /** Die langsamsten Anfragen aller Addons im Zeitraum. */
    public List<String[]> getLangsam() {
        List<String[]> alle = new ArrayList<>();
        for (Zeile z : getZeilen()) {
            for (String[] l : z.a.langsam) {
                String zeit = LocalDateTime.ofInstant(Instant.ofEpochMilli(Long.parseLong(l[0])), ZoneId.systemDefault()).format(ZEIT);
                alle.add(new String[] { zeit, z.titel, l[1], l[2], String.valueOf(Long.parseLong(l[3]) / (1024 * 1024)), l[4], l[5] });
            }
        }
        alle.sort((p, q) -> Long.compare(Long.parseLong(q[2]), Long.parseLong(p[2])));
        return alle.size() > 15 ? alle.subList(0, 15) : alle;
    }

    // --- Addon-Server --------------------------------------------------------

    public Map<String, String> getServer() {
        Map<String, String> m = new LinkedHashMap<>();
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        long belegt = mem.getHeapMemoryUsage().getUsed() / (1024 * 1024);
        long max = mem.getHeapMemoryUsage().getMax() / (1024 * 1024);
        m.put("Speicher (Heap)", belegt + " von " + max + " MB · " + Math.round(100.0 * belegt / max) + " %");
        m.put("Speicher sonst (Non-Heap)", mem.getNonHeapMemoryUsage().getUsed() / (1024 * 1024) + " MB");
        java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.OperatingSystemMXBean) {
            com.sun.management.OperatingSystemMXBean s = (com.sun.management.OperatingSystemMXBean) os;
            m.put("Rechenlast Addon-Server", Math.round(Math.max(0, s.getProcessCpuLoad()) * 100) + " % · Rechner " + Math.round(Math.max(0, s.getCpuLoad()) * 100) + " %");
        }
        m.put("Arbeitsfäden", ManagementFactory.getThreadMXBean().getThreadCount() + "");
        RuntimeMXBean rt = ManagementFactory.getRuntimeMXBean();
        long h = rt.getUptime() / 3_600_000;
        m.put("Läuft seit", h >= 24 ? (h / 24) + " Tagen " + (h % 24) + " h" : h + " h " + (rt.getUptime() / 60_000 % 60) + " min");
        long ping = Kieselstein.ping();
        m.put("Kieselstein", ping < 0 ? "nicht erreichbar (" + Kieselstein.server() + ")" : "antwortet in " + ping + " ms (" + Kieselstein.server() + ")");
        return m;
    }

    // --- Speicheraufnahme ----------------------------------------------------

    /**
     * Zählt, was gerade im Speicher liegt, nach Herkunft: je Addon (über sein
     * Java-Paket), Kieselstein-Datenobjekte, Oberfläche (JSF/PrimeFaces),
     * Sonstiges. Löst eine volle Speicherbereinigung aus – kurz spürbar.
     */
    public void aufnehmen() {
        if (!sitzung.hatRecht(Startseite.RECHT_VERWALTEN)) {
            melden(FacesMessage.SEVERITY_ERROR, "Dafür fehlt das Recht " + Startseite.RECHT_VERWALTEN);
            return;
        }
        try {
            MBeanServer s = ManagementFactory.getPlatformMBeanServer();
            String text = (String) s.invoke(new ObjectName("com.sun.management:type=DiagnosticCommand"),
                    "gcClassHistogram", new Object[] { new String[0] }, new String[] { String[].class.getName() });
            Map<String, long[]> gruppen = new LinkedHashMap<>();
            Map<String, String> pakete = new LinkedHashMap<>();
            for (Startseite.Addon a : AddonOrdner.alle()) {
                pakete.put("at.freudenthaler.kieselstein.addon." + a.getName().replace("-", "") + ".", a.getTitel());
            }
            for (String zeile : text.split("\n")) {
                String[] t = zeile.trim().split("\\s+");
                if (t.length < 4 || !t[0].endsWith(":")) {
                    continue;
                }
                long anzahl = Long.parseLong(t[1]);
                long bytes = Long.parseLong(t[2]);
                String klasse = t[3];
                String gruppe = null;
                for (Map.Entry<String, String> p : pakete.entrySet()) {
                    if (klasse.startsWith(p.getKey())) {
                        gruppe = "Addon " + p.getValue();
                        break;
                    }
                }
                if (gruppe == null) {
                    if (klasse.startsWith("at.freudenthaler.kieselstein.addon.basis.")) {
                        gruppe = "Basisschicht (alle Addons)";
                    } else if (klasse.startsWith("com.lp.") || klasse.startsWith("com.heliumv.")) {
                        gruppe = "Kieselstein-Datenobjekte (alle Addons)";
                    } else if (klasse.startsWith("org.primefaces.") || klasse.startsWith("com.sun.faces.") || klasse.startsWith("javax.faces.")) {
                        gruppe = "Oberfläche (JSF, PrimeFaces)";
                    } else {
                        gruppe = "Addon-Server und Java";
                    }
                }
                long[] g = gruppen.computeIfAbsent(gruppe, k -> new long[2]);
                g[0] += anzahl;
                g[1] += bytes;
            }
            long summe = Math.max(1, gruppen.values().stream().mapToLong(g -> g[1]).sum());
            aufnahme = gruppen.entrySet().stream()
                    .map(e -> new Aufnahme(e.getKey(), e.getValue()[0], e.getValue()[1], 100.0 * e.getValue()[1] / summe))
                    .sorted((p, q) -> Long.compare(q.bytes, p.bytes)).collect(Collectors.toList());
            aufnahmeZeit = LocalDateTime.now().format(ZEIT);
            AddonOrdner.verlauf(sitzung.getBenutzer(), "Speicheraufnahme", "-", "");
        } catch (Exception e) {
            melden(FacesMessage.SEVERITY_ERROR, "Speicheraufnahme nicht möglich: " + e.getMessage());
        }
    }

    public List<Aufnahme> getAufnahme() { return aufnahme; }
    public String getAufnahmeZeit() { return aufnahmeZeit; }

    private static void melden(FacesMessage.Severity s, String text) {
        FacesContext.getCurrentInstance().addMessage(null, new FacesMessage(s, text, null));
    }

    // --- Zeilen --------------------------------------------------------------

    public static final class Zeile implements Serializable {
        private static final long serialVersionUID = 1L;
        final Auswertung a;
        final String titel;
        final double anteilCpu;
        final double anteilSpeicher;

        Zeile(Auswertung a, String titel, double anteilCpu, double anteilSpeicher) {
            this.a = a;
            this.titel = titel;
            this.anteilCpu = anteilCpu;
            this.anteilSpeicher = anteilSpeicher;
        }

        public String getTitel() { return titel; }
        public String getName() { return a.addon; }
        public long getAnfragen() { return a.anfragen; }
        public long getFehler() { return a.fehler; }
        public long getAbgewiesen() { return a.abgewiesen; }
        public long getSchnittMs() { return a.schnitt(a.dauerMs); }
        public long getSchnittKieselsteinMs() { return a.schnitt(a.kieselsteinMs); }
        public long getSchnittEigenMs() { return a.schnitt(a.eigeneMs()); }
        public long getMaxMs() { return a.maxDauerMs; }
        public String getCpu() { return String.format(Locale.GERMANY, "%.1f s", a.cpuMs / 1000.0); }
        public String getSpeicher() { return String.format(Locale.GERMANY, "%.0f MB", a.speicherBytes / 1048576.0); }
        public String getSpeicherJeAnfrage() {
            return a.anfragen == 0 ? "–" : String.format(Locale.GERMANY, "%.0f KB", a.speicherBytes / 1024.0 / a.anfragen);
        }
        public long getAnteilCpu() { return Math.round(anteilCpu); }
        public long getAnteilSpeicher() { return Math.round(anteilSpeicher); }
        public String getLinie() { return a.linie(); }
        /** Wer bremst? Der größere Anteil an der mittleren Dauer. */
        public String getUrsache() {
            if (a.anfragen == 0) {
                return "";
            }
            return a.kieselsteinMs > a.eigeneMs() ? "meist Wartezeit auf Kieselstein" : "meist eigene Zeit des Addons";
        }
        public int getBalkenKieselstein() {
            return a.dauerMs == 0 ? 0 : (int) Math.round(100.0 * a.kieselsteinMs / a.dauerMs);
        }
    }

    public static final class Aufnahme implements Serializable {
        private static final long serialVersionUID = 1L;
        final String gruppe;
        final long anzahl;
        final long bytes;
        final double anteil;

        Aufnahme(String gruppe, long anzahl, long bytes, double anteil) {
            this.gruppe = gruppe;
            this.anzahl = anzahl;
            this.bytes = bytes;
            this.anteil = anteil;
        }

        public String getGruppe() { return gruppe; }
        public String getAnzahl() { return String.format(Locale.GERMANY, "%,d", anzahl); }
        public String getMegabyte() { return String.format(Locale.GERMANY, "%.1f MB", bytes / 1048576.0); }
        public long getAnteil() { return Math.round(anteil); }
    }
}

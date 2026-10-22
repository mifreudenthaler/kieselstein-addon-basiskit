'use strict';
/*
 * Live-Modus: ein Addon auf der ersten Stufe ausgepackt laufen lassen und jede
 * Änderung auf dem kürzesten Weg übernehmen.
 *
 *   Seiten, Stile, Bilder (src/main/webapp)   → hineinkopiert, sofort sichtbar
 *   Java (src/main/java)                       → übersetzt, Addon neu gestartet (wenige Sekunden)
 *   Texte, Einstellungen (src/main/resources)  → übernommen, Addon neu gestartet
 *   WEB-INF, Basisschicht, build.gradle        → ganz neu gebaut und gestartet
 *
 * Nach jeder Übernahme schreibt der Live-Modus einen neuen Stand nach
 * addons/logs/live/<addon>.stand; die Seite im Browser lädt sich daraufhin neu.
 *
 * Nur, wenn die erste Stufe diese Maschine ist (Editor über Remote-SSH auf dem
 * Server). In die Produktion kommt so nie etwas: Beenden packt das Addon
 * wieder ein, ausgerollt wird wie immer über die Zentrale.
 */
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const w = require('./werkzeug');

const pause = (ms) => new Promise((r) => setTimeout(r, ms));

class Live {
  constructor({ wurzel, addon, server, optionen = {}, melde = () => {} }) {
    if (server.ssh) {
      throw new Error('Der Live-Modus braucht die erste Stufe auf dieser Maschine – den Editor per Remote-SSH mit dem Server verbinden.');
    }
    this.wurzel = wurzel;
    this.addon = addon; // Name, z. B. auftraege
    this.server = server;
    this.optionen = optionen;
    this.melde = melde;
    this.addons = `${server.kieselstein.replace(/\/$/, '')}/addons`;
    this.deployments = path.join(this.addons, 'deployments');
    this.ziel = path.join(this.deployments, `${addon}.war`);
    this.standDatei = path.join(this.addons, 'logs', 'live', `${addon}.stand`);
    this.kette = Promise.resolve();
  }

  marke(endung) {
    return `${this.ziel}${endung}`;
  }

  /** Läuft für dieses Addon schon ein Live-Modus (ausgepackt)? */
  static aktiv(server, addon) {
    const ziel = path.join(server.kieselstein.replace(/\/$/, ''), 'addons', 'deployments', `${addon}.war`);
    return !server.ssh && fs.existsSync(ziel) && fs.statSync(ziel).isDirectory();
  }

  /** Alle Addons, die auf diesem Server gerade ausgepackt laufen. */
  static laufende(server) {
    if (server.ssh) return [];
    const d = path.join(server.kieselstein.replace(/\/$/, ''), 'addons', 'deployments');
    if (!fs.existsSync(d)) return [];
    return fs.readdirSync(d).filter((n) => n.endsWith('.war') && fs.statSync(path.join(d, n)).isDirectory()).map((n) => n.slice(0, -4));
  }

  stand() {
    fs.mkdirSync(path.dirname(this.standDatei), { recursive: true });
    fs.writeFileSync(this.standDatei, String(Date.now()));
  }

  /** Auf WildFly warten: Paket gestartet (true) oder gescheitert (Fehler). */
  async warten(sekunden = 120) {
    const ende = Date.now() + sekunden * 1000;
    while (Date.now() < ende) {
      if (fs.existsSync(this.marke('.failed'))) {
        throw new Error(`WildFly konnte ${this.addon} nicht starten:\n${fs.readFileSync(this.marke('.failed'), 'utf8').slice(0, 1500)}`);
      }
      if (fs.existsSync(this.marke('.deployed')) && !fs.existsSync(this.marke('.dodeploy')) && !fs.existsSync(this.marke('.isdeploying'))) return;
      await pause(250);
    }
    throw new Error(`${this.addon} ist nach ${sekunden} s nicht gestartet`);
  }

  /** Herausnehmen und warten, bis WildFly es losgelassen hat. */
  async herausnehmen() {
    if (!fs.existsSync(this.marke('.deployed'))) return;
    fs.rmSync(this.marke('.deployed'), { force: true });
    const ende = Date.now() + 60000;
    while (Date.now() < ende && !fs.existsSync(this.marke('.undeployed'))) await pause(250);
  }

  /** WAR auspacken und für die Entwicklung einstellen. */
  auspacken(war) {
    fs.rmSync(this.ziel, { recursive: true, force: true });
    fs.mkdirSync(this.ziel, { recursive: true });
    try {
      execFileSync('unzip', ['-q', '-o', war, '-d', this.ziel]);
    } catch (e) {
      execFileSync('python3', ['-c', 'import sys,zipfile; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])', war, this.ziel]);
    }
    // Development: JSF liest Seiten und Stile bei jedem Aufruf frisch, statt sie zwischenzuspeichern.
    const webxml = path.join(this.ziel, 'WEB-INF', 'web.xml');
    if (fs.existsSync(webxml)) {
      let x = fs.readFileSync(webxml, 'utf8');
      x = x.replace(/(<param-name>javax\.faces\.PROJECT_STAGE<\/param-name>\s*<param-value>)[^<]*/, '$1Development')
        .replace(/(<param-name>com\.sun\.faces\.defaultResourceMaxAge<\/param-name>\s*<param-value>)[^<]*/, '$10');
      fs.writeFileSync(webxml, x);
    }
  }

  async neuStarten() {
    for (const e of ['.failed', '.undeployed', '.skipdeploy']) fs.rmSync(this.marke(e), { force: true });
    fs.rmSync(this.marke('.deployed'), { force: true });
    fs.writeFileSync(this.marke('.dodeploy'), '');
    await this.warten();
  }

  async starten() {
    const t0 = Date.now();
    this.melde(`Live-Modus ${this.addon}: bauen …`);
    const war = await w.bauen(this.wurzel, this.addon, this.optionen);
    await this.herausnehmen();
    for (const e of ['.failed', '.undeployed', '.skipdeploy', '.dodeploy']) fs.rmSync(this.marke(e), { force: true });
    if (fs.existsSync(this.ziel) && !fs.statSync(this.ziel).isDirectory()) fs.rmSync(this.ziel);
    this.auspacken(war);
    this.melde(`Live-Modus ${this.addon}: starten …`);
    await this.neuStarten();
    this.stand();
    this.melde(`Live-Modus ${this.addon} läuft (${((Date.now() - t0) / 1000).toFixed(1)} s)`);
  }

  /** Was eine geänderte Datei braucht. */
  art(datei) {
    const rel = path.relative(w.addonOrdner(this.wurzel, this.addon), datei).split(path.sep).join('/');
    const vonWurzel = path.relative(this.wurzel, datei).split(path.sep).join('/');
    if (rel.startsWith('src/main/webapp/WEB-INF/')) return 'ganz';
    if (rel.startsWith('src/main/webapp/')) return 'seite';
    if (rel.startsWith('src/main/java/')) return 'java';
    if (rel.startsWith('src/main/resources/')) return 'texte';
    if (rel === 'build.gradle' || rel.startsWith('basiskit/') || vonWurzel.startsWith('basis/src/main/')) return 'ganz';
    return null;
  }

  /** Geänderte Dateien übernehmen – nacheinander, nie zwei Übernahmen gleichzeitig. */
  uebernehmen(dateien) {
    this.kette = this.kette.then(() => this.uebernehmenJetzt(dateien)).catch((e) => {
      this.melde(`✘ ${e.message.split('\n')[0]}`);
      throw e;
    });
    return this.kette;
  }

  async uebernehmenJetzt(dateien) {
    const arten = new Set(dateien.map((d) => this.art(d)).filter(Boolean));
    if (!arten.size) return null;
    const t0 = Date.now();
    if (arten.has('ganz')) {
      this.melde(`Live ${this.addon}: neu bauen …`);
      const war = await w.bauen(this.wurzel, this.addon, this.optionen);
      await this.herausnehmen();
      this.auspacken(war);
      await this.neuStarten();
    } else {
      let neustart = false;
      if (arten.has('java') || arten.has('texte')) {
        this.melde(`Live ${this.addon}: übersetzen …`);
        await w.gradle(this.wurzel, [`${w.gradlePfad(this.wurzel, this.addon)}:classes`], this.optionen);
        const klassen = path.join(this.ziel, 'WEB-INF', 'classes');
        for (const quelle of ['build/classes/java/main', 'build/resources/main']) {
          const q = path.join(w.addonOrdner(this.wurzel, this.addon), quelle);
          if (fs.existsSync(q)) fs.cpSync(q, klassen, { recursive: true, force: true });
        }
        neustart = true;
      }
      if (arten.has('seite')) {
        for (const d of dateien.filter((x) => this.art(x) === 'seite')) {
          const rel = path.relative(path.join(w.addonOrdner(this.wurzel, this.addon), 'src/main/webapp'), d);
          const nach = path.join(this.ziel, rel);
          if (fs.existsSync(d)) {
            fs.mkdirSync(path.dirname(nach), { recursive: true });
            fs.copyFileSync(d, nach);
          } else {
            fs.rmSync(nach, { force: true });
          }
        }
      }
      if (neustart) await this.neuStarten();
    }
    this.stand();
    const art = arten.has('ganz') ? 'neu gebaut' : arten.has('java') || arten.has('texte') ? 'übersetzt und neu gestartet' : 'Seite übernommen';
    const ms = Date.now() - t0;
    this.melde(`✔ Live ${this.addon}: ${art} (${ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`})`);
    return { art, ms };
  }

  /** Beenden: wieder als normales Paket einsetzen, Neuladen im Browser abschalten. */
  async beenden() {
    this.melde(`Live-Modus ${this.addon}: einpacken …`);
    await this.kette.catch(() => {});
    const war = await w.bauen(this.wurzel, this.addon, this.optionen);
    await this.herausnehmen();
    fs.rmSync(this.ziel, { recursive: true, force: true });
    fs.rmSync(this.standDatei, { force: true });
    fs.copyFileSync(war, path.join(this.deployments, `.${this.addon}.war.neu`));
    fs.renameSync(path.join(this.deployments, `.${this.addon}.war.neu`), this.ziel);
    await this.neuStarten();
    this.melde(`Live-Modus ${this.addon} beendet – läuft wieder als Paket`);
  }
}

module.exports = { Live };

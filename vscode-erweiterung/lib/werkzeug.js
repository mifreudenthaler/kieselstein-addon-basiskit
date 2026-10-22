'use strict';
/*
 * Die Handgriffe der Kieselstein-Addon-Erweiterung – ohne VS Code.
 *
 * Alles hier ist reines Node, damit es sich ohne Editor prüfen lässt. Die
 * Erweiterung (extension.js) ruft es nur auf und zeigt das Ergebnis.
 *
 * Server, Stufen und Laden in die erste Stufe stehen in server.js.
 */
const fs = require('node:fs');
const path = require('node:path');
const { spawn, execFileSync } = require('node:child_process');

const NAME_MUSTER = /^[a-z][a-z0-9-]{1,40}$/;

/**
 * Ein Addon-Projekt ist ein Addon mit eigenem Repository: im Ordner selbst liegen
 * settings.gradle, build.gradle und src/main/webapp/WEB-INF/jboss-web.xml.
 * Das Basiskit-Repository selbst ist eine Sammlung (settings.gradle mit basis/
 * und Addons als Unterprojekten) – dort wird die Basisschicht entwickelt.
 */
function istAddonProjekt(d) {
  return fs.existsSync(path.join(d, 'settings.gradle')) && fs.existsSync(path.join(d, 'src/main/webapp/WEB-INF/jboss-web.xml'));
}

function istSammlung(d) {
  return fs.existsSync(path.join(d, 'settings.gradle')) && fs.existsSync(path.join(d, 'basis'));
}

function projektArt(wurzel) {
  return istAddonProjekt(wurzel) ? 'addon' : 'sammlung';
}

function projektName(wurzel) {
  const s = fs.existsSync(path.join(wurzel, 'settings.gradle')) ? fs.readFileSync(path.join(wurzel, 'settings.gradle'), 'utf8') : '';
  const m = s.match(/rootProject\.name\s*=\s*['"]([^'"]+)['"]/);
  return m ? m[1] : path.basename(wurzel);
}

function addonAus(ordner, name) {
  const props = leseProperties(path.join(ordner, 'src/main/resources/addon.properties'));
  return { name, titel: props.titel || name, ordner };
}

/** Alle Addons im geöffneten Projekt (bei einem Addon-Projekt genau eines). */
function addons(wurzel) {
  if (istAddonProjekt(wurzel)) return [addonAus(wurzel, projektName(wurzel))];
  return fs.readdirSync(wurzel, { withFileTypes: true })
    .filter((d) => d.isDirectory() && fs.existsSync(path.join(wurzel, d.name, 'src/main/webapp/WEB-INF/jboss-web.xml')))
    .map((d) => addonAus(path.join(wurzel, d.name), d.name));
}

/** Gradle-Pfad eines Addons: im Addon-Projekt die Wurzel (""), in der Sammlung „:name". */
function gradlePfad(wurzel, name) {
  return istAddonProjekt(wurzel) ? '' : `:${name}`;
}

/** Ordner eines Addons. */
function addonOrdner(wurzel, name) {
  return istAddonProjekt(wurzel) ? wurzel : path.join(wurzel, name);
}

function leseProperties(datei) {
  const werte = {};
  if (!fs.existsSync(datei)) return werte;
  for (const zeile of fs.readFileSync(datei, 'utf8').split(/\r?\n/)) {
    const m = zeile.match(/^\s*([^#!][^=:]*?)\s*[=:]\s*(.*)$/);
    if (m) werte[m[1]] = m[2];
  }
  return werte;
}

/** Zu welchem Addon gehört diese Datei? */
function addonZuDatei(wurzel, datei) {
  if (!datei) return null;
  const rel = path.relative(wurzel, datei);
  if (rel.startsWith('..')) return null;
  if (istAddonProjekt(wurzel)) return addons(wurzel)[0];
  const erster = rel.split(path.sep)[0];
  return addons(wurzel).find((a) => a.name === erster) || null;
}

/** Das geöffnete Projekt: ein Addon-Projekt oder das Basiskit selbst. */
function projektWurzel(start) {
  let d = start;
  for (let i = 0; i < 6 && d; i++) {
    if (istAddonProjekt(d) || istSammlung(d)) return d;
    const hoch = path.dirname(d);
    if (hoch === d) break;
    d = hoch;
  }
  return null;
}

/** Version der Basisschicht, die ein Addon-Projekt benutzt (aus build.gradle). */
function basiskitVersion(wurzel) {
  const b = path.join(wurzel, 'build.gradle');
  if (!fs.existsSync(b)) return null;
  const m = fs.readFileSync(b, 'utf8').match(/ext\.basiskitVersion\s*=\s*'([^']+)'/);
  return m ? m[1] : null;
}

/**
 * Ein neues Addon-Projekt aus der Vorlage anlegen – ein eigener Ordner, später
 * ein eigenes Repository. Gibt den Ordner zurück.
 */
function addonProjektAnlegen(vorlage, ziel, name, titel, beschreibung) {
  if (!NAME_MUSTER.test(name)) {
    throw new Error('Name: Kleinbuchstaben, Ziffern und Bindestrich, mit einem Buchstaben vorn (z. B. „lieferscheine")');
  }
  if (['basis', 'zentrale', 'basiskit'].includes(name)) throw new Error(`„${name}" ist im Basiskit schon vergeben`);
  if (fs.existsSync(ziel) && fs.readdirSync(ziel).filter((n) => !n.startsWith('.')).length) {
    throw new Error(`${ziel} ist nicht leer – bitte einen leeren oder neuen Ordner wählen.`);
  }
  const paket = name.replace(/-/g, '');
  const ersetzen = (s) => s.replace(/__NAME__/g, name).replace(/__PAKET__/g, paket)
    .replace(/__TITEL__/g, titel).replace(/__BESCHREIBUNG__/g, beschreibung || '');
  const kopieren = (von, nach) => {
    for (const e of fs.readdirSync(von, { withFileTypes: true })) {
      const quelle = path.join(von, e.name);
      const zielPfad = path.join(nach, ersetzen(e.name));
      if (e.isDirectory()) {
        fs.mkdirSync(zielPfad, { recursive: true });
        kopieren(quelle, zielPfad);
      } else if (/\.(jar|png|jpg|gif|ico)$/.test(e.name)) {
        fs.copyFileSync(quelle, zielPfad);
      } else {
        fs.writeFileSync(zielPfad, ersetzen(fs.readFileSync(quelle, 'utf8')));
        if (e.name === 'gradlew') fs.chmodSync(zielPfad, 0o755);
      }
    }
  };
  fs.mkdirSync(ziel, { recursive: true });
  kopieren(vorlage, ziel);
  return ziel;
}

/**
 * Basisschicht eines Addon-Projekts auf die Fassung der Erweiterung bringen:
 * Bibliothek tauschen, Version in build.gradle setzen.
 */
function basiskitAktualisieren(vorlage, wurzel) {
  const neu = fs.readdirSync(path.join(vorlage, 'basiskit')).find((n) => /^basis-[\d.]+\.jar$/.test(n));
  const version = neu.replace(/^basis-|\.jar$/g, '');
  const alt = basiskitVersion(wurzel);
  const ordner = path.join(wurzel, 'basiskit');
  fs.mkdirSync(ordner, { recursive: true });
  for (const n of fs.readdirSync(ordner)) if (/^basis-.*\.jar$/.test(n)) fs.rmSync(path.join(ordner, n));
  for (const n of fs.readdirSync(path.join(vorlage, 'basiskit'))) fs.copyFileSync(path.join(vorlage, 'basiskit', n), path.join(ordner, n));
  const b = path.join(wurzel, 'build.gradle');
  fs.writeFileSync(b, fs.readFileSync(b, 'utf8').replace(/ext\.basiskitVersion\s*=\s*'[^']+'/, `ext.basiskitVersion = '${version}'`));
  return { alt, neu: version };
}

/** JDK 21 finden: Einstellung, sonst das von Kieselstein. */
function jdk(vorgabe) {
  if (vorgabe && fs.existsSync(path.join(vorgabe, 'bin', 'java'))) return vorgabe;
  const basis = path.join(require('node:os').homedir(), '.kieselstein', 'jdk');
  if (fs.existsSync(basis)) {
    const treffer = fs.readdirSync(basis).find((d) => /jdk21/.test(d));
    if (treffer) return path.join(basis, treffer);
  }
  return process.env.JAVA_HOME || null;
}

/** Gradle laufen lassen; jede Ausgabezeile geht an melde(). */
function gradle(wurzel, aufgaben, { jdkPfad, clientLib, melde = () => {} } = {}) {
  return new Promise((fertig, scheitern) => {
    const args = ['-q', '--console=plain', ...aufgaben];
    if (clientLib) args.push(`-PksClientLib=${clientLib}`);
    const env = { ...process.env };
    const j = jdk(jdkPfad);
    if (j) env.JAVA_HOME = j;
    const p = spawn(path.join(wurzel, 'gradlew'), args, { cwd: wurzel, env });
    let ausgabe = '';
    const lies = (b) => {
      const t = b.toString();
      ausgabe += t;
      t.split(/\r?\n/).filter((z) => z && !z.startsWith('Hinweis')).forEach(melde);
    };
    p.stdout.on('data', lies);
    p.stderr.on('data', lies);
    p.on('error', scheitern);
    p.on('close', (code) => (code === 0 ? fertig(ausgabe) : scheitern(new Error(`Gradle endete mit ${code}\n${ausgabe.slice(-2000)}`))));
  });
}

function bauen(wurzel, addon, optionen) {
  return gradle(wurzel, [`${gradlePfad(wurzel, addon)}:war`], optionen)
    .then(() => path.join(addonOrdner(wurzel, addon), 'build', 'libs', `${addon}.war`));
}

function testen(wurzel, addon, optionen) {
  return gradle(wurzel, [`${gradlePfad(wurzel, addon)}:test`], optionen);
}

/** Die Fassaden der Kieselstein-Version: alle *Fac-Schnittstellen im ejb-jar. */
function fassaden(clientLib) {
  const jar = fs.readdirSync(clientLib).find((n) => /^kieselstein-ejb-.*\.jar$/.test(n));
  if (!jar) throw new Error(`In ${clientLib} liegt kein kieselstein-ejb-*.jar`);
  const liste = execFileSync('unzip', ['-Z1', path.join(clientLib, jar)], { maxBuffer: 64 * 1024 * 1024 }).toString();
  return liste.split('\n')
    .filter((n) => /\/service\/[A-Za-z]+Fac\.class$/.test(n))
    .map((n) => n.replace(/\.class$/, '').replace(/\//g, '.'))
    .sort((a, b) => a.split('.').pop().localeCompare(b.split('.').pop()));
}

/** Die Methoden einer Fassade, wie javap sie zeigt. */
function fassadeZeigen(clientLib, klasse, jdkPfad) {
  const jar = fs.readdirSync(clientLib).find((n) => /^kieselstein-ejb-.*\.jar$/.test(n));
  const j = jdk(jdkPfad);
  const javap = j ? path.join(j, 'bin', 'javap') : 'javap';
  return execFileSync(javap, ['-cp', path.join(clientLib, jar), klasse], { maxBuffer: 16 * 1024 * 1024 }).toString();
}

module.exports = {
  addons, addonZuDatei, projektWurzel, projektArt, projektName, gradlePfad, addonOrdner, basiskitVersion,
  addonProjektAnlegen, basiskitAktualisieren, bauen, testen, gradle, fassaden, fassadeZeigen, leseProperties,
};

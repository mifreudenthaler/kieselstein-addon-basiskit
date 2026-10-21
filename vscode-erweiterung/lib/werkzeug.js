'use strict';
/*
 * Die Handgriffe der Kieselstein-Addon-Erweiterung – ohne VS Code.
 *
 * Alles hier ist reines Node, damit es sich ohne Editor prüfen lässt. Die
 * Erweiterung (extension.js) ruft es nur auf und zeigt das Ergebnis.
 *
 * Bewusst nicht enthalten: ein Weg in die Produktion. Die Erweiterung reicht
 * bis zum Test-Addon-Server. In die Produktion kommt ein Addon nur über
 * „Für Produktion freigeben" in der Test-Zentrale, durch einen Menschen.
 */
const fs = require('node:fs');
const path = require('node:path');
const { spawn, execFileSync } = require('node:child_process');

const NAME_MUSTER = /^[a-z][a-z0-9-]{1,40}$/;

/** Alle Addons im Projekt: Unterordner mit WEB-INF/jboss-web.xml (die Zentrale zählt mit). */
function addons(wurzel) {
  return fs.readdirSync(wurzel, { withFileTypes: true })
    .filter((d) => d.isDirectory() && d.name !== 'vorlage'
      && fs.existsSync(path.join(wurzel, d.name, 'src/main/webapp/WEB-INF/jboss-web.xml')))
    .map((d) => {
      const props = leseProperties(path.join(wurzel, d.name, 'src/main/resources/addon.properties'));
      return { name: d.name, titel: props.titel || d.name, ordner: path.join(wurzel, d.name) };
    });
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
  const erster = rel.split(path.sep)[0];
  return addons(wurzel).find((a) => a.name === erster) || null;
}

/** Wo der Basiskit-Projektordner liegt: der Ordner mit settings.gradle und basis/. */
function projektWurzel(start) {
  let d = start;
  for (let i = 0; i < 6 && d; i++) {
    if (fs.existsSync(path.join(d, 'settings.gradle')) && fs.existsSync(path.join(d, 'basis'))) return d;
    const hoch = path.dirname(d);
    if (hoch === d) break;
    d = hoch;
  }
  return null;
}

/**
 * Ein neues Addon aus der Vorlage anlegen und in settings.gradle eintragen.
 * Gibt den Ordner zurück.
 */
function addonAnlegen(wurzel, name, titel, beschreibung) {
  if (!NAME_MUSTER.test(name)) {
    throw new Error('Name: Kleinbuchstaben, Ziffern und Bindestrich, mit einem Buchstaben vorn (z. B. „lieferscheine")');
  }
  if (['basis', 'zentrale', 'vorlage', 'addon-server', 'vscode-erweiterung'].includes(name)) {
    throw new Error(`„${name}" ist im Basiskit schon vergeben`);
  }
  const ziel = path.join(wurzel, name);
  if (fs.existsSync(ziel)) throw new Error(`Den Ordner ${name}/ gibt es schon`);
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
      } else {
        fs.writeFileSync(zielPfad, ersetzen(fs.readFileSync(quelle, 'utf8')));
      }
    }
  };
  fs.mkdirSync(ziel, { recursive: true });
  kopieren(path.join(wurzel, 'vorlage'), ziel);

  const settings = path.join(wurzel, 'settings.gradle');
  const text = fs.readFileSync(settings, 'utf8');
  const neu = text.replace(/include\s+([^\n]+)/, (zeile, liste) => `include ${liste.trim()}, '${name}'`);
  fs.writeFileSync(settings, neu === text ? `${text.trimEnd()}\ninclude '${name}'\n` : neu);
  return ziel;
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
  return gradle(wurzel, [`:${addon}:war`], optionen).then(() => path.join(wurzel, addon, 'build', 'libs', `${addon}.war`));
}

function testen(wurzel, addon, optionen) {
  return gradle(wurzel, [`:${addon}:test`], optionen);
}

/**
 * Ein WAR in den Test-Addon-Server legen und warten, bis WildFly es gestartet hat.
 * Weigert sich, wenn der Addon-Server nicht die Stufe „test" hat.
 */
async function inDenTestLaden(war, addonsOrdner, { sekunden = 120 } = {}) {
  const stufe = stufeLesen(addonsOrdner);
  if (stufe !== 'test') {
    throw new Error(`Der Addon-Server in ${addonsOrdner} hat die Stufe „${stufe}". Die Erweiterung lädt nur in den Test – in die Produktion kommt ein Addon über die Freigabe in der Test-Zentrale.`);
  }
  const deployments = path.join(addonsOrdner, 'deployments');
  const name = path.basename(war);
  const ziel = path.join(deployments, name);
  const marke = (endung) => `${ziel}${endung}`;
  for (const e of ['.deployed', '.failed', '.skipdeploy', '.undeployed']) fs.rmSync(marke(e), { force: true });
  const tmp = path.join(deployments, `.${name}.neu`);
  fs.copyFileSync(war, tmp);
  fs.renameSync(tmp, ziel);
  fs.writeFileSync(marke('.dodeploy'), '');
  const ende = Date.now() + sekunden * 1000;
  while (Date.now() < ende) {
    if (fs.existsSync(marke('.failed'))) {
      const grund = fs.readFileSync(marke('.failed'), 'utf8').slice(0, 1500);
      throw new Error(`WildFly konnte ${name} nicht starten:\n${grund}`);
    }
    if (fs.existsSync(marke('.deployed')) && !fs.existsSync(marke('.dodeploy')) && !fs.existsSync(marke('.isdeploying'))) {
      return { name, sekunden: Math.round((sekunden * 1000 - (ende - Date.now())) / 100) / 10 };
    }
    await new Promise((r) => setTimeout(r, 400));
  }
  throw new Error(`${name} ist nach ${sekunden} s noch nicht gestartet`);
}

function stufeLesen(addonsOrdner) {
  const conf = path.join(addonsOrdner, 'config', 'addon-server.conf');
  if (!fs.existsSync(conf)) return 'unbekannt';
  const m = fs.readFileSync(conf, 'utf8').match(/^STUFE=(\S+)/m);
  return m ? m[1].toLowerCase() : 'test';
}

/** Was im Addon-Server liegt und läuft. */
function zustand(addonsOrdner) {
  const deployments = path.join(addonsOrdner, 'deployments');
  if (!fs.existsSync(deployments)) return { stufe: 'unbekannt', addons: [] };
  const liste = fs.readdirSync(deployments).filter((n) => n.endsWith('.war')).map((n) => {
    const m = (e) => fs.existsSync(path.join(deployments, n + e));
    return {
      name: n.replace(/\.war$/, ''),
      zustand: m('.failed') ? 'Fehler' : m('.deployed') ? 'läuft' : m('.skipdeploy') || m('.undeployed') ? 'abgeschaltet' : 'startet',
    };
  });
  return { stufe: stufeLesen(addonsOrdner), addons: liste };
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
  addons, addonZuDatei, projektWurzel, addonAnlegen, bauen, testen, inDenTestLaden,
  stufeLesen, zustand, fassaden, fassadeZeigen, leseProperties,
};

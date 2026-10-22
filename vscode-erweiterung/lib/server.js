'use strict';
/*
 * Die Server einer Kette – Entwicklung (zusätzlich), Test, Produktion – und
 * was die Erweiterung auf ihnen tut: Stand abfragen, Basiskit einrichten oder
 * aktualisieren, Addon-Server starten und anhalten, ein Addon in die erste
 * Stufe laden.
 *
 * Ein Server ist { stufe, ssh, kieselstein, adresse, portVersatz }. „ssh" leer
 * heißt: diese Maschine. Alles läuft über die üblichen Werkzeuge ssh und scp –
 * dieselben Zugänge wie für VS Code Remote-SSH.
 *
 * Grenze: In die Produktion bringt die Erweiterung nur das Basiskit
 * (Addon-Server und Zentrale). Addons kommen dort nur über „Ausrollen" in der
 * Zentrale der vorigen Stufe an.
 */
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');

const STUFEN = ['entwicklung', 'test', 'produktion'];
const STUFEN_NAME = { entwicklung: 'Entwicklung', test: 'Test', produktion: 'Produktion' };

/** Die Kette in Reihenfolge; je Stufe höchstens ein Server. */
function kette(server) {
  return STUFEN.map((s) => server.find((x) => x.stufe === s)).filter(Boolean);
}

/** In diese Stufe lädt VS Code: Entwicklung, wenn es sie gibt, sonst Test. */
function ersteStufe(server) {
  return kette(server).find((s) => s.stufe !== 'produktion') || null;
}

function naechste(server, s) {
  const k = kette(server);
  const i = k.indexOf(s);
  return i >= 0 && i + 1 < k.length ? k[i + 1] : null;
}

function vorige(server, s) {
  const k = kette(server);
  const i = k.indexOf(s);
  return i > 0 ? k[i - 1] : null;
}

/**
 * Die vorige Stufe mit dieser verbinden: Adresse der nächsten Stufe und
 * gemeinsamen Schlüssel eintragen, Addon-Server neu starten – nur wenn sich
 * etwas ändert. So kennt die Entwicklung den nachträglich eingerichteten Test.
 */
async function verbinden(vor, nach, schluessel, { melde = () => {} } = {}) {
  const st = await stand(vor);
  if (!st.installiert) return false;
  const a = addonsOrdner(vor);
  const r = await ausfuehren(vor, `cat ${q(a + '/config/freigabe.schluessel')} 2>/dev/null`);
  if (st.naechste === nach.adresse && r.aus.trim() === schluessel) return false;
  melde(`${STUFEN_NAME[vor.stufe]}: mit ${STUFEN_NAME[nach.stufe]} verbinden (${nach.adresse}) …`);
  const conf = `${a}/config/addon-server.conf`;
  await pflicht(vor, `if grep -q '^NAECHSTE_STUFE_URL=' ${q(conf)}; then sed -i ${q('s#^NAECHSTE_STUFE_URL=.*#NAECHSTE_STUFE_URL=' + nach.adresse + '#')} ${q(conf)}; `
    + `else echo ${q('NAECHSTE_STUFE_URL=' + nach.adresse)} >> ${q(conf)}; fi; `
    + `(umask 077; printf %s ${q(schluessel)} > ${q(a + '/config/freigabe.schluessel')})`);
  await pflicht(vor, `bash ${q(a + '/stopp.sh')} >/dev/null 2>&1; bash ${q(a + '/start.sh')} --hintergrund`);
  await warten(vor, (x) => x.laeuft && x.addons.some((y) => y.name === 'zentrale' && y.zustand.startsWith('läuft')), 180);
  melde(`${STUFEN_NAME[vor.stufe]}: rollt jetzt nach ${STUFEN_NAME[nach.stufe]} aus`);
  return true;
}

function q(s) {
  return `'${String(s).replace(/'/g, `'\\''`)}'`;
}

/** Einen Befehl auf dem Server ausführen (bash). Liefert { code, aus }. */
function ausfuehren(server, befehl, { melde } = {}) {
  return new Promise((fertig, scheitern) => {
    const p = server.ssh
      ? spawn('ssh', ['-o', 'BatchMode=yes', '-o', 'ConnectTimeout=10', server.ssh, `bash -lc ${q(befehl)}`])
      : spawn('bash', ['-lc', befehl]);
    let aus = '';
    const lies = (b) => {
      const t = b.toString();
      aus += t;
      if (melde) t.split(/\r?\n/).filter(Boolean).forEach(melde);
    };
    p.stdout.on('data', lies);
    p.stderr.on('data', lies);
    p.on('error', scheitern);
    p.on('close', (code) => fertig({ code, aus }));
  });
}

async function pflicht(server, befehl, optionen) {
  const r = await ausfuehren(server, befehl, optionen);
  if (r.code !== 0) throw new Error(`${STUFEN_NAME[server.stufe] || server.stufe}: ${r.aus.trim().split('\n').slice(-3).join(' ') || 'Befehl gescheitert'}`);
  return r.aus;
}

/** Eine Datei auf den Server bringen. */
function hochladen(server, lokal, entfernt) {
  if (!server.ssh) {
    fs.mkdirSync(path.dirname(entfernt.replace(/^~/, os.homedir())), { recursive: true });
    fs.copyFileSync(lokal, entfernt.replace(/^~/, os.homedir()));
    return Promise.resolve();
  }
  return new Promise((fertig, scheitern) => {
    const p = spawn('scp', ['-q', '-o', 'BatchMode=yes', lokal, `${server.ssh}:${entfernt}`]);
    let fehler = '';
    p.stderr.on('data', (b) => { fehler += b; });
    p.on('error', scheitern);
    p.on('close', (code) => (code === 0 ? fertig() : scheitern(new Error(`Hochladen gescheitert: ${fehler.trim()}`))));
  });
}

function addonsOrdner(server) {
  return `${server.kieselstein.replace(/\/$/, '')}/addons`;
}

/**
 * Stand eines Servers: erreichbar? Kieselstein da? Basiskit installiert?
 * { erreichbar, kieselstein, installiert, basiskit, laeuft, stufe, addons: [{name, zustand}], fehler }
 */
async function stand(server) {
  const ks = server.kieselstein.replace(/\/$/, '');
  const befehl = `if [ ! -f ${q(ks + '/dist/VERSION.txt')} ]; then echo kieselstein=fehlt; exit 0; fi; `
    + `echo kieselsteinversion=$(cat ${q(ks + '/dist/VERSION.txt')}); `
    + `if [ -x ${q(ks + '/addons/status.sh')} ]; then bash ${q(ks + '/addons/status.sh')}; else echo basiskit=; fi`;
  let r;
  try {
    r = await ausfuehren(server, befehl);
  } catch (e) {
    return { erreichbar: false, fehler: e.message, addons: [] };
  }
  if (r.code !== 0) return { erreichbar: false, fehler: r.aus.trim().split('\n').pop(), addons: [] };
  const werte = {};
  const addons = [];
  for (const zeile of r.aus.split('\n')) {
    const i = zeile.indexOf('=');
    if (i < 0) continue;
    const k = zeile.slice(0, i).trim();
    const v = zeile.slice(i + 1).trim();
    if (k === 'addon') {
      const [name, zustand] = v.split(':');
      addons.push({ name, zustand: zustand === 'laeuft' ? 'läuft' : zustand === 'live' ? 'läuft (live)' : zustand });
    } else werte[k] = v;
  }
  return {
    erreichbar: true,
    kieselstein: werte.kieselstein !== 'fehlt',
    kieselsteinVersion: werte.kieselsteinversion,
    installiert: Boolean(werte.basiskit),
    basiskit: werte.basiskit || null,
    laeuft: werte.laeuft === 'ja',
    stufe: werte.stufe || null,
    naechste: werte.naechste || '',
    addons,
  };
}

/** Das Installationspaket: in der Erweiterung mitgeliefert, sonst aus dem Bau im Projekt. */
function paket(erweiterungsOrdner, projektWurzel) {
  const orte = [path.join(erweiterungsOrdner, 'paket')];
  if (projektWurzel) orte.push(path.join(projektWurzel, 'build', 'paket'));
  for (const o of orte) {
    if (!fs.existsSync(o)) continue;
    const t = fs.readdirSync(o).filter((n) => /^kieselstein-basiskit-.*\.tar\.gz$/.test(n)).sort().pop();
    if (t) return { datei: path.join(o, t), version: t.replace(/^kieselstein-basiskit-|\.tar\.gz$/g, '') };
  }
  return null;
}

/**
 * Basiskit auf einem Server einrichten oder aktualisieren und den Addon-Server
 * (neu) starten. `schluessel` ist der gemeinsame Freigabe-Schlüssel der Kette.
 */
async function einrichten(server, alle, { paketDatei, schluessel, melde = () => {} }) {
  const ks = server.kieselstein.replace(/\/$/, '');
  const ziel = naechste(alle, server);
  const tmp = `/tmp/kieselstein-basiskit-${crypto.randomBytes(4).toString('hex')}`;
  melde(`${STUFEN_NAME[server.stufe]}: Kieselstein unter ${ks} prüfen …`);
  await pflicht(server, `test -f ${q(ks + '/dist/VERSION.txt')} || { echo "Kein Kieselstein unter ${ks}"; exit 1; }; mkdir -p ${q(tmp)}`);

  // WildFly-Archiv: liegt es dort nicht und hat die Maschine hier eins, wird es mitgebracht –
  // Kundenserver haben oft kein Internet.
  const wildfly = path.join(os.homedir(), '.cache', 'kieselstein-addon', 'wildfly-26.1.2.Final.tar.gz');
  const hatWildfly = (await ausfuehren(server, `test -d ${q(ks + '/addons/server')} || test -f ~/.cache/kieselstein-addon/wildfly-26.1.2.Final.tar.gz`)).code === 0;
  if (!hatWildfly && fs.existsSync(wildfly)) {
    melde(`${STUFEN_NAME[server.stufe]}: WildFly übertragen (einmalig, ca. 220 MB) …`);
    await pflicht(server, 'mkdir -p ~/.cache/kieselstein-addon');
    await hochladen(server, wildfly, '~/.cache/kieselstein-addon/wildfly-26.1.2.Final.tar.gz');
  }

  melde(`${STUFEN_NAME[server.stufe]}: Paket übertragen …`);
  await hochladen(server, paketDatei, `${tmp}/paket.tar.gz`);
  const schluesselDatei = path.join(os.tmpdir(), `ks-schluessel-${crypto.randomBytes(4).toString('hex')}`);
  fs.writeFileSync(schluesselDatei, schluessel, { mode: 0o600 });
  try {
    await hochladen(server, schluesselDatei, `${tmp}/schluessel`);
  } finally {
    fs.rmSync(schluesselDatei, { force: true });
  }

  const args = [
    q(ks),
    '--stufe', server.stufe,
    '--port-versatz', String(server.portVersatz || 200),
    '--schluessel-datei', q(`${tmp}/schluessel`),
  ];
  if (ziel) args.push('--naechste-stufe', q(ziel.adresse));
  if (server.kieselsteinServer) args.push('--kieselstein-server', q(server.kieselsteinServer));
  melde(`${STUFEN_NAME[server.stufe]}: einrichten …`);
  const aus = await pflicht(server,
    `cd ${q(tmp)} && tar -xzf paket.tar.gz && bash kieselstein-basiskit-*/einrichten.sh ${args.join(' ')}; r=$?; rm -rf ${q(tmp)}; exit $r`,
    { melde: (z) => melde(`  ${z}`) });
  if (!/^OK /m.test(aus)) throw new Error(`${STUFEN_NAME[server.stufe]}: Einrichtung ohne Abschlussmeldung`);

  melde(`${STUFEN_NAME[server.stufe]}: Addon-Server starten …`);
  const a = addonsOrdner(server);
  await pflicht(server, `bash ${q(a + '/stopp.sh')} >/dev/null 2>&1; bash ${q(a + '/start.sh')} --hintergrund`);
  await warten(server, (st) => st.laeuft && st.addons.some((x) => x.name === 'zentrale' && (x.zustand.startsWith('läuft') || x.zustand === 'fehler')), 180);
  const st = await stand(server);
  const z = st.addons.find((x) => x.name === 'zentrale');
  if (!z || z.zustand !== 'läuft') throw new Error(`${STUFEN_NAME[server.stufe]}: Zentrale startet nicht – Protokoll unter ${a}/logs`);
  melde(`${STUFEN_NAME[server.stufe]}: Zentrale ${st.basiskit} läuft – ${server.adresse}`);
  return st;
}

async function warten(server, bedingung, sekunden) {
  const ende = Date.now() + sekunden * 1000;
  while (Date.now() < ende) {
    const st = await stand(server);
    if (bedingung(st)) return st;
    await new Promise((r) => setTimeout(r, 2000));
  }
  return stand(server);
}

async function starten(server) {
  await pflicht(server, `bash ${q(addonsOrdner(server) + '/start.sh')} --hintergrund`);
  return warten(server, (st) => st.laeuft && st.addons.some((x) => x.name === 'zentrale' && x.zustand === 'läuft'), 180);
}

async function anhalten(server) {
  await pflicht(server, `bash ${q(addonsOrdner(server) + '/stopp.sh')}`);
  return stand(server);
}

/**
 * Ein Addon (WAR) in die erste Stufe laden und auf den Start warten.
 * Weigert sich bei jeder anderen Stufe.
 */
async function inErsteStufeLaden(war, alle, { sekunden = 120, melde = () => {} } = {}) {
  const ziel = ersteStufe(alle);
  if (!ziel) throw new Error('Kein Test- oder Entwicklungsserver eingetragen.');
  const st = await stand(ziel);
  if (!st.installiert) throw new Error(`Auf ${STUFEN_NAME[ziel.stufe]} ist das Basiskit noch nicht eingerichtet.`);
  if (st.stufe !== ziel.stufe) {
    throw new Error(`Der Addon-Server unter ${ziel.kieselstein} meldet die Stufe „${st.stufe}", eingetragen ist „${ziel.stufe}" – bitte prüfen.`);
  }
  const name = path.basename(war);
  const d = `${addonsOrdner(ziel)}/deployments`;
  if ((await ausfuehren(ziel, `test -d ${q(d + '/' + name)}`)).code === 0) {
    throw new Error(`${name.replace(/\.war$/, '')} läuft im Live-Modus – Änderungen werden ohnehin übernommen. Zum normalen Laden den Live-Modus beenden.`);
  }
  melde(`${name} → ${STUFEN_NAME[ziel.stufe]}`);
  await hochladen(ziel, war, `${d}/.${name}.neu`);
  await pflicht(ziel, `cd ${q(d)} && rm -f ${q(name)}.deployed ${q(name)}.failed ${q(name)}.skipdeploy ${q(name)}.undeployed `
    + `&& mv ${q('.' + name + '.neu')} ${q(name)} && touch ${q(name + '.dodeploy')}`);
  const beginn = Date.now();
  const ende = beginn + sekunden * 1000;
  while (Date.now() < ende) {
    const r = await ausfuehren(ziel, `cd ${q(d)}; if [ -e ${q(name + '.failed')} ]; then echo FEHLER; head -c 1500 ${q(name + '.failed')}; `
      + `elif [ -e ${q(name + '.deployed')} ] && [ ! -e ${q(name + '.dodeploy')} ] && [ ! -e ${q(name + '.isdeploying')} ]; then echo OK; else echo WARTEN; fi`);
    if (r.aus.startsWith('OK')) return { name, stufe: ziel.stufe, sekunden: Math.round((Date.now() - beginn) / 100) / 10, adresse: ziel.adresse };
    if (r.aus.startsWith('FEHLER')) throw new Error(`WildFly konnte ${name} nicht starten:\n${r.aus.slice(7)}`);
    await new Promise((x) => setTimeout(x, 700));
  }
  throw new Error(`${name} ist nach ${sekunden} s noch nicht gestartet`);
}

/**
 * Die Kieselstein-Client-Bibliotheken der Version auf diesem Server in einen
 * lokalen Ordner holen – aus dist/clients der Installation. Gegen sie wird gebaut.
 */
async function clientBibliotheken(server, zielOrdner) {
  const ks = server.kieselstein.replace(/\/$/, '');
  const r = await ausfuehren(server, `ls ${q(ks)}/dist/clients/kieselstein-client-*.tar.gz 2>/dev/null | head -1`);
  const archiv = r.aus.trim();
  if (!archiv) throw new Error(`Unter ${ks}/dist/clients liegt kein Kieselstein-Client.`);
  fs.mkdirSync(zielOrdner, { recursive: true });
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'ks-client-'));
  try {
    await new Promise((fertig, scheitern) => {
      const quelle = server.ssh
        ? spawn('ssh', ['-o', 'BatchMode=yes', server.ssh, `cat ${q(archiv)}`])
        : spawn('cat', [archiv]);
      const tar = spawn('tar', ['-xzf', '-', '-C', tmp, '--wildcards', '*/lib/*.jar']);
      quelle.stdout.pipe(tar.stdin);
      let fehler = '';
      tar.stderr.on('data', (b) => { fehler += b; });
      quelle.on('error', scheitern);
      tar.on('error', scheitern);
      tar.on('close', (code) => (code === 0 ? fertig() : scheitern(new Error(`Client-Bibliotheken nicht lesbar: ${fehler.trim()}`))));
    });
    const lib = fs.readdirSync(tmp).map((d) => path.join(tmp, d, 'lib')).find((d) => fs.existsSync(d));
    if (!lib) throw new Error('Im Kieselstein-Client fehlt lib/.');
    fs.rmSync(zielOrdner, { recursive: true, force: true });
    fs.cpSync(lib, zielOrdner, { recursive: true });
    return { ordner: zielOrdner, anzahl: fs.readdirSync(zielOrdner).length, version: path.basename(archiv).replace(/^kieselstein-client-|\.tar\.gz$/g, '') };
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
}

function neuerSchluessel() {
  return crypto.randomBytes(32).toString('hex');
}

module.exports = {
  STUFEN, STUFEN_NAME, kette, ersteStufe, naechste, vorige, verbinden, ausfuehren, stand, paket, einrichten,
  starten, anhalten, inErsteStufeLaden, neuerSchluessel, clientBibliotheken,
};

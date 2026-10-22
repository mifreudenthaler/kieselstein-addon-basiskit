// Läuft in VS Code: Live-Modus von Anfang bis Ende an einem Probe-Addon.
const vscode = require('vscode');
const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

const pause = (ms) => new Promise((r) => setTimeout(r, ms));
const holen = async (url) => { try { const r = await fetch(url, { cache: 'no-store' }); return { status: r.status, text: await r.text() }; } catch (e) { return { status: 0, text: '' }; } };
const pruefen = async (name, f) => {
  const t0 = Date.now();
  try { await f(); console.log(`  ✔ ${name} (${Date.now() - t0} ms)`); } catch (e) { console.log(`  ✘ ${name}: ${e.message}`); throw e; }
};
/** Wartet, bis die Seite den Text zeigt; gibt die Zeit in ms zurück. */
async function bisSichtbar(url, text, sekunden) {
  const t0 = Date.now();
  while (Date.now() - t0 < sekunden * 1000) {
    const r = await holen(url);
    if (r.status === 200 && r.text.includes(text)) return Date.now() - t0;
    await pause(150);
  }
  throw new Error(`„${text}" nach ${sekunden} s nicht sichtbar`);
}

exports.run = async () => {
  const api = await vscode.extensions.getExtension('freudenthaler.kieselstein-addon').activate();
  const wurzel = vscode.workspace.workspaceFolders[0].uri.fsPath;
  const srv = api.serverListe()[0];
  const url = `${srv.adresse}/livetest/`;
  const ordner = wurzel; // der Arbeitsbereich ist das Addon-Projekt selbst

  await pruefen('Probe-Addon vorbereiten', async () => {
    assert.strictEqual(api.werkzeug.projektArt(wurzel), 'addon');
    fs.writeFileSync(path.join(ordner, 'src/main/java/at/freudenthaler/kieselstein/addon/livetest/Probe.java'),
      'package at.freudenthaler.kieselstein.addon.livetest;\n@javax.inject.Named @javax.enterprise.context.RequestScoped\npublic class Probe { public String getText() { return "JAVA-1"; } }\n');
    const seite = path.join(ordner, 'src/main/webapp/index.xhtml');
    // in den Seitentitel – der erscheint auch ohne Anmeldung
    fs.writeFileSync(seite, fs.readFileSync(seite, 'utf8').replace(/<ui:param name="titel" value="[^"]*"\/>/, '<ui:param name="titel" value="SEITE-A #{probe.text}"/>'));
  });

  await pruefen('Live-Modus starten', async () => {
    const l = await api.liveStarten({ name: 'livetest', titel: 'Livetest' });
    assert.ok(l, 'Live-Modus nicht gestartet');
    const ziel = path.join(srv.kieselstein, 'addons/deployments/livetest.war');
    assert.ok(fs.statSync(ziel).isDirectory(), 'nicht ausgepackt');
    await bisSichtbar(url, 'SEITE-A JAVA-1', 10);
    const r = await holen(`${url}basiskit/live`);
    assert.strictEqual(r.status, 200, 'Live-Stand nicht erreichbar');
    const seite = await holen(url);
    assert.ok(seite.text.includes('basiskit/live') && seite.text.includes('>Live<'), 'Seite ohne Live-Zeichen und Neuladen');
  });

  let standVorher = (await holen(`${url}basiskit/live`)).text;
  await pruefen('Seite ändern → sofort sichtbar', async () => {
    const seite = path.join(ordner, 'src/main/webapp/index.xhtml');
    fs.writeFileSync(seite, fs.readFileSync(seite, 'utf8').replace('SEITE-A', 'SEITE-B'));
    const ms = await bisSichtbar(url, 'SEITE-B JAVA-1', 5);
    console.log(`    sichtbar nach ${ms} ms`);
    await pause(300);
    const stand = (await holen(`${url}basiskit/live`)).text;
    assert.notStrictEqual(stand, standVorher, 'Stand nicht erneuert – Browser würde nicht neu laden');
    standVorher = stand;
  });

  await pruefen('Java ändern → übersetzt und neu gestartet', async () => {
    const klasse = path.join(ordner, 'src/main/java/at/freudenthaler/kieselstein/addon/livetest/Probe.java');
    fs.writeFileSync(klasse, fs.readFileSync(klasse, 'utf8').replace('JAVA-1', 'JAVA-2'));
    const ms = await bisSichtbar(url, 'SEITE-B JAVA-2', 30);
    console.log(`    sichtbar nach ${ms} ms`);
    const stand = (await holen(`${url}basiskit/live`)).text;
    assert.notStrictEqual(stand, standVorher, 'Stand nicht erneuert');
  });

  await pruefen('Zentrale zeigt das Addon im Live-Modus, Ausrollen gesperrt', async () => {
    const st = await api.server.stand(srv);
    const a = st.addons.find((x) => x.name === 'livetest');
    assert.strictEqual(a.zustand, 'läuft (live)');
  });

  await pruefen('Normales Laden während Live-Modus wird abgewiesen', async () => {
    await assert.rejects(api.server.inErsteStufeLaden(path.join(ordner, 'build/libs/livetest.war'), api.serverListe()), /Live-Modus/);
  });

  await pruefen('Live-Modus beenden → wieder ein Paket, kein Neuladen mehr', async () => {
    await api.liveBeenden();
    const ziel = path.join(srv.kieselstein, 'addons/deployments/livetest.war');
    assert.ok(fs.statSync(ziel).isFile(), 'kein Paket');
    await bisSichtbar(url, 'SEITE-B JAVA-2', 10);
    assert.strictEqual((await holen(`${url}basiskit/live`)).status, 404);
    assert.ok(!(await holen(url)).text.includes('basiskit/live'), 'Seite fragt noch nach dem Live-Stand');
  });
};

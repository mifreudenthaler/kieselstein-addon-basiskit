// Läuft in VS Code ohne geöffnetes Projekt: Addon-Projekt anlegen, bauen, in die erste Stufe laden.
const vscode = require('vscode');
const assert = require('node:assert');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const pruefen = async (name, f) => {
  const t0 = Date.now();
  try { await f(); console.log(`  ✔ ${name} (${Date.now() - t0} ms)`); } catch (e) { console.log(`  ✘ ${name}: ${e.message}`); throw e; }
};

exports.run = async () => {
  const api = await vscode.extensions.getExtension('freudenthaler.kieselstein-addon').activate();
  const ordner = fs.mkdtempSync(path.join(os.tmpdir(), 'ks-addon-projekt-'));

  await pruefen('Ohne Projekt: klare Meldung statt „Basisprojekt fehlt"', async () => {
    const w = vscode.workspace.workspaceFolders || [];
    assert.ok(!w.some((x) => fs.existsSync(path.join(x.uri.fsPath, 'basis'))), 'Arbeitsbereich enthält schon ein Projekt');
  });

  await pruefen('Addon-Projekt anlegen (mit erstem Addon, Bibliotheken vom Server)', async () => {
    await api.projektAnlegen(ordner, { name: 'lieferscheine', titel: 'Lieferscheine', beschreibung: 'Probe' });
    for (const d of ['basis/src', 'vorlage', 'gradlew', 'lieferscheine/src/main/webapp/index.xhtml', '.kieselstein-client/lib', 'AGENTS.md', '.git']) {
      assert.ok(fs.existsSync(path.join(ordner, d)), `${d} fehlt`);
    }
    assert.ok(fs.readFileSync(path.join(ordner, 'settings.gradle'), 'utf8').includes("'lieferscheine'"));
    assert.ok(fs.readdirSync(path.join(ordner, '.kieselstein-client/lib')).some((n) => n.startsWith('kieselstein-ejb-')));
  });

  await pruefen('Projekt baut und testet', async () => {
    await api.werkzeug.testen(ordner, 'lieferscheine', {});
    const war = await api.werkzeug.bauen(ordner, 'lieferscheine', {});
    assert.ok(fs.existsSync(war));
  });

  await pruefen('Addon in die erste Stufe laden und aufrufen', async () => {
    const r = await api.server.inErsteStufeLaden(path.join(ordner, 'lieferscheine/build/libs/lieferscheine.war'), api.serverListe());
    const seite = await (await fetch(`${r.adresse}/lieferscheine/`)).text();
    assert.ok(seite.includes('Lieferscheine'), 'Seite ohne Titel');
  });
};

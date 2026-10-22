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

  const version = vscode.extensions.getExtension('freudenthaler.kieselstein-addon').packageJSON.version;

  await pruefen('Addon-Projekt anlegen: ein Addon, ein Ordner, ein eigenes Repository', async () => {
    await api.projektAnlegen(ordner, { name: 'lieferscheine', titel: 'Lieferscheine', beschreibung: 'Probe' });
    for (const d of ['settings.gradle', 'build.gradle', 'gradlew', `basiskit/basis-${version}.jar`, `basiskit/basis-${version}-sources.jar`,
      'src/main/webapp/index.xhtml', 'src/main/webapp/WEB-INF/jboss-web.xml', '.kieselstein-client/lib', 'AGENTS.md', 'README.md', '.gitignore', '.git']) {
      assert.ok(fs.existsSync(path.join(ordner, d)), `${d} fehlt`);
    }
    assert.ok(!fs.existsSync(path.join(ordner, 'basis')), 'Basisschicht als Quellcode im Addon');
    assert.ok(fs.readFileSync(path.join(ordner, 'settings.gradle'), 'utf8').includes("rootProject.name = 'lieferscheine'"));
    const log = require('node:child_process').execFileSync('git', ['log', '--oneline'], { cwd: ordner }).toString().trim().split('\n');
    assert.strictEqual(log.length, 1, 'kein erster Commit');
    const imRepo = require('node:child_process').execFileSync('git', ['ls-files'], { cwd: ordner }).toString();
    assert.ok(imRepo.includes(`basiskit/basis-${version}.jar`) && !imRepo.includes('.kieselstein-client'), 'Repository-Inhalt falsch');
    assert.strictEqual(api.werkzeug.projektArt(ordner), 'addon');
  });

  await pruefen('Projekt baut und testet', async () => {
    await api.werkzeug.testen(ordner, 'lieferscheine', {});
    const war = await api.werkzeug.bauen(ordner, 'lieferscheine', {});
    assert.ok(war.endsWith('build/libs/lieferscheine.war') && fs.existsSync(war), war);
  });

  await pruefen('Basisschicht im Addon aktualisieren', async () => {
    const b = path.join(ordner, 'build.gradle');
    fs.writeFileSync(b, fs.readFileSync(b, 'utf8').replace(/ext\.basiskitVersion = '[^']+'/, "ext.basiskitVersion = '0.0.1'"));
    fs.renameSync(path.join(ordner, `basiskit/basis-${version}.jar`), path.join(ordner, 'basiskit/basis-0.0.1.jar'));
    const e = api.werkzeug.basiskitAktualisieren(path.join(vscode.extensions.getExtension('freudenthaler.kieselstein-addon').extensionPath, 'projekt'), ordner);
    assert.deepStrictEqual(e, { alt: '0.0.1', neu: version });
    assert.ok(fs.existsSync(path.join(ordner, `basiskit/basis-${version}.jar`)) && !fs.existsSync(path.join(ordner, 'basiskit/basis-0.0.1.jar')));
    await api.werkzeug.bauen(ordner, 'lieferscheine', {});
  });

  await pruefen('Addon in die erste Stufe laden und aufrufen', async () => {
    const r = await api.server.inErsteStufeLaden(path.join(ordner, 'build/libs/lieferscheine.war'), api.serverListe());
    const seite = await (await fetch(`${r.adresse}/lieferscheine/`)).text();
    assert.ok(seite.includes('Lieferscheine'), 'Seite ohne Titel');
  });
};

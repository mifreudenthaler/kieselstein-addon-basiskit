// Läuft in VS Code: prüft Aktivierung, Befehle, Seitenleiste, Laden in die erste Stufe und Einrichten.
const vscode = require('vscode');
const assert = require('node:assert');

const pruefen = async (name, f) => {
  const t0 = Date.now();
  try {
    await f();
    console.log(`  ✔ ${name} (${Date.now() - t0} ms)`);
  } catch (e) {
    console.log(`  ✘ ${name}: ${e.message}`);
    throw e;
  }
};

exports.run = async () => {
  const ext = vscode.extensions.getExtension('freudenthaler.kieselstein-addon');
  assert.ok(ext, 'Erweiterung nicht gefunden');
  const api = await ext.activate();

  await pruefen('Befehle registriert', async () => {
    const alle = await vscode.commands.getCommands(true);
    for (const id of ['serverEintragen', 'basiskitEinrichten', 'serverStarten', 'serverAnhalten', 'zentraleOeffnen', 'anlegen', 'inDenTest', 'testen', 'fassade']) {
      assert.ok(alle.includes(`kieselsteinAddon.${id}`), `kieselsteinAddon.${id} fehlt`);
    }
    assert.ok(!alle.includes('kieselsteinAddon.protokoll'), 'Protokoll-Befehl sollte entfernt sein');
  });

  let knoten;
  await pruefen('Seitenleiste zeigt die Kette mit Stand', async () => {
    knoten = await api.ansicht.getChildren();
    const namen = [];
    for (const k of knoten) {
      const t = await api.ansicht.getTreeItem(k);
      namen.push(`${t.label}: ${t.description}`);
    }
    console.log('    ' + namen.join('\n    '));
    assert.deepStrictEqual(knoten.map((k) => k.srv.stufe), ['entwicklung', 'test', 'produktion']);
    assert.ok(namen.every((n) => /Zentrale 0\.2\.0/.test(n)), 'nicht überall Zentrale 0.2.0');
    const addons = await api.ansicht.getChildren(knoten[1]);
    assert.ok(addons.some((a) => a.addon.name === 'auftraege'), 'Test zeigt auftraege nicht');
  });

  await pruefen('Addon bauen und in die erste Stufe laden (Entwicklung)', async () => {
    const r = await vscode.commands.executeCommand('kieselsteinAddon.inDenTest', { name: 'auftraege', titel: 'Aufträge' });
    assert.ok(r, 'kein Ergebnis');
    assert.strictEqual(r.stufe, 'entwicklung');
  });

  await pruefen('Basiskit auf dem Test aktualisieren', async () => {
    await vscode.commands.executeCommand('kieselsteinAddon.basiskitEinrichten', knoten[1]);
    const st = await api.server.stand(knoten[1].srv);
    assert.ok(st.laeuft && st.basiskit === '0.2.0', JSON.stringify(st));
  });

  await pruefen('Update-Prüfung: Versionen vergleichen, Repo erkennen', async () => {
    assert.ok(api.neuer('0.2.1', '0.2.0') && api.neuer('1.0.0', '0.9.9') && !api.neuer('0.2.0', '0.2.0') && !api.neuer('0.1.9', '0.2.0'));
    assert.strictEqual(api.githubRepo(), 'mifreudenthaler/kieselstein-addon-basiskit');
  });

  await pruefen('Freigabe-Schlüssel aus der Kette übernommen', async () => {
    const k = await api.schluessel();
    const r = await api.server.ausfuehren(knoten[2].srv, `cat '${knoten[2].srv.kieselstein}/addons/config/freigabe.schluessel'`);
    assert.strictEqual(k, r.aus.trim());
  });
};

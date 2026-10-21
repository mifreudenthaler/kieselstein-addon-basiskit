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

  await pruefen('Formular: Eingaben prüfen, Port statt Versatz', async () => {
    const f = api.formular;
    const fehler = f.pruefeEingaben({ stufe: 'test', ssh: 'nur-rechner', kieselstein: 'relativ', port: '8080' }, []);
    assert.ok(fehler.ssh && fehler.kieselstein && /Kieselstein selbst/.test(fehler.port), JSON.stringify(fehler));
    const belegt = f.pruefeEingaben({ stufe: 'test', ssh: '', kieselstein: '/opt/ks', port: '8380' }, [{ stufe: 'entwicklung', ssh: '', portVersatz: 300 }]);
    assert.ok(/Entwicklung/.test(belegt.port || ''), JSON.stringify(belegt));
    const e = f.eintrag({ stufe: 'produktion', ssh: 'ks@erp-prod', kieselstein: '/opt/kieselstein/', port: '8280', adresse: '' });
    assert.deepStrictEqual(e, { stufe: 'produktion', ssh: 'ks@erp-prod', kieselstein: '/opt/kieselstein', adresse: 'http://erp-prod:8280', portVersatz: 200 });
  });

  await pruefen('Formular: Kieselstein suchen und prüfen', async () => {
    const gefunden = await api.formular.kieselsteinSuchen('');
    assert.ok(gefunden.some((g) => g.ordner.endsWith('/.kieselstein/versionen/2.0.0')), JSON.stringify(gefunden));
    const ok = await api.formular.pruefen({ stufe: 'test', ssh: '', kieselstein: gefunden.find((g) => g.ordner.endsWith('2.0.0')).ordner, port: '8280' });
    assert.ok(ok.ok && /Zentrale 0\.2\.0 ist installiert/.test(ok.text), ok.text);
    const nein = await api.formular.pruefen({ stufe: 'test', ssh: '', kieselstein: '/tmp', port: '8280' });
    assert.ok(!nein.ok, nein.text);
  });

  await pruefen('Formular öffnet sich (neu und bearbeiten)', async () => {
    vscode.commands.executeCommand('kieselsteinAddon.serverEintragen');
    await new Promise((r) => setTimeout(r, 1500));
    vscode.commands.executeCommand('kieselsteinAddon.serverBearbeiten', knoten[1]);
    await new Promise((r) => setTimeout(r, 1500));
    const offen = vscode.window.tabGroups.all.flatMap((g) => g.tabs).map((t) => t.label);
    assert.ok(offen.includes('Server eintragen') && offen.includes('Server – Test'), offen.join(', '));
    await vscode.commands.executeCommand('workbench.action.closeAllEditors');
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

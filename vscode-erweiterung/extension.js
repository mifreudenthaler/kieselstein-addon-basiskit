'use strict';
/*
 * Kieselstein-Addon-Erweiterung für VS Code.
 *
 * Seitenleiste „Kieselstein-Addons": die Server der Kette – Test und
 * Produktion, auf Wunsch zusätzlich Entwicklung – mit dem Stand ihrer
 * Zentrale. Von hier wird das Basiskit (Addon-Server und Zentrale)
 * eingerichtet und aktualisiert, auch auf der Produktion.
 *
 * Addons lädt die Erweiterung nur in die erste Stufe (Entwicklung, wenn es
 * sie gibt, sonst Test). Weiter geht ein Addon nur über „Ausrollen" in der
 * Zentrale der vorigen Stufe, durch einen Menschen. Protokolle und Messwerte
 * stehen in der Zentrale, nicht hier.
 */
const vscode = require('vscode');
const path = require('node:path');
const w = require('./lib/werkzeug');
const s = require('./lib/server');
const f = require('./lib/formular');

let ausgabe;
let leiste;
let ansicht;
let kontext;

// --- Einstellungen -----------------------------------------------------------

function konfig() {
  return vscode.workspace.getConfiguration('kieselsteinAddon');
}

function einstellung(schluessel) {
  const wert = konfig().get(schluessel);
  return typeof wert === 'string' ? wert.replace(/^~(?=\/|$)/, require('node:os').homedir()) : wert;
}

function serverListe() {
  return (konfig().get('server') || []).filter((x) => x && s.STUFEN.includes(x.stufe) && x.kieselstein);
}

async function serverSpeichern(liste) {
  const ziel = vscode.workspace.workspaceFolders?.length ? vscode.ConfigurationTarget.Workspace : vscode.ConfigurationTarget.Global;
  await konfig().update('server', liste, ziel);
}

function wurzel(pflicht = true) {
  for (const ordner of vscode.workspace.workspaceFolders || []) {
    const r = w.projektWurzel(ordner.uri.fsPath);
    if (r) return r;
  }
  if (pflicht) throw new Error('Kein Basiskit-Projekt im Arbeitsbereich (settings.gradle mit basis/).');
  return null;
}

/**
 * Der gemeinsame Freigabe-Schlüssel der Kette. Liegt er schon auf einem
 * eingerichteten Server, wird er übernommen – so kann auch ein zweiter
 * Entwickler die Kette pflegen. Sonst wird einer erzeugt.
 */
async function schluessel() {
  const id = `kieselsteinAddon.schluessel:${vscode.workspace.workspaceFolders?.[0]?.uri.toString() || 'global'}`;
  let k = await kontext.secrets.get(id);
  if (k) return k;
  for (const srv of s.kette(serverListe())) {
    const r = await s.ausfuehren(srv, `cat '${srv.kieselstein.replace(/\/$/, '')}/addons/config/freigabe.schluessel' 2>/dev/null`).catch(() => null);
    if (r && r.code === 0 && /^[0-9a-f]{32,}$/.test(r.aus.trim())) {
      k = r.aus.trim();
      break;
    }
  }
  k = k || s.neuerSchluessel();
  await kontext.secrets.store(id, k);
  return k;
}

// --- Seitenleiste ------------------------------------------------------------

class Serveransicht {
  constructor() {
    this.aenderung = new vscode.EventEmitter();
    this.onDidChangeTreeData = this.aenderung.event;
    this.staende = new Map();
  }

  neuLaden() {
    this.staende.clear();
    this.aenderung.fire();
  }

  async standVon(srv) {
    const k = `${srv.stufe}`;
    if (!this.staende.has(k)) this.staende.set(k, s.stand(srv));
    return this.staende.get(k);
  }

  async getChildren(knoten) {
    if (!knoten) {
      const liste = s.kette(serverListe());
      if (!liste.length) return [];
      return liste.map((srv) => ({ art: 'server', srv }));
    }
    if (knoten.art === 'server') {
      const st = await this.standVon(knoten.srv);
      return (st.addons || []).map((a) => ({ art: 'addon', srv: knoten.srv, addon: a }));
    }
    return [];
  }

  async getTreeItem(knoten) {
    if (knoten.art === 'addon') {
      const t = new vscode.TreeItem(knoten.addon.name, vscode.TreeItemCollapsibleState.None);
      t.description = knoten.addon.zustand;
      t.iconPath = new vscode.ThemeIcon(knoten.addon.zustand === 'läuft' ? 'pass' : knoten.addon.zustand === 'fehler' ? 'error' : 'circle-slash');
      t.contextValue = 'addon';
      return t;
    }
    const srv = knoten.srv;
    const st = await this.standVon(srv);
    const p = paketInfo();
    const t = new vscode.TreeItem(s.STUFEN_NAME[srv.stufe], vscode.TreeItemCollapsibleState.Expanded);
    let beschreibung;
    let wert;
    if (!st.erreichbar) {
      beschreibung = 'nicht erreichbar';
      wert = 'server-fehlt';
      t.iconPath = new vscode.ThemeIcon('debug-disconnect');
    } else if (!st.kieselstein) {
      beschreibung = 'kein Kieselstein unter diesem Pfad';
      wert = 'server-fehlt';
      t.iconPath = new vscode.ThemeIcon('warning');
    } else if (!st.installiert) {
      beschreibung = 'Zentrale nicht installiert';
      wert = 'server-installieren';
      t.iconPath = new vscode.ThemeIcon('cloud-download');
    } else {
      const veraltet = p && st.basiskit !== p.version;
      beschreibung = `Zentrale ${st.basiskit}${veraltet ? ` – ${p.version} verfügbar` : ''} · ${st.laeuft ? 'läuft' : 'angehalten'}`;
      wert = `server-${veraltet ? 'aktualisieren' : 'aktuell'}-${st.laeuft ? 'laeuft' : 'steht'}`;
      t.iconPath = new vscode.ThemeIcon(st.laeuft ? 'vm-running' : 'vm-outline');
    }
    t.description = beschreibung;
    t.tooltip = new vscode.MarkdownString([
      `**${s.STUFEN_NAME[srv.stufe]}**`,
      `Maschine: ${srv.ssh || 'diese Maschine'}`,
      `Kieselstein: ${srv.kieselstein}${st.kieselsteinVersion ? ` (${st.kieselsteinVersion})` : ''}`,
      `Zentrale: ${srv.adresse} (Port ${f.portAusVersatz(srv.portVersatz)})`,
      st.fehler ? `Fehler: ${st.fehler}` : '',
    ].filter(Boolean).join('  \n'));
    t.contextValue = wert;
    return t;
  }
}

function paketInfo() {
  return s.paket(kontext.extensionPath, wurzel(false));
}

// --- Befehle: Server ---------------------------------------------------------

/**
 * Formular für einen Server – neu (ohne Knoten) oder zum Bearbeiten (Rechtsklick auf den Server).
 * Gibt ein Promise zurück, das mit dem gespeicherten Eintrag (oder null) endet.
 */
function serverFormular(knoten) {
  const alt = knoten?.srv || null;
  const panel = vscode.window.createWebviewPanel('kieselsteinAddon.server', alt ? `Server – ${s.STUFEN_NAME[alt.stufe]}` : 'Server eintragen',
    vscode.ViewColumn.Active, { enableScripts: true, retainContextWhenHidden: true });
  const nonce = require('node:crypto').randomBytes(16).toString('hex');
  panel.webview.html = f.html(nonce, panel.webview.cspSource);
  const vorhanden = serverListe();
  const vorschlag = alt ? null : (['test', 'produktion', 'entwicklung'].find((x) => !vorhanden.some((v) => v.stufe === x)) || 'test');
  panel.webview.postMessage({
    art: 'vorbelegen',
    titel: alt ? `Server bearbeiten – ${s.STUFEN_NAME[alt.stufe]}` : 'Server eintragen',
    stufeFest: Boolean(alt),
    werte: alt
      ? { stufe: alt.stufe, ssh: alt.ssh, kieselstein: alt.kieselstein, port: f.portAusVersatz(alt.portVersatz), adresse: alt.adresse === `http://${alt.ssh ? alt.ssh.split('@').pop() : require('node:os').hostname()}:${f.portAusVersatz(alt.portVersatz)}` ? '' : alt.adresse }
      : { stufe: vorschlag, port: vorhanden.some((v) => !v.ssh) ? 8280 + 100 * vorhanden.filter((v) => !v.ssh).length : 8280 },
  });
  return new Promise((fertig) => {
    let erledigt = false;
    const ende = (wert) => { if (!erledigt) { erledigt = true; fertig(wert); } };
    panel.onDidDispose(() => ende(null));
    panel.webview.onDidReceiveMessage(async (m) => {
      const werte = alt ? { ...m.werte, stufe: alt.stufe } : m.werte;
      try {
        if (m.art === 'abbrechen') {
          panel.dispose();
        } else if (m.art === 'suchen') {
          panel.webview.postMessage({ art: 'gefunden', liste: await f.kieselsteinSuchen(werte.ssh) });
        } else if (m.art === 'pruefen' || m.art === 'speichern') {
          const andere = serverListe().filter((v) => v.stufe !== (alt ? alt.stufe : werte.stufe));
          const fehlerFelder = f.pruefeEingaben(werte, andere);
          panel.webview.postMessage({ art: 'fehler', fehler: fehlerFelder });
          if (Object.keys(fehlerFelder).length) {
            panel.webview.postMessage({ art: 'ergebnis', ok: false, text: 'Bitte die markierten Felder prüfen.' });
            return;
          }
          const p = await f.pruefen(werte);
          panel.webview.postMessage({ art: 'ergebnis', ok: p.ok, text: p.text });
          if (m.art === 'speichern' && p.ok) {
            const neu = f.eintrag(werte);
            await serverSpeichern([...serverListe().filter((v) => v.stufe !== neu.stufe), neu]);
            ansicht.neuLaden();
            aktualisieren();
            vscode.window.showInformationMessage(`${s.STUFEN_NAME[neu.stufe]} gespeichert.`);
            panel.dispose();
            ende(neu);
          }
        }
      } catch (e) {
        panel.webview.postMessage({ art: 'ergebnis', ok: false, text: e.message });
      }
    });
  });
}

async function serverEintragen() {
  return serverFormular(null);
}

async function serverBearbeiten(knoten) {
  if (knoten?.srv) return serverFormular(knoten);
  return null;
}

async function serverEntfernen(knoten) {
  const srv = knoten?.srv;
  if (!srv) return;
  const ok = await vscode.window.showWarningMessage(`${s.STUFEN_NAME[srv.stufe]} aus der Liste nehmen? Auf dem Server ändert sich nichts.`, { modal: true }, 'Entfernen');
  if (ok !== 'Entfernen') return;
  await serverSpeichern(serverListe().filter((v) => v.stufe !== srv.stufe));
  ansicht.neuLaden();
}

/**
 * Basiskit einrichten oder aktualisieren. Ohne Angabe: alle Server der Kette,
 * in Reihenfolge – so kennt jede Zentrale die Adresse der nächsten.
 */
async function basiskitEinrichten(knoten) {
  try {
    const alle = serverListe();
    const ziele = knoten?.srv ? [knoten.srv] : s.kette(alle);
    if (!ziele.length) throw new Error('Noch kein Server eingetragen.');
    const p = paketInfo();
    if (!p) throw new Error('Kein Basiskit-Paket gefunden (in der Erweiterung oder unter build/paket).');
    if (ziele.some((z) => z.stufe === 'produktion')) {
      const ok = await vscode.window.showWarningMessage(
        `Basiskit ${p.version} auf der Produktion einrichten? Der Addon-Server dort startet dafür neu – Kieselstein selbst nicht.`,
        { modal: true }, 'Einrichten');
      if (ok !== 'Einrichten') return;
    }
    const k = await schluessel();
    await mitFortschritt(`Basiskit ${p.version} einrichten`, async (fortschritt) => {
      for (const z of ziele) {
        fortschritt.report({ message: s.STUFEN_NAME[z.stufe] });
        await s.einrichten(z, alle, { paketDatei: p.datei, schluessel: k, melde: (m) => ausgabe.appendLine(m) });
      }
      ausgabe.appendLine(`✔ Basiskit ${p.version} eingerichtet: ${ziele.map((z) => s.STUFEN_NAME[z.stufe]).join(', ')}`);
      vscode.window.showInformationMessage(`Basiskit ${p.version} eingerichtet: ${ziele.map((z) => s.STUFEN_NAME[z.stufe]).join(', ')}.`);
    });
  } catch (e) {
    fehler(e);
  }
  ansicht.neuLaden();
  aktualisieren();
}

async function serverStarten(knoten) {
  if (!knoten?.srv) return;
  await mitFortschritt(`${s.STUFEN_NAME[knoten.srv.stufe]}: Addon-Server starten`, () => s.starten(knoten.srv)).catch(fehler);
  ansicht.neuLaden();
}

async function serverAnhalten(knoten) {
  if (!knoten?.srv) return;
  const ok = await vscode.window.showWarningMessage(`Addon-Server ${s.STUFEN_NAME[knoten.srv.stufe]} anhalten? Alle Addons dort sind dann nicht erreichbar. Kieselstein läuft weiter.`, { modal: true }, 'Anhalten');
  if (ok !== 'Anhalten') return;
  await mitFortschritt(`${s.STUFEN_NAME[knoten.srv.stufe]}: Addon-Server anhalten`, () => s.anhalten(knoten.srv)).catch(fehler);
  ansicht.neuLaden();
}

function zentraleOeffnen(knoten) {
  const srv = knoten?.srv || s.ersteStufe(serverListe());
  if (srv) vscode.env.openExternal(vscode.Uri.parse(`${srv.adresse}/`));
}

function verwaltungOeffnen(knoten) {
  const srv = knoten?.srv || s.ersteStufe(serverListe());
  if (srv) vscode.env.openExternal(vscode.Uri.parse(`${srv.adresse}/verwaltung.xhtml`));
}

// --- Befehle: Addons ---------------------------------------------------------

function optionen() {
  return { jdkPfad: einstellung('jdk') || undefined, clientLib: einstellung('clientLib') || undefined, melde: (z) => ausgabe.appendLine(z) };
}

async function addonWaehlen(frage) {
  const r = wurzel();
  const passend = w.addonZuDatei(r, vscode.window.activeTextEditor?.document.uri.fsPath);
  if (passend && passend.name !== 'zentrale') return passend;
  const liste = w.addons(r).filter((a) => a.name !== 'zentrale');
  if (liste.length === 1) return liste[0];
  const wahl = await vscode.window.showQuickPick(liste.map((a) => ({ label: a.titel, description: a.name, addon: a })), { placeHolder: frage });
  return wahl?.addon;
}

async function addonAnlegen() {
  try {
    const r = wurzel();
    const name = await vscode.window.showInputBox({
      prompt: 'Name des Addons (wird Adresse und Ordner), z. B. lieferscheine',
      validateInput: (v) => (/^[a-z][a-z0-9-]{1,40}$/.test(v) ? null : 'Kleinbuchstaben, Ziffern, Bindestrich'),
    });
    if (!name) return;
    const titel = await vscode.window.showInputBox({ prompt: 'Titel, wie er in der Zentrale steht', value: name });
    if (!titel) return;
    const beschreibung = (await vscode.window.showInputBox({ prompt: 'Ein Satz, was das Addon tut' })) || '';
    const ordner = w.addonAnlegen(r, name, titel, beschreibung);
    await vscode.window.showTextDocument(vscode.Uri.file(path.join(ordner, 'src/main/webapp/index.xhtml')));
    vscode.window.showInformationMessage(`Addon „${titel}" angelegt.`);
  } catch (e) {
    fehler(e);
  }
}

/** Bauen und in die erste Stufe laden (Entwicklung, wenn eingetragen, sonst Test). */
async function inErsteStufe(addonVorgabe) {
  try {
    const addon = addonVorgabe || await addonWaehlen('Welches Addon laden?');
    if (!addon) return;
    const erste = s.ersteStufe(serverListe());
    if (!erste) throw new Error('Kein Test-Server eingetragen – in der Seitenleiste „Kieselstein-Addons" einen Server eintragen.');
    const stufe = s.STUFEN_NAME[erste.stufe];
    const e = await mitFortschritt(`${addon.titel}: bauen und in ${stufe} laden`, async (fortschritt) => {
      fortschritt.report({ message: 'bauen …' });
      const war = await w.bauen(wurzel(), addon.name, optionen());
      fortschritt.report({ message: `in ${stufe} laden …` });
      return s.inErsteStufeLaden(war, serverListe(), { melde: (m) => ausgabe.appendLine(m) });
    });
    ausgabe.appendLine(`✔ ${e.name} läuft in ${stufe} (nach ${e.sekunden} s)`);
    ansicht.neuLaden();
    // nicht abwarten: die Meldung bleibt stehen, der Befehl ist fertig
    vscode.window.showInformationMessage(`${addon.titel} läuft in ${stufe}.`, 'Öffnen').then((auf) => {
      if (auf) vscode.env.openExternal(vscode.Uri.parse(`${e.adresse}/${addon.name}/`));
    });
    return e;
  } catch (e) {
    fehler(e);
  }
}

async function testen() {
  try {
    const addon = await addonWaehlen('Welches Addon testen?');
    if (!addon) return;
    await mitFortschritt(`${addon.titel}: Tests`, () => w.testen(wurzel(), addon.name, optionen()));
    ausgabe.appendLine('✔ Tests bestanden');
    vscode.window.showInformationMessage(`${addon.titel}: Tests bestanden.`);
  } catch (e) {
    fehler(e);
  }
}

async function oeffnen() {
  const addon = await addonWaehlen('Welches Addon öffnen?');
  const erste = s.ersteStufe(serverListe());
  if (addon && erste) vscode.env.openExternal(vscode.Uri.parse(`${erste.adresse}/${addon.name}/`));
}

async function fassadeNachschlagen() {
  try {
    const lib = einstellung('clientLib');
    if (!lib) throw new Error('Einstellung kieselsteinAddon.clientLib fehlt (lib/ des Kieselstein-Clients der Zielversion).');
    const wahl = await vscode.window.showQuickPick(
      w.fassaden(lib).map((k) => ({ label: k.split('.').pop(), description: k })), { placeHolder: 'Fassade suchen, z. B. AuftragFac' });
    if (!wahl) return;
    const dok = await vscode.workspace.openTextDocument({ content: w.fassadeZeigen(lib, wahl.description, einstellung('jdk') || undefined), language: 'java' });
    vscode.window.showTextDocument(dok, { preview: true });
  } catch (e) {
    fehler(e);
  }
}

// --- Updates -----------------------------------------------------------------

/** owner/repo aus dem repository-Eintrag der Erweiterung. */
function githubRepo() {
  const url = kontext.extension.packageJSON.repository?.url || '';
  const m = url.match(/github\.com[/:]([^/]+\/[^/.]+)/);
  return m ? m[1] : null;
}

function neuer(a, b) {
  const x = a.split('.').map(Number);
  const y = b.split('.').map(Number);
  for (let i = 0; i < 3; i++) {
    if ((x[i] || 0) !== (y[i] || 0)) return (x[i] || 0) > (y[i] || 0);
  }
  return false;
}

/**
 * Nach einer neueren Fassung auf GitHub sehen – höchstens einmal am Tag von
 * selbst, jederzeit über den Befehl. Ohne Internet (beim Kunden üblich) still.
 */
async function updatePruefen(vonHand = false) {
  const repo = githubRepo();
  if (!repo) return;
  const zuletzt = kontext.globalState.get('kieselsteinAddon.updateGeprueft', 0);
  if (!vonHand && Date.now() - zuletzt < 24 * 3600 * 1000) return;
  try {
    const r = await fetch(`https://api.github.com/repos/${repo}/releases/latest`, { headers: { 'User-Agent': 'kieselstein-addon' }, signal: AbortSignal.timeout(8000) });
    if (!r.ok) throw new Error(`GitHub antwortet mit ${r.status}`);
    const release = await r.json();
    await kontext.globalState.update('kieselsteinAddon.updateGeprueft', Date.now());
    const version = String(release.tag_name || '').replace(/^v/, '');
    const jetzt = kontext.extension.packageJSON.version;
    const vsix = (release.assets || []).find((a) => a.name.endsWith('.vsix'));
    if (!vsix || !neuer(version, jetzt)) {
      if (vonHand) vscode.window.showInformationMessage(`Kieselstein-Addon-Erweiterung ${jetzt} ist aktuell.`);
      return;
    }
    const wahl = await vscode.window.showInformationMessage(
      `Kieselstein-Addon-Erweiterung ${version} ist verfügbar (installiert: ${jetzt}). Danach lässt sich auch das Basiskit auf den Servern aktualisieren.`,
      'Aktualisieren', 'Später');
    if (wahl !== 'Aktualisieren') return;
    await mitFortschritt(`Erweiterung ${version} installieren`, async () => {
      const d = await fetch(vsix.browser_download_url, { headers: { 'User-Agent': 'kieselstein-addon' } });
      if (!d.ok) throw new Error(`Download gescheitert (${d.status})`);
      const datei = path.join(require('node:os').tmpdir(), vsix.name);
      require('node:fs').writeFileSync(datei, Buffer.from(await d.arrayBuffer()));
      await vscode.commands.executeCommand('workbench.extensions.installExtension', vscode.Uri.file(datei));
    });
    const neu = await vscode.window.showInformationMessage(`Erweiterung ${version} installiert – VS Code neu laden, um sie zu verwenden.`, 'Neu laden');
    if (neu) vscode.commands.executeCommand('workbench.action.reloadWindow');
  } catch (e) {
    if (vonHand) fehler(new Error(`Keine Update-Prüfung möglich: ${e.message}`));
  }
}

// --- Hilfen ------------------------------------------------------------------

async function mitFortschritt(titel, arbeit) {
  ausgabe.show(true);
  ausgabe.appendLine(`\n▸ ${titel}`);
  return vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title: titel }, arbeit);
}

function fehler(e) {
  ausgabe.appendLine(`✘ ${e.message}`);
  vscode.window.showErrorMessage(e.message.split('\n')[0]);
}

async function aktualisieren() {
  const erste = s.ersteStufe(serverListe());
  if (!erste) {
    leiste.text = '$(server) Kieselstein: Server eintragen';
    leiste.command = 'kieselsteinAddon.serverEintragen';
    return;
  }
  const st = await s.stand(erste);
  const laeuft = (st.addons || []).filter((a) => a.zustand === 'läuft').length;
  leiste.text = `$(server) ${s.STUFEN_NAME[erste.stufe]} · ${st.installiert ? `${laeuft}/${st.addons.length} Addons` : 'Zentrale fehlt'}`;
  leiste.tooltip = `Addons werden nach ${s.STUFEN_NAME[erste.stufe]} geladen (${erste.adresse})`;
  leiste.command = 'kieselsteinAddon.zentraleOeffnen';
}

function activate(k) {
  kontext = k;
  ausgabe = vscode.window.createOutputChannel('Kieselstein Addons');
  leiste = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 50);
  leiste.show();
  ansicht = new Serveransicht();
  k.subscriptions.push(vscode.window.registerTreeDataProvider('kieselsteinAddon.server', ansicht));
  aktualisieren().catch(() => {});
  const takt = setInterval(() => aktualisieren().catch(() => {}), 15000);

  const befehle = {
    'kieselsteinAddon.serverEintragen': serverEintragen,
    'kieselsteinAddon.serverEntfernen': serverEntfernen,
    'kieselsteinAddon.serverBearbeiten': serverBearbeiten,
    'kieselsteinAddon.basiskitEinrichten': basiskitEinrichten,
    'kieselsteinAddon.serverStarten': serverStarten,
    'kieselsteinAddon.serverAnhalten': serverAnhalten,
    'kieselsteinAddon.zentraleOeffnen': zentraleOeffnen,
    'kieselsteinAddon.verwaltungOeffnen': verwaltungOeffnen,
    'kieselsteinAddon.ansichtNeuLaden': () => ansicht.neuLaden(),
    'kieselsteinAddon.anlegen': addonAnlegen,
    'kieselsteinAddon.inDenTest': inErsteStufe,
    'kieselsteinAddon.testen': testen,
    'kieselsteinAddon.oeffnen': oeffnen,
    'kieselsteinAddon.fassade': fassadeNachschlagen,
    'kieselsteinAddon.updatePruefen': () => updatePruefen(true),
  };
  for (const [id, f] of Object.entries(befehle)) k.subscriptions.push(vscode.commands.registerCommand(id, f));
  k.subscriptions.push(ausgabe, leiste, { dispose: () => clearInterval(takt) },
    vscode.workspace.onDidChangeConfiguration((e) => { if (e.affectsConfiguration('kieselsteinAddon')) { ansicht.neuLaden(); aktualisieren(); } }));

  setTimeout(() => updatePruefen(false), 10000);

  // Für automatische Tests: dieselben Bausteine, ohne Eingabefelder
  return { server: s, werkzeug: w, formular: f, ansicht, serverListe, serverSpeichern, basiskitEinrichten, inErsteStufe, schluessel, neuer, githubRepo };
}

function deactivate() {}

module.exports = { activate, deactivate };

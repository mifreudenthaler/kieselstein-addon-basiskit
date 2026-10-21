'use strict';
/*
 * Kieselstein-Addon-Erweiterung für VS Code.
 *
 * Gedacht für die Arbeit auf dem Testserver beim Kunden (VS Code über
 * Remote-SSH) oder auf einem eigenen Entwicklungsserver. Sie bündelt die
 * Handgriffe bis zum Test: anlegen, bauen, testen, in den Test-Addon-Server
 * laden, Zustand und Protokoll sehen, Fassaden nachschlagen.
 *
 * In die Produktion führt hier kein Weg. Freigegeben wird in der
 * Test-Zentrale, von einem Menschen – „Freigabe öffnen" bringt einen dorthin.
 */
const vscode = require('vscode');
const fs = require('node:fs');
const path = require('node:path');
const w = require('./lib/werkzeug');

let ausgabe;
let leiste;
let protokollBeobachter;

function einstellung(schluessel) {
  const wert = vscode.workspace.getConfiguration('kieselsteinAddon').get(schluessel);
  return typeof wert === 'string' ? wert.replace(/^~(?=\/|$)/, require('node:os').homedir()) : wert;
}

function wurzel() {
  for (const ordner of vscode.workspace.workspaceFolders || []) {
    const r = w.projektWurzel(ordner.uri.fsPath);
    if (r) return r;
  }
  throw new Error('Kein Basiskit-Projekt im Arbeitsbereich (settings.gradle mit basis/).');
}

function optionen() {
  return {
    jdkPfad: einstellung('jdk') || undefined,
    clientLib: einstellung('clientLib') || undefined,
    melde: (z) => ausgabe.appendLine(z),
  };
}

/** Welches Addon? Das der offenen Datei, sonst Auswahl. */
async function addonWaehlen(frage, mitZentrale = true) {
  const r = wurzel();
  const offen = vscode.window.activeTextEditor?.document.uri.fsPath;
  const passend = w.addonZuDatei(r, offen);
  if (passend && (mitZentrale || passend.name !== 'zentrale')) return passend;
  const liste = w.addons(r).filter((a) => mitZentrale || a.name !== 'zentrale');
  if (liste.length === 1) return liste[0];
  const wahl = await vscode.window.showQuickPick(
    liste.map((a) => ({ label: a.titel, description: a.name, addon: a })), { placeHolder: frage });
  return wahl?.addon;
}

async function mitFortschritt(titel, arbeit) {
  ausgabe.show(true);
  ausgabe.appendLine(`\n▸ ${titel}`);
  return vscode.window.withProgress({ location: vscode.ProgressLocation.Notification, title: titel }, arbeit);
}

function fehler(e) {
  ausgabe.appendLine(`✘ ${e.message}`);
  vscode.window.showErrorMessage(e.message.split('\n')[0]);
}

// --- Befehle ---------------------------------------------------------------

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
    const beschreibung = await vscode.window.showInputBox({ prompt: 'Ein Satz, was das Addon tut' }) || '';
    const ordner = w.addonAnlegen(r, name, titel, beschreibung);
    const seite = path.join(ordner, 'src/main/webapp/index.xhtml');
    await vscode.window.showTextDocument(vscode.Uri.file(seite));
    vscode.window.showInformationMessage(`Addon „${titel}" angelegt. Bauen und in den Test laden: Kieselstein: Addon in den Test laden.`);
  } catch (e) {
    fehler(e);
  }
}

async function inDenTest() {
  try {
    const addon = await addonWaehlen('Welches Addon in den Test laden?');
    if (!addon) return;
    const addonsOrdner = einstellung('addonsOrdner');
    if (!addonsOrdner) throw new Error('Einstellung kieselsteinAddon.addonsOrdner fehlt (der Ordner addons/ neben dist und data).');
    await mitFortschritt(`${addon.titel}: bauen und in den Test laden`, async (fortschritt) => {
      fortschritt.report({ message: 'bauen …' });
      const war = await w.bauen(wurzel(), addon.name, optionen());
      fortschritt.report({ message: 'laden …' });
      const e = await w.inDenTestLaden(war, addonsOrdner);
      ausgabe.appendLine(`✔ ${e.name} läuft im Test (nach ${e.sekunden} s)`);
      const auf = await vscode.window.showInformationMessage(`${addon.titel} läuft im Test.`, 'Öffnen');
      if (auf) vscode.env.openExternal(vscode.Uri.parse(adresse(addon.name)));
    });
    aktualisieren();
  } catch (e) {
    fehler(e);
  }
}

async function testen() {
  try {
    const addon = await addonWaehlen('Welches Addon testen?');
    if (!addon) return;
    await mitFortschritt(`${addon.titel}: Tests`, async () => {
      await w.testen(wurzel(), addon.name, optionen());
      ausgabe.appendLine('✔ Tests bestanden');
      vscode.window.showInformationMessage(`${addon.titel}: Tests bestanden.`);
    });
  } catch (e) {
    fehler(e);
  }
}

function adresse(name) {
  const basis = (einstellung('adresse') || 'http://localhost:8280').replace(/\/$/, '');
  return name && name !== 'zentrale' ? `${basis}/${name}/` : `${basis}/`;
}

async function oeffnen() {
  const addon = await addonWaehlen('Welches Addon öffnen?');
  if (addon) vscode.env.openExternal(vscode.Uri.parse(adresse(addon.name)));
}

function freigabeOeffnen() {
  // Bewusst nur ein Verweis: Freigeben tut ein Mensch, in der Test-Zentrale.
  vscode.env.openExternal(vscode.Uri.parse(`${adresse()}verwaltung.xhtml`));
}

function protokoll() {
  const addonsOrdner = einstellung('addonsOrdner');
  const datei = path.join(addonsOrdner || '', 'logs', 'server.log');
  if (!fs.existsSync(datei)) {
    vscode.window.showWarningMessage(`Kein Protokoll unter ${datei}`);
    return;
  }
  const kanal = vscode.window.createOutputChannel('Kieselstein Addon-Server', 'log');
  kanal.show(true);
  let stelle = Math.max(0, fs.statSync(datei).size - 20000);
  const lesen = () => {
    const groesse = fs.statSync(datei).size;
    if (groesse < stelle) stelle = 0; // neu begonnen
    if (groesse === stelle) return;
    const fd = fs.openSync(datei, 'r');
    const puffer = Buffer.alloc(groesse - stelle);
    fs.readSync(fd, puffer, 0, puffer.length, stelle);
    fs.closeSync(fd);
    stelle = groesse;
    kanal.append(puffer.toString('utf8'));
  };
  lesen();
  protokollBeobachter?.close();
  protokollBeobachter = fs.watch(datei, lesen);
}

async function fassadeNachschlagen() {
  try {
    const lib = einstellung('clientLib');
    if (!lib) throw new Error('Einstellung kieselsteinAddon.clientLib fehlt (lib/ des Kieselstein-Clients der Zielversion).');
    const wahl = await vscode.window.showQuickPick(
      w.fassaden(lib).map((k) => ({ label: k.split('.').pop(), description: k })),
      { placeHolder: 'Fassade suchen, z. B. AuftragFac' });
    if (!wahl) return;
    const text = w.fassadeZeigen(lib, wahl.description, einstellung('jdk') || undefined);
    const dok = await vscode.workspace.openTextDocument({ content: text, language: 'java' });
    vscode.window.showTextDocument(dok, { preview: true });
  } catch (e) {
    fehler(e);
  }
}

// --- Statusleiste ------------------------------------------------------------

function aktualisieren() {
  const addonsOrdner = einstellung('addonsOrdner');
  if (!addonsOrdner) {
    leiste.text = '$(server) Kieselstein: Addon-Server einstellen';
    leiste.tooltip = 'kieselsteinAddon.addonsOrdner setzen';
    return;
  }
  const z = w.zustand(addonsOrdner);
  const laeuft = z.addons.filter((a) => a.zustand === 'läuft').length;
  const fehlerhaft = z.addons.filter((a) => a.zustand === 'Fehler').length;
  leiste.text = `$(server) ${z.stufe === 'test' ? 'Test' : z.stufe} · ${laeuft}/${z.addons.length} Addons${fehlerhaft ? ` · ${fehlerhaft} Fehler` : ''}`;
  leiste.tooltip = z.addons.map((a) => `${a.name}: ${a.zustand}`).join('\n') || 'keine Addons';
  leiste.backgroundColor = fehlerhaft ? new vscode.ThemeColor('statusBarItem.errorBackground') : undefined;
}

function activate(kontext) {
  ausgabe = vscode.window.createOutputChannel('Kieselstein Addons');
  leiste = vscode.window.createStatusBarItem(vscode.StatusBarAlignment.Left, 50);
  leiste.command = 'kieselsteinAddon.oeffnen';
  leiste.show();
  aktualisieren();
  const takt = setInterval(aktualisieren, 5000);

  const befehle = {
    'kieselsteinAddon.anlegen': addonAnlegen,
    'kieselsteinAddon.inDenTest': inDenTest,
    'kieselsteinAddon.testen': testen,
    'kieselsteinAddon.oeffnen': oeffnen,
    'kieselsteinAddon.freigabeOeffnen': freigabeOeffnen,
    'kieselsteinAddon.protokoll': protokoll,
    'kieselsteinAddon.fassade': fassadeNachschlagen,
  };
  for (const [id, f] of Object.entries(befehle)) kontext.subscriptions.push(vscode.commands.registerCommand(id, f));
  kontext.subscriptions.push(ausgabe, leiste, { dispose: () => { clearInterval(takt); protokollBeobachter?.close(); } });
}

function deactivate() {}

module.exports = { activate, deactivate };

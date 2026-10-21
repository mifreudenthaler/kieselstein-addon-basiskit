'use strict';
/*
 * Das Formular für einen Server der Kette – statt einer Folge von Eingabezeilen.
 *
 * Gefragt wird nur, was ein Mensch weiß: welche Stufe, welcher Rechner, wo
 * Kieselstein liegt, auf welchem Port die Zentrale erreichbar sein soll.
 * Den Kieselstein-Ordner sucht das Formular selbst, und „Prüfen" sagt, ob
 * Rechner und Ordner stimmen, bevor gespeichert wird.
 */
const s = require('./server');

const KIESELSTEIN_PORT = 8080;

/** Port der Zentrale ↔ Versatz des Addon-Servers (intern: Versatz zu den WildFly-Ports). */
function portAusVersatz(v) {
  return KIESELSTEIN_PORT + Number(v || 200);
}

function versatzAusPort(p) {
  return Number(p) - KIESELSTEIN_PORT;
}

function rechnerName(ssh) {
  return ssh ? ssh.split('@').pop() : require('node:os').hostname();
}

/** Prüft die Eingaben; gibt Fehlertexte je Feld zurück (leer = in Ordnung). */
function pruefeEingaben(e, vorhanden) {
  const fehler = {};
  if (!s.STUFEN.includes(e.stufe)) fehler.stufe = 'Bitte eine Stufe wählen';
  if (e.ssh && !/^[^\s@]+@[^\s@]+$/.test(e.ssh)) fehler.ssh = 'Als benutzer@rechner, oder leer für diese Maschine';
  if (!e.kieselstein || !e.kieselstein.startsWith('/') && !e.kieselstein.startsWith('~')) fehler.kieselstein = 'Vollständiger Pfad, z. B. /opt/kieselstein';
  const port = Number(e.port);
  if (!Number.isInteger(port) || port < 1024 || port > 65000) fehler.port = 'Zahl zwischen 1024 und 65000';
  else if (port === KIESELSTEIN_PORT) fehler.port = '8080 belegt Kieselstein selbst';
  else if (port <= KIESELSTEIN_PORT) fehler.port = `Bitte über ${KIESELSTEIN_PORT} wählen, z. B. 8280`;
  const gleicherRechner = (vorhanden || []).filter((v) => v.stufe !== e.stufe && (v.ssh || '') === (e.ssh || '')
    && portAusVersatz(v.portVersatz) === port);
  if (!fehler.port && gleicherRechner.length) fehler.port = `Port ${port} nutzt auf diesem Rechner schon ${s.STUFEN_NAME[gleicherRechner[0].stufe]}`;
  return fehler;
}

/** Aus den Eingaben den gespeicherten Eintrag machen. */
function eintrag(e) {
  const port = Number(e.port);
  return {
    stufe: e.stufe,
    ssh: (e.ssh || '').trim(),
    kieselstein: e.kieselstein.trim().replace(/\/$/, ''),
    adresse: (e.adresse || '').trim().replace(/\/$/, '') || `http://${rechnerName(e.ssh)}:${port}`,
    portVersatz: versatzAusPort(port),
  };
}

/** Kieselstein-Installationen auf dem Rechner suchen (Ordner mit dist/VERSION.txt). */
async function kieselsteinSuchen(ssh) {
  const r = await s.ausfuehren({ ssh, stufe: 'test', kieselstein: '/' },
    'for d in /opt/kieselstein* /srv/kieselstein* /usr/local/kieselstein* ~/kieselstein* ~/.kieselstein/versionen/* /home/*/kieselstein*; do '
    + '[ -f "$d/dist/VERSION.txt" ] && echo "$d|$(cat "$d/dist/VERSION.txt")"; done 2>/dev/null | sort -u');
  return r.aus.split('\n').filter((z) => z.includes('|')).map((z) => {
    const [ordner, version] = z.split('|');
    return { ordner, version: version.trim() };
  });
}

/** Rechner erreichbar? Kieselstein dort? Basiskit schon eingerichtet? */
async function pruefen(e) {
  const st = await s.stand({ ...eintrag(e), stufe: e.stufe });
  if (!st.erreichbar) return { ok: false, text: `Rechner nicht erreichbar: ${st.fehler || 'keine Antwort'}` };
  if (!st.kieselstein) return { ok: false, text: `Unter ${e.kieselstein} liegt keine Kieselstein-Installation (dist/VERSION.txt fehlt).` };
  return {
    ok: true,
    text: `Kieselstein ${st.kieselsteinVersion} gefunden – Zentrale ${st.installiert ? `${st.basiskit} ist installiert (${st.stufe})` : 'noch nicht installiert'}.`,
  };
}

function html(nonce, csp) {
  return `<!DOCTYPE html>
<html lang="de"><head><meta charset="UTF-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src ${csp} 'unsafe-inline'; script-src 'nonce-${nonce}';">
<style>
  body { font-family: var(--vscode-font-family); color: var(--vscode-foreground); padding: 1.5rem 2rem; max-width: 46rem; }
  h1 { font-size: 1.35rem; font-weight: 600; margin: 0 0 .3rem; }
  .unter { color: var(--vscode-descriptionForeground); margin: 0 0 1.6rem; }
  .feld { margin-bottom: 1.25rem; }
  label { display: block; font-weight: 600; margin-bottom: .3rem; }
  .hilfe { color: var(--vscode-descriptionForeground); font-size: .9em; margin-top: .3rem; }
  .fehler { color: var(--vscode-errorForeground); font-size: .9em; margin-top: .3rem; min-height: 1em; }
  input, select { width: 100%; box-sizing: border-box; padding: .45rem .55rem; background: var(--vscode-input-background);
    color: var(--vscode-input-foreground); border: 1px solid var(--vscode-input-border, var(--vscode-widget-border)); border-radius: 2px; font: inherit; }
  input.kurz { width: 9rem; }
  .zeile { display: flex; gap: .5rem; }
  .zeile input { flex: 1; }
  button { padding: .45rem 1rem; border: none; border-radius: 2px; cursor: pointer; font: inherit;
    background: var(--vscode-button-background); color: var(--vscode-button-foreground); }
  button.zweit { background: var(--vscode-button-secondaryBackground); color: var(--vscode-button-secondaryForeground); }
  .knoepfe { display: flex; gap: .5rem; margin-top: 1.8rem; }
  .ergebnis { margin-top: .5rem; padding: .5rem .7rem; border-radius: 2px; display: none; }
  .ergebnis.ok { display: block; background: var(--vscode-inputValidation-infoBackground); }
  .ergebnis.nein { display: block; background: var(--vscode-inputValidation-errorBackground); }
  details { margin-top: .5rem; } summary { cursor: pointer; color: var(--vscode-textLink-foreground); }
</style></head>
<body>
  <h1 id="titel">Server eintragen</h1>
  <p class="unter">Ein Server der Kette. Standard sind Test und Produktion beim Kunden; Entwicklung ist zusätzlich.</p>

  <div class="feld">
    <label for="stufe">Stufe</label>
    <select id="stufe">
      <option value="test">Test – hier werden Addons geladen und geprüft</option>
      <option value="produktion">Produktion – Addons kommen nur über „Ausrollen" aus dem Test</option>
      <option value="entwicklung">Entwicklung – zusätzlich, eigener Server vor dem Test</option>
    </select>
    <div class="fehler" id="f-stufe"></div>
  </div>

  <div class="feld">
    <label for="ssh">Rechner</label>
    <input id="ssh" placeholder="leer = diese Maschine, sonst benutzer@rechner (SSH)">
    <div class="hilfe">Derselbe Zugang wie für Remote-SSH. Leer lassen, wenn der Editor gerade auf diesem Server arbeitet.</div>
    <div class="fehler" id="f-ssh"></div>
  </div>

  <div class="feld">
    <label for="kieselstein">Kieselstein-Ordner</label>
    <div class="zeile">
      <input id="kieselstein" list="gefunden" placeholder="/opt/kieselstein">
      <button class="zweit" id="suchen" type="button">Suchen</button>
    </div>
    <datalist id="gefunden"></datalist>
    <div class="hilfe">Der Ordner mit <code>dist</code> und <code>data</code>. Daneben entsteht der Ordner <code>addons</code>.</div>
    <div class="fehler" id="f-kieselstein"></div>
  </div>

  <div class="feld">
    <label for="port">Port der Zentrale</label>
    <input id="port" class="kurz" value="8280" inputmode="numeric">
    <div class="hilfe">Unter diesem Port erreicht man die Addon-Zentrale im Browser. Kieselstein selbst bleibt auf 8080.</div>
    <div class="fehler" id="f-port"></div>
    <details>
      <summary>Adresse der Zentrale anders angeben</summary>
      <div class="feld" style="margin-top:.6rem">
        <input id="adresse" placeholder="automatisch: http://rechner:port">
        <div class="hilfe">Nur nötig, wenn die Zentrale unter einem anderen Namen erreichbar ist. Die vorige Stufe muss sie unter dieser Adresse erreichen.</div>
      </div>
    </details>
  </div>

  <div class="knoepfe">
    <button id="speichern" type="button">Speichern</button>
    <button class="zweit" id="pruefen" type="button">Prüfen</button>
    <button class="zweit" id="abbrechen" type="button">Abbrechen</button>
  </div>
  <div class="ergebnis" id="ergebnis"></div>

<script nonce="${nonce}">
  const vs = acquireVsCodeApi();
  const $ = (id) => document.getElementById(id);
  const werte = () => ({ stufe: $('stufe').value, ssh: $('ssh').value.trim(), kieselstein: $('kieselstein').value.trim(),
    port: $('port').value.trim(), adresse: $('adresse').value.trim() });
  const melden = (ok, text) => { const e = $('ergebnis'); e.className = 'ergebnis ' + (ok ? 'ok' : 'nein'); e.textContent = text; };
  $('speichern').onclick = () => vs.postMessage({ art: 'speichern', werte: werte() });
  $('pruefen').onclick = () => { melden(true, 'Prüfe …'); vs.postMessage({ art: 'pruefen', werte: werte() }); };
  $('abbrechen').onclick = () => vs.postMessage({ art: 'abbrechen' });
  $('suchen').onclick = () => { melden(true, 'Suche Kieselstein-Installationen …'); vs.postMessage({ art: 'suchen', werte: werte() }); };
  window.addEventListener('message', ({ data }) => {
    if (data.art === 'vorbelegen') {
      for (const [k, v] of Object.entries(data.werte)) if ($(k)) $(k).value = v ?? '';
      $('titel').textContent = data.titel;
      if (data.stufeFest) $('stufe').disabled = true;
    }
    if (data.art === 'fehler') {
      for (const k of ['stufe', 'ssh', 'kieselstein', 'port']) $('f-' + k).textContent = data.fehler[k] || '';
    }
    if (data.art === 'ergebnis') melden(data.ok, data.text);
    if (data.art === 'gefunden') {
      $('gefunden').innerHTML = '';
      for (const g of data.liste) { const o = document.createElement('option'); o.value = g.ordner; o.label = 'Kieselstein ' + g.version; $('gefunden').appendChild(o); }
      if (data.liste.length === 1 && !$('kieselstein').value) $('kieselstein').value = data.liste[0].ordner;
      melden(data.liste.length > 0, data.liste.length ? data.liste.length + ' Installation(en) gefunden: ' + data.liste.map(g => g.ordner + ' (' + g.version + ')').join(', ')
        : 'Keine Kieselstein-Installation an den üblichen Orten gefunden – bitte den Ordner eintragen.');
    }
  });
</script>
</body></html>`;
}

module.exports = { portAusVersatz, versatzAusPort, pruefeEingaben, eintrag, kieselsteinSuchen, pruefen, html };

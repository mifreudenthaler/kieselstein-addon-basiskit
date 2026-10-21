// Startet ein echtes VS Code (auf einem virtuellen Bildschirm) mit der Erweiterung
// und führt test/suite.js darin aus. Aufruf: xvfb-run -a node test/lauf.js <projektordner> <einstellungen.json>
const path = require('node:path');
const fs = require('node:fs');
const os = require('node:os');
const { runTests } = require(process.env.TEST_ELECTRON || '@vscode/test-electron');

(async () => {
  const [projekt, einstellungen] = process.argv.slice(2);
  const benutzer = fs.mkdtempSync(path.join(os.tmpdir(), 'ks-vscode-'));
  fs.mkdirSync(path.join(benutzer, 'User'), { recursive: true });
  fs.copyFileSync(einstellungen, path.join(benutzer, 'User', 'settings.json'));
  await runTests({
    extensionDevelopmentPath: path.resolve(__dirname, '..'),
    extensionTestsPath: path.resolve(__dirname, 'suite.js'),
    launchArgs: [projekt, '--user-data-dir', benutzer, '--disable-extensions', '--disable-workspace-trust'],
  });
})().catch((e) => { console.error('Testlauf gescheitert:', e.message); process.exit(1); });

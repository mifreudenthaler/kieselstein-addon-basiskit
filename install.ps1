# Kieselstein-Addon-Erweiterung in VS Code installieren (oder aktualisieren) – Windows.
#
#   irm https://raw.githubusercontent.com/mifreudenthaler/kieselstein-addon-basiskit/main/install.ps1 | iex
#
# Holt die neueste Erweiterung aus den GitHub-Releases und installiert sie mit
# dem VS-Code-Befehl „code". Das Installationspaket für Addon-Server und
# Zentrale steckt in der Erweiterung.
$ErrorActionPreference = 'Stop'
$repo = if ($env:KS_REPO) { $env:KS_REPO } else { 'mifreudenthaler/kieselstein-addon-basiskit' }

$code = $null
foreach ($c in 'code', 'code-insiders', 'codium') {
    if (Get-Command $c -ErrorAction SilentlyContinue) { $code = $c; break }
}
if (-not $code) {
    Write-Host 'VS Code wurde nicht gefunden (Befehl "code").' -ForegroundColor Red
    Write-Host 'Bitte VS Code installieren: https://code.visualstudio.com/ - und diese Zeile erneut ausfuehren.'
    exit 1
}

Write-Host 'Suche die neueste Fassung ...'
$release = Invoke-RestMethod -Uri "https://api.github.com/repos/$repo/releases/latest" -Headers @{ 'User-Agent' = 'kieselstein-addon' }
$asset = $release.assets | Where-Object { $_.name -like '*.vsix' } | Select-Object -First 1
if (-not $asset) {
    Write-Host "Keine Erweiterung im neuesten Release von $repo gefunden." -ForegroundColor Red
    exit 1
}

$datei = Join-Path ([IO.Path]::GetTempPath()) $asset.name
Write-Host "Lade $($asset.name) ..."
Invoke-WebRequest -Uri $asset.browser_download_url -OutFile $datei -UseBasicParsing
try {
    & $code --install-extension $datei --force
} finally {
    Remove-Item $datei -ErrorAction SilentlyContinue
}
Write-Host ''
Write-Host 'Fertig. In VS Code links auf das Kieselstein-Symbol klicken und "Server eintragen" waehlen.'

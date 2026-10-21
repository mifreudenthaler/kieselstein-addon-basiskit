# Kieselstein-Addon-Erweiterung installieren oder aktualisieren – Windows, für VS Code, Cursor und VSCodium.
#
#   irm https://raw.githubusercontent.com/mifreudenthaler/kieselstein-addon-basiskit/main/install.ps1 | iex
#
# Holt die neueste Erweiterung aus den GitHub-Releases und installiert sie in
# jeden gefundenen Editor (code, cursor, codium). Nur einen bestimmten:
# $env:KS_EDITOR = 'cursor'. Das Installationspaket für Addon-Server und
# Zentrale steckt in der Erweiterung.
$ErrorActionPreference = 'Stop'
$repo = if ($env:KS_REPO) { $env:KS_REPO } else { 'mifreudenthaler/kieselstein-addon-basiskit' }
$kandidaten = if ($env:KS_EDITOR) { @($env:KS_EDITOR) } else { @('code', 'cursor', 'codium', 'code-insiders') }

$editoren = @($kandidaten | Where-Object { Get-Command $_ -ErrorAction SilentlyContinue })
if ($editoren.Count -eq 0) {
    Write-Host 'Kein Editor gefunden (code, cursor oder codium).' -ForegroundColor Red
    Write-Host 'Bitte VS Code (https://code.visualstudio.com/), Cursor (https://cursor.com/) oder VSCodium (https://vscodium.com/)'
    Write-Host 'installieren - und diese Zeile erneut ausfuehren.'
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
    foreach ($e in $editoren) {
        Write-Host "Installiere in $e ..."
        & $e --install-extension $datei --force
    }
} finally {
    Remove-Item $datei -ErrorAction SilentlyContinue
}
Write-Host ''
Write-Host 'Fertig. Im Editor links auf das Kieselstein-Symbol klicken und "Server eintragen" waehlen.'

# Construit l'installeur Windows : dist\VDL-Setup-<version>.exe (+ version portable .zip)
#
#   powershell -ExecutionPolicy Bypass -File packaging\build_windows.ps1
#
# Prérequis : Python 3.10+ et Inno Setup 6 (winget install JRSoftware.InnoSetup).
# ffmpeg (build « essentials » de gyan.dev, licence GPL) est téléchargé automatiquement.

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $PSScriptRoot
$Pack = $PSScriptRoot
$Cache = Join-Path $Pack "cache"
$Dist = Join-Path $Root "dist"
Set-Location $Root

$Version = (Select-String -Path "vdl\__init__.py" -Pattern '__version__ = "([^"]+)"').Matches[0].Groups[1].Value
Write-Host "== VDL $Version ==" -ForegroundColor Cyan

# --- Python de compilation (environnement dédié, propre et reproductible)
$BuildVenv = Join-Path $Pack ".buildvenv"
if (-not (Test-Path "$BuildVenv\Scripts\python.exe")) {
    python -m venv $BuildVenv
}
$Py = "$BuildVenv\Scripts\python.exe"
& $Py -m pip install -q -U pip
& $Py -m pip install -q "yt-dlp[default,curl-cffi]" "PySide6>=6.6" "requests>=2.31" pyinstaller pillow
if ($LASTEXITCODE -ne 0) { throw "pip install a échoué" }

# --- Icône .ico (multi-tailles) depuis icon.png
& $Py -c "from PIL import Image; Image.open(r'vdl\gui\icon.png').save(r'packaging\vdl.ico', sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])"

# --- ffmpeg + ffprobe
New-Item -ItemType Directory -Force $Cache | Out-Null
$FfDir = Join-Path $Cache "ffmpeg"
if (-not (Test-Path "$FfDir\ffmpeg.exe")) {
    $Zip = Join-Path $Cache "ffmpeg.zip"
    Write-Host "Téléchargement de ffmpeg…"
    Invoke-WebRequest -UseBasicParsing "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip" -OutFile $Zip
    $Tmp = Join-Path $Cache "ffmpeg_tmp"
    Remove-Item -Recurse -Force $Tmp -ErrorAction SilentlyContinue
    Expand-Archive $Zip $Tmp
    $Bin = Get-ChildItem $Tmp -Recurse -Filter ffmpeg.exe | Select-Object -First 1
    New-Item -ItemType Directory -Force $FfDir | Out-Null
    Copy-Item (Join-Path $Bin.DirectoryName "ffmpeg.exe"), (Join-Path $Bin.DirectoryName "ffprobe.exe") $FfDir
    Copy-Item (Join-Path $Bin.Directory.Parent.FullName "LICENSE") (Join-Path $FfDir "LICENSE.txt") -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $Tmp, $Zip
}

# --- PyInstaller
Remove-Item -Recurse -Force (Join-Path $Dist "VDL"), (Join-Path $Root "build") -ErrorAction SilentlyContinue
& $Py -m PyInstaller --noconfirm --clean --distpath $Dist --workpath (Join-Path $Root "build") "packaging\vdl.spec"
if ($LASTEXITCODE -ne 0) { throw "PyInstaller a échoué" }

# --- Fichiers livrés à côté de VDL.exe
$App = Join-Path $Dist "VDL"
Copy-Item -Recurse $FfDir (Join-Path $App "ffmpeg")
Copy-Item -Recurse (Join-Path $Root "extension") (Join-Path $App "extension")
Copy-Item (Join-Path $Pack "LISEZ-MOI.txt") $App

# --- Installeur Inno Setup
$Iscc = @(
    "$env:LOCALAPPDATA\Programs\Inno Setup 6\ISCC.exe",
    "${env:ProgramFiles(x86)}\Inno Setup 6\ISCC.exe",
    "$env:ProgramFiles\Inno Setup 6\ISCC.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $Iscc) { throw "Inno Setup 6 introuvable (winget install JRSoftware.InnoSetup)" }
& $Iscc /Q "/DAppVersion=$Version" "packaging\vdl.iss"
if ($LASTEXITCODE -ne 0) { throw "Inno Setup a échoué" }

# --- Version portable (sans installation)
$Portable = Join-Path $Dist "VDL-Portable-$Version.zip"
Remove-Item -Force $Portable -ErrorAction SilentlyContinue
Compress-Archive -Path $App -DestinationPath $Portable

Write-Host ""
Write-Host "OK :" -ForegroundColor Green
Get-ChildItem $Dist -File | ForEach-Object { "  {0}  ({1:N0} Mo)" -f $_.Name, ($_.Length / 1MB) }

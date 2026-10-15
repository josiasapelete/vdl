# Recette PyInstaller : produit dist/VDL/VDL.exe (mode dossier, démarrage rapide
# et moins de faux positifs antivirus que le mode « fichier unique »).
# Lancer via packaging/build_windows.ps1.

import os
import re

from PyInstaller.utils.hooks import collect_all
from PyInstaller.utils.win32.versioninfo import (
    FixedFileInfo, StringFileInfo, StringStruct, StringTable, VarFileInfo, VarStruct, VSVersionInfo,
)

ROOT = os.path.abspath(os.path.join(SPECPATH, ".."))
VERSION = re.search(r'__version__ = "([^"]+)"', open(os.path.join(ROOT, "vdl", "__init__.py"), encoding="utf-8").read()).group(1)
NUMS = tuple(int(x) for x in VERSION.split(".")[:3]) + (0,)

datas = [(os.path.join(ROOT, "vdl", "gui", "icon.png"), os.path.join("vdl", "gui"))]
binaries = []
hiddenimports = []
# curl_cffi (imitation de Chrome) embarque une bibliothèque native
for pkg in ("curl_cffi",):
    d, b, h = collect_all(pkg)
    datas += d
    binaries += b
    hiddenimports += h

a = Analysis(
    [os.path.join(SPECPATH, "vdl_app.py")],
    pathex=[ROOT],
    datas=datas,
    binaries=binaries,
    hiddenimports=hiddenimports,
    excludes=[
        "tkinter", "unittest", "pydoc_data",
        "PySide6.QtWebEngineCore", "PySide6.QtWebEngineWidgets", "PySide6.QtWebEngineQuick",
        "PySide6.QtQml", "PySide6.QtQuick", "PySide6.QtQuickWidgets", "PySide6.Qt3DCore",
        "PySide6.QtMultimedia", "PySide6.QtPdf", "PySide6.QtCharts", "PySide6.QtDataVisualization",
        "PySide6.QtDesigner", "PySide6.QtSql", "PySide6.QtTest", "PySide6.QtBluetooth",
    ],
    noarchive=False,
)
pyz = PYZ(a.pure)

version_info = VSVersionInfo(
    ffi=FixedFileInfo(filevers=NUMS, prodvers=NUMS),
    kids=[
        StringFileInfo([StringTable("040C04B0", [
            StringStruct("CompanyName", "VDL"),
            StringStruct("FileDescription", "VDL — gestionnaire de téléchargements"),
            StringStruct("FileVersion", VERSION),
            StringStruct("InternalName", "VDL"),
            StringStruct("OriginalFilename", "VDL.exe"),
            StringStruct("ProductName", "VDL"),
            StringStruct("ProductVersion", VERSION),
        ])]),
        VarFileInfo([VarStruct("Translation", [0x040C, 1200])]),
    ],
)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name="VDL",
    console=False,
    icon=os.path.join(SPECPATH, "vdl.ico"),
    version=version_info,
    upx=False,          # UPX déclenche beaucoup de faux positifs antivirus
)
coll = COLLECT(exe, a.binaries, a.datas, name="VDL", upx=False)

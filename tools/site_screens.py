"""Copie les captures d'écran de l'app Android (Paparazzi) vers le site.

    cd android && gradlew recordPaparazziDebug
    python tools/site_screens.py
"""

import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "android" / "app" / "src" / "test" / "snapshots" / "images"
DST = ROOT / "site" / "assets" / "screens"

NAMES = {
    "site_1_home": "accueil",
    "site_2_browser": "navigateur",
    "site_3_quality": "qualite",
    "site_4_shields": "protection",
    "site_5_downloads": "telechargements",
    "site_6_home_dark": "accueil-sombre",
}

DST.mkdir(parents=True, exist_ok=True)
for shot, name in NAMES.items():
    src = SRC / f"app.vdl_SiteShotsTest_{shot}.png"
    shutil.copyfile(src, DST / f"{name}.png")
    print(f"{name}.png")

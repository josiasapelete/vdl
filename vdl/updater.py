"""Mises à jour automatiques.

- yt-dlp : les sites changent leur code en permanence, un yt-dlp vieux de
  quelques semaines finit par échouer sur YouTube/TikTok. Vérifié au plus une
  fois toutes les 12 h.
    * en développement : via pip ;
    * dans l'application compilée : téléchargement de l'archive officielle
      depuis GitHub (vérifiée par SHA-256), chargée au prochain lancement
      par ytdlp_loader.
- VDL lui-même : on signale simplement qu'une nouvelle version existe.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import time

import requests

from . import UPDATE_REPO, __version__
from .config import data_dir

CHECK_INTERVAL = 12 * 3600
PYPI_URL = "https://pypi.org/pypi/yt-dlp/json"
YTDLP_RELEASE_API = "https://api.github.com/repos/yt-dlp/yt-dlp/releases/latest"
KEEP_ARCHIVES = 2


def _ver(v: str) -> tuple[int, ...]:
    return tuple(int(x) for x in v.replace("-", ".").lstrip("v").split(".") if x.isdigit())


def current_version() -> str:
    from yt_dlp.version import __version__ as v
    return v


def _frozen() -> bool:
    return bool(getattr(sys, "frozen", False))


def _state_file():
    return data_dir() / "update.json"


def _due() -> bool:
    try:
        last = json.loads(_state_file().read_text())["last_check"]
        return time.time() - last > CHECK_INTERVAL
    except (OSError, ValueError, KeyError):
        return True


def _mark_checked() -> None:
    try:
        _state_file().write_text(json.dumps({"last_check": time.time()}))
    except OSError:
        pass


# --------------------------------------------------------------------------- yt-dlp

def update(force: bool = False) -> tuple[bool, str]:
    """Retourne (mis_à_jour, message). Les erreurs réseau ne sont jamais fatales."""
    if not force and not _due():
        return False, "Vérification récente, rien à faire."
    try:
        return _update_frozen() if _frozen() else _update_pip()
    except Exception as e:  # noqa: BLE001
        return False, f"Impossible de mettre à jour yt-dlp : {e}"


def _update_pip() -> tuple[bool, str]:
    cur = current_version()
    latest = requests.get(PYPI_URL, timeout=10).json()["info"]["version"]
    _mark_checked()
    if _ver(latest) <= _ver(cur):
        return False, f"yt-dlp est à jour ({cur})."
    proc = subprocess.run(
        [sys.executable, "-m", "pip", "install", "-q", "-U", "yt-dlp[default,curl-cffi]"],
        capture_output=True, text=True,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    if proc.returncode != 0:
        return False, f"Échec de la mise à jour de yt-dlp : {proc.stderr.strip()[-300:]}"
    return True, f"yt-dlp mis à jour {cur} → {latest} (pris en compte au prochain lancement)."


def _update_frozen() -> tuple[bool, str]:
    from .ytdlp_loader import ytdlp_dir

    cur = current_version()
    rel = requests.get(YTDLP_RELEASE_API, timeout=15, headers={"Accept": "application/vnd.github+json"}).json()
    latest = rel["tag_name"]
    _mark_checked()
    if _ver(latest) <= _ver(cur):
        return False, f"yt-dlp est à jour ({cur})."

    assets = {a["name"]: a["browser_download_url"] for a in rel.get("assets", [])}
    if "yt-dlp" not in assets or "SHA2-256SUMS" not in assets:
        return False, "Archive yt-dlp introuvable dans la dernière version."
    sums = requests.get(assets["SHA2-256SUMS"], timeout=15).text
    expected = next((line.split()[0] for line in sums.splitlines()
                     if line.strip().endswith(" yt-dlp")), "")
    data = requests.get(assets["yt-dlp"], timeout=120).content
    if not expected or hashlib.sha256(data).hexdigest() != expected.lower():
        return False, "Archive yt-dlp corrompue (somme de contrôle invalide), mise à jour annulée."

    folder = ytdlp_dir()
    name = f"yt-dlp-{latest}.zip"
    tmp = folder / (name + ".tmp")
    tmp.write_bytes(data)
    tmp.replace(folder / name)
    (folder / "current").write_text(name, encoding="utf-8")
    # Ménage : on garde les dernières archives seulement
    archives = sorted(folder.glob("yt-dlp-*.zip"), key=lambda p: p.stat().st_mtime, reverse=True)
    for old in archives[KEEP_ARCHIVES:]:
        old.unlink(missing_ok=True)
    return True, f"yt-dlp mis à jour {cur} → {latest} (pris en compte au prochain lancement)."


# --------------------------------------------------------------------------- VDL

def check_app_update() -> tuple[str, str] | None:
    """(version, url de la page de téléchargement) si une version plus récente de VDL existe."""
    if not UPDATE_REPO:
        return None
    try:
        rel = requests.get(f"https://api.github.com/repos/{UPDATE_REPO}/releases/latest", timeout=10,
                           headers={"Accept": "application/vnd.github+json"}).json()
        tag = rel.get("tag_name", "")
        if tag and _ver(tag) > _ver(__version__):
            return tag.lstrip("v"), rel.get("html_url") or f"https://github.com/{UPDATE_REPO}/releases/latest"
    except (requests.RequestException, ValueError):
        pass
    return None

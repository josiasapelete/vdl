"""Chargement d'un yt-dlp plus récent que celui embarqué (version installée .exe/.app).

Dans l'application compilée, pip n'existe pas. On télécharge donc l'archive
officielle de yt-dlp (fichier « yt-dlp » des releases GitHub, un zip Python)
dans le dossier de données, et ce module la fait passer avant la copie
embarquée au moment de « import yt_dlp ».

Ce module ne doit jamais importer yt_dlp lui-même : il est installé avant.
"""

from __future__ import annotations

import importlib.machinery
import sys
from pathlib import Path

from .config import data_dir


def ytdlp_dir() -> Path:
    p = data_dir() / "yt-dlp"
    p.mkdir(parents=True, exist_ok=True)
    return p


def active_zip() -> Path | None:
    """Archive yt-dlp à utiliser (désignée par le fichier « current »), si présente."""
    try:
        name = (ytdlp_dir() / "current").read_text(encoding="utf-8").strip()
    except OSError:
        return None
    path = ytdlp_dir() / name
    return path if name and path.is_file() else None


class _ZipFirstFinder:
    """Résout « yt_dlp » et ses sous-modules depuis l'archive téléchargée,
    avant l'importeur de PyInstaller (qui sinon gagnerait toujours)."""

    def __init__(self, zip_path: Path):
        self.zip_path = str(zip_path)

    def find_spec(self, name, path=None, target=None):
        if name != "yt_dlp" and not name.startswith("yt_dlp."):
            return None
        search = [self.zip_path] if name == "yt_dlp" else path
        return importlib.machinery.PathFinder.find_spec(name, search)


def install() -> None:
    """À appeler avant tout « import yt_dlp ». Sans effet hors application compilée."""
    if not getattr(sys, "frozen", False) or "yt_dlp" in sys.modules:
        return
    zip_path = active_zip()
    if zip_path is None:
        return
    finder = _ZipFirstFinder(zip_path)
    sys.meta_path.insert(0, finder)
    try:
        import yt_dlp  # noqa: F401 — vérifie que l'archive est utilisable
    except Exception:  # noqa: BLE001 — archive abîmée : retour à la version embarquée
        sys.meta_path.remove(finder)
        for mod in [m for m in sys.modules if m == "yt_dlp" or m.startswith("yt_dlp.")]:
            del sys.modules[mod]
        try:
            (ytdlp_dir() / "current").unlink()
        except OSError:
            pass

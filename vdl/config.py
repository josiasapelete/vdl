"""Chemins de l'application et réglages persistants (JSON)."""

from __future__ import annotations

import json
import os
import sys
import threading
from dataclasses import asdict, dataclass, field
from pathlib import Path


def data_dir() -> Path:
    """Dossier de données de l'app (base SQLite, réglages)."""
    if sys.platform == "win32":
        base = Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming"))
    elif sys.platform == "darwin":
        base = Path.home() / "Library" / "Application Support"
    else:
        base = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local" / "share"))
    path = base / "VDL"
    path.mkdir(parents=True, exist_ok=True)
    return path


def default_download_dir() -> Path:
    return Path.home() / "Downloads" / "VDL"


@dataclass
class Settings:
    download_dir: str = field(default_factory=lambda: str(default_download_dir()))
    max_concurrent: int = 3            # téléchargements simultanés
    segments: int = 8                  # connexions par fichier (multi-segments)
    fragment_threads: int = 4          # fragments HLS/DASH en parallèle (yt-dlp)
    default_quality: str = "best"      # "best", "2160", "1080", "720", "480", "360"
    default_mode: str = "video"        # "video" ou "audio"
    audio_bitrate: str = "192"         # kbps pour la conversion MP3
    subtitles: str = ""                # ex. "fr,en" — vide = pas de sous-titres
    auto_subtitles: bool = False       # inclure les sous-titres générés automatiquement
    clipboard_watch: bool = True
    auto_update_ytdlp: bool = True
    server_port: int = 9614
    cookies_from_browser: str = ""     # "chrome", "firefox", "edge"… pour les contenus privés

    _lock = threading.Lock()

    @classmethod
    def path(cls) -> Path:
        return data_dir() / "settings.json"

    @classmethod
    def load(cls) -> "Settings":
        s = cls()
        try:
            raw = json.loads(cls.path().read_text(encoding="utf-8-sig"))  # tolère un BOM (éditeurs Windows)
            for k, v in raw.items():
                if hasattr(s, k) and not k.startswith("_"):
                    setattr(s, k, v)
        except (OSError, ValueError):
            pass
        return s

    def save(self) -> None:
        with self._lock:
            data = {k: v for k, v in asdict(self).items() if not k.startswith("_")}
            self.path().write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")

"""Structures de données partagées : tâche de téléchargement et options."""

from __future__ import annotations

import time
import uuid
from dataclasses import asdict, dataclass, field
from enum import Enum


class Status(str, Enum):
    QUEUED = "queued"
    RUNNING = "running"
    PAUSED = "paused"
    PROCESSING = "processing"   # post-traitement ffmpeg (fusion, MP3, sous-titres)
    DONE = "done"
    ERROR = "error"
    CANCELED = "canceled"

    @property
    def label(self) -> str:
        return {
            "queued": "En attente",
            "running": "Téléchargement",
            "paused": "En pause",
            "processing": "Traitement",
            "done": "Terminé",
            "error": "Erreur",
            "canceled": "Annulé",
        }[self.value]

    @property
    def active(self) -> bool:
        return self in (Status.QUEUED, Status.RUNNING, Status.PROCESSING)


class Kind(str, Enum):
    YTDLP = "ytdlp"     # page vidéo / flux HLS-DASH → yt-dlp
    DIRECT = "direct"   # fichier classique → téléchargeur multi-segments


@dataclass
class Options:
    mode: str = "video"          # "video" ou "audio"
    quality: str = "best"        # "best" ou hauteur max ("1080", "720"…)
    format_id: str = ""          # format yt-dlp précis (prioritaire sur quality)
    audio_bitrate: str = "192"
    subtitles: str = ""          # langues, ex. "fr,en"
    auto_subtitles: bool = False
    referer: str = ""
    user_agent: str = ""
    headers: dict = field(default_factory=dict)   # en-têtes réels du navigateur (cookies, Origin…)
    impersonate: bool = False                     # se faire passer pour Chrome (empreinte TLS)

    @classmethod
    def from_dict(cls, d: dict | None) -> "Options":
        o = cls()
        for k, v in (d or {}).items():
            if hasattr(o, k):
                setattr(o, k, v)
        return o


@dataclass
class Task:
    url: str
    kind: Kind = Kind.YTDLP
    options: Options = field(default_factory=Options)
    id: str = field(default_factory=lambda: uuid.uuid4().hex[:12])
    title: str = ""
    status: Status = Status.QUEUED
    filepath: str = ""
    total_bytes: int = 0
    downloaded_bytes: int = 0
    speed: float = 0.0           # octets/s (non persisté)
    eta: float = 0.0             # secondes (non persisté)
    error: str = ""
    created_at: float = field(default_factory=time.time)
    finished_at: float = 0.0

    @property
    def progress(self) -> float:
        if self.status == Status.DONE:
            return 1.0
        if self.total_bytes > 0:
            return min(1.0, self.downloaded_bytes / self.total_bytes)
        return 0.0

    def to_dict(self) -> dict:
        d = asdict(self)
        d["kind"] = self.kind.value
        d["status"] = self.status.value
        d["progress"] = self.progress
        return d


def human_size(n: float) -> str:
    if not n:
        return "—"
    for unit in ("o", "Ko", "Mo", "Go", "To"):
        if abs(n) < 1024 or unit == "To":
            return f"{n:.0f} {unit}" if unit == "o" else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} To"


def human_time(s: float) -> str:
    if not s or s < 0:
        return "—"
    s = int(s)
    h, rem = divmod(s, 3600)
    m, sec = divmod(rem, 60)
    return f"{h}:{m:02d}:{sec:02d}" if h else f"{m}:{sec:02d}"

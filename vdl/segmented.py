"""Téléchargeur multi-segments façon IDM pour les fichiers classiques.

- Découpe le fichier en N plages HTTP (Range) téléchargées en parallèle.
- Découpage dynamique : quand une connexion termine sa plage, elle « vole »
  la moitié de la plus grosse plage restante, pour que toutes les connexions
  restent occupées jusqu'au bout.
- Pause/reprise : l'état des plages est sauvegardé dans « fichier.vdl.json ».
"""

from __future__ import annotations

import json
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import requests

CHUNK = 256 * 1024
MIN_SEGMENT = 1024 * 1024          # pas de plage plus petite que 1 Mo
MAX_RETRIES = 8
TIMEOUT = (10, 20)                 # (connexion, lecture) : une connexion muette est relancée vite

ProgressCb = Callable[[int, int, float], None]   # (téléchargé, total, vitesse o/s)


class Interrupted(Exception):
    """Levée quand le téléchargement est mis en pause ou annulé."""


@dataclass
class Segment:
    start: int
    end: int          # inclus
    done: int = 0

    @property
    def remaining(self) -> int:
        return self.end - self.start + 1 - self.done

    @property
    def position(self) -> int:
        return self.start + self.done


class SegmentedDownloader:
    def __init__(self, url: str, dest: Path, headers: dict | None = None, segments: int = 8,
                 progress: ProgressCb | None = None, stop: threading.Event | None = None):
        self.url = url
        self.dest = Path(dest)
        self.part = self.dest.with_name(self.dest.name + ".part")
        self.state_file = self.dest.with_name(self.dest.name + ".vdl.json")
        self.headers = headers or {}
        self.n_segments = max(1, segments)
        self.progress = progress
        self.stop = stop or threading.Event()
        self.total = 0
        self.segments: list[Segment] = []
        self._lock = threading.Lock()
        self._error: Exception | None = None
        self._busy: set[int] = set()      # plages en cours (id des Segment)
        self._single_done = 0
        self._session = requests.Session()
        self._session.headers.update(self.headers)

    # ------------------------------------------------------------------ API

    def run(self) -> Path:
        total, ranges_ok = self._inspect()
        self.total = total

        if total and ranges_ok:
            if not self._load_state(total):
                self._fresh_segments(total)
            self._run_parallel()
        else:
            self._run_single()

        if self.stop.is_set():
            self._save_state()
            raise Interrupted()
        if self._error:
            self._save_state()
            raise self._error

        self.part.replace(self.dest)
        self.state_file.unlink(missing_ok=True)
        return self.dest

    def cleanup(self) -> None:
        """Supprime les fichiers temporaires (après une annulation)."""
        self.part.unlink(missing_ok=True)
        self.state_file.unlink(missing_ok=True)

    # ------------------------------------------------------------------ préparation

    def _inspect(self) -> tuple[int, bool]:
        for attempt in range(3):
            try:
                r = self._session.get(self.url, headers={"Range": "bytes=0-0"}, stream=True,
                                      allow_redirects=True, timeout=TIMEOUT)
                r.close()
                break
            except requests.RequestException:
                if attempt == 2 or self.stop.wait(2 ** attempt):
                    raise
        r.raise_for_status()
        self.url = r.url    # suit les redirections une fois pour toutes
        if r.status_code == 206:
            cr = r.headers.get("Content-Range", "")
            if "/" in cr and cr.rsplit("/", 1)[1].isdigit():
                return int(cr.rsplit("/", 1)[1]), True
        cl = r.headers.get("Content-Length", "")
        return (int(cl) if cl.isdigit() else 0), False

    def _fresh_segments(self, total: int) -> None:
        n = max(1, min(self.n_segments, total // MIN_SEGMENT))
        size = total // n
        self.segments = [
            Segment(i * size, (total - 1) if i == n - 1 else (i + 1) * size - 1) for i in range(n)
        ]
        self.part.parent.mkdir(parents=True, exist_ok=True)
        with open(self.part, "wb") as f:
            f.truncate(total)

    def _load_state(self, total: int) -> bool:
        try:
            st = json.loads(self.state_file.read_text(encoding="utf-8"))
            if st.get("total") != total or not self.part.exists():
                return False
            self.segments = [Segment(*s) for s in st["segments"]]
            return True
        except (OSError, ValueError, KeyError, TypeError):
            return False

    def _save_state(self) -> None:
        if not self.segments:
            return
        with self._lock:
            data = {"url": self.url, "total": self.total,
                    "segments": [[s.start, s.end, s.done] for s in self.segments]}
        try:
            self.state_file.write_text(json.dumps(data), encoding="utf-8")
        except OSError:
            pass

    # ------------------------------------------------------------------ parallèle

    def _downloaded(self) -> int:
        with self._lock:
            return sum(s.done for s in self.segments)

    def _next_segment(self, current: Segment | None) -> Segment | None:
        """Plage suivante : une plage non commencée, sinon la moitié de la plus grosse."""
        with self._lock:
            busy = self._busy
            if current is not None:
                busy.discard(id(current))
            for s in self.segments:
                if s.remaining > 0 and id(s) not in busy:
                    busy.add(id(s))
                    return s
            biggest = max((s for s in self.segments if id(s) in busy), key=lambda s: s.remaining, default=None)
            if biggest is None or biggest.remaining < 2 * MIN_SEGMENT:
                return None
            mid = biggest.position + biggest.remaining // 2
            new = Segment(mid, biggest.end)
            biggest.end = mid - 1
            self.segments.append(new)
            busy.add(id(new))
            return new

    def _worker(self) -> None:
        seg = self._next_segment(None)
        while seg is not None and not self.stop.is_set() and self._error is None:
            try:
                self._download_segment(seg)
            except Exception as e:  # noqa: BLE001 — remonté au thread principal
                self._error = e
                return
            seg = self._next_segment(seg)

    def _download_segment(self, seg: Segment) -> None:
        retries = 0
        while seg.remaining > 0 and not self.stop.is_set():
            try:
                headers = {"Range": f"bytes={seg.position}-{seg.end}"}
                with self._session.get(self.url, headers=headers, stream=True, timeout=TIMEOUT) as r:
                    if r.status_code != 206:
                        raise requests.HTTPError(f"Le serveur a refusé la plage (HTTP {r.status_code})")
                    with open(self.part, "r+b") as f:
                        f.seek(seg.position)
                        for chunk in r.iter_content(CHUNK):
                            if self.stop.is_set():
                                return
                            allowed = seg.remaining          # la plage a pu être raccourcie
                            if allowed <= 0:
                                return
                            chunk = chunk[:allowed]
                            f.write(chunk)
                            with self._lock:
                                seg.done += len(chunk)
                            retries = 0
            except (requests.RequestException, OSError):
                retries += 1
                if retries > MAX_RETRIES:
                    raise
                self.stop.wait(min(15, 2 ** retries))

    def _run_parallel(self) -> None:
        # Autant de connexions que demandé : les plages manquantes naissent du découpage dynamique
        workers = [threading.Thread(target=self._worker, daemon=True) for _ in range(self.n_segments)]
        for w in workers:
            w.start()
        self._monitor(lambda: any(w.is_alive() for w in workers))

    # ------------------------------------------------------------------ simple flux

    def _run_single(self) -> None:
        """Serveur sans support des plages : un seul flux, reprise impossible."""
        self.segments = []
        done = 0
        finished = threading.Event()

        def run():
            nonlocal done
            try:
                with self._session.get(self.url, stream=True, timeout=TIMEOUT) as r:
                    r.raise_for_status()
                    self.part.parent.mkdir(parents=True, exist_ok=True)
                    with open(self.part, "wb") as f:
                        for chunk in r.iter_content(CHUNK):
                            if self.stop.is_set():
                                return
                            f.write(chunk)
                            done += len(chunk)
                            self._single_done = done
            except Exception as e:  # noqa: BLE001
                self._error = e
            finally:
                finished.set()

        t = threading.Thread(target=run, daemon=True)
        t.start()
        self._monitor(lambda: not finished.is_set(), single=True)
        if self.stop.is_set():
            self.part.unlink(missing_ok=True)   # rien à reprendre

    # ------------------------------------------------------------------ suivi

    def _monitor(self, alive: Callable[[], bool], single: bool = False) -> None:
        last_bytes = self._single_done if single else self._downloaded()
        last_t = time.monotonic()
        last_save = last_t
        speed = 0.0
        while alive():
            time.sleep(0.4)
            now = time.monotonic()
            cur = self._single_done if single else self._downloaded()
            inst = (cur - last_bytes) / max(1e-3, now - last_t)
            speed = inst if speed == 0 else 0.7 * speed + 0.3 * inst
            last_bytes, last_t = cur, now
            if self.progress:
                self.progress(cur, self.total, speed)
            if not single and now - last_save > 2:
                self._save_state()
                last_save = now
        if self.progress:
            self.progress(self._single_done if single else self._downloaded(), self.total, 0.0)

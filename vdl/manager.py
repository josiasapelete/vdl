"""Gestionnaire : file d'attente, exécution parallèle, pause/reprise, annulation, historique.

Utilisé à l'identique par la ligne de commande, l'interface graphique et le
serveur local (extension navigateur). Les interfaces lisent l'état via
`snapshot()` — aucun rappel inter-threads à gérer côté UI.
"""

from __future__ import annotations

import glob
import re
import threading
import time
from pathlib import Path
from typing import Callable

import yt_dlp

from .config import Settings
from .db import Database
from .extractor import (build_ydl_opts, filename_from_response, has_specific_extractor, has_video_stream, head,
                        request_headers)
from .models import Kind, Options, Status, Task
from .segmented import Interrupted, SegmentedDownloader

Listener = Callable[[Task], None]


class _Stop(Exception):
    """Levée depuis le hook de progression yt-dlp pour interrompre."""


def unique_path(path: Path) -> Path:
    if not path.exists() and not path.with_name(path.name + ".part").exists():
        return path
    stem, suffix = path.stem, path.suffix
    i = 1
    while True:
        cand = path.with_name(f"{stem} ({i}){suffix}")
        if not cand.exists() and not cand.with_name(cand.name + ".part").exists():
            return cand
        i += 1


class DownloadManager:
    def __init__(self, settings: Settings | None = None, db: Database | None = None):
        self.settings = settings or Settings.load()
        self.db = db or Database()
        self._lock = threading.RLock()
        self._tasks: dict[str, Task] = {}
        self._stops: dict[str, threading.Event] = {}
        self._cancel: set[str] = set()
        self._threads: dict[str, threading.Thread] = {}
        self._listeners: list[Listener] = []
        self._wake = threading.Event()
        self._closing = False
        self._restore()
        self._scheduler = threading.Thread(target=self._schedule_loop, daemon=True, name="vdl-scheduler")
        self._scheduler.start()

    # ------------------------------------------------------------------ API publique

    def add(self, url: str, options: Options | None = None, kind: Kind | None = None,
            title: str = "", start: bool = True) -> Task:
        options = options or Options(mode=self.settings.default_mode, quality=self.settings.default_quality,
                                     audio_bitrate=self.settings.audio_bitrate,
                                     subtitles=self.settings.subtitles,
                                     auto_subtitles=self.settings.auto_subtitles)
        if kind is None:
            from .extractor import detect_kind
            kind, _ = detect_kind(url, options)
        task = Task(url=url, kind=kind, options=options, title=title or url,
                    status=Status.QUEUED if start else Status.PAUSED)
        with self._lock:
            self._tasks[task.id] = task
        self.db.save(task)
        self._emit(task)
        self._wake.set()
        return task

    def pause(self, task_id: str) -> None:
        with self._lock:
            t = self._tasks.get(task_id)
            if not t:
                return
            if t.status == Status.QUEUED:
                t.status = Status.PAUSED
                self._persist(t)
            elif t.status == Status.RUNNING and task_id in self._stops:
                self._stops[task_id].set()

    def resume(self, task_id: str) -> None:
        with self._lock:
            t = self._tasks.get(task_id)
            if t and t.status in (Status.PAUSED, Status.ERROR, Status.CANCELED):
                t.status, t.error = Status.QUEUED, ""
                self._persist(t)
        self._wake.set()

    def cancel(self, task_id: str) -> None:
        with self._lock:
            t = self._tasks.get(task_id)
            if not t:
                return
            if task_id in self._stops and t.status in (Status.RUNNING, Status.PROCESSING):
                self._cancel.add(task_id)
                self._stops[task_id].set()
            elif t.status not in (Status.DONE,):
                t.status = Status.CANCELED
                self._cleanup_partial(t)
                self._persist(t)

    def remove(self, task_id: str, delete_file: bool = False) -> None:
        self.cancel(task_id)
        with self._lock:
            t = self._tasks.pop(task_id, None)
        if t:
            if delete_file and t.filepath:
                Path(t.filepath).unlink(missing_ok=True)
            self.db.delete(task_id)

    def pause_all(self) -> None:
        for tid in list(self._tasks):
            self.pause(tid)

    def resume_all(self) -> None:
        for t in self.snapshot():
            if t.status == Status.PAUSED:
                self.resume(t.id)

    def clear_finished(self) -> None:
        with self._lock:
            for tid, t in list(self._tasks.items()):
                if t.status in (Status.DONE, Status.ERROR, Status.CANCELED):
                    del self._tasks[tid]
        self.db.clear_finished()

    def get(self, task_id: str) -> Task | None:
        with self._lock:
            return self._tasks.get(task_id)

    def snapshot(self) -> list[Task]:
        with self._lock:
            return sorted(self._tasks.values(), key=lambda t: t.created_at)

    def subscribe(self, fn: Listener) -> None:
        self._listeners.append(fn)

    def wait_all(self, poll: float = 0.3) -> None:
        while any(t.status.active for t in self.snapshot()):
            time.sleep(poll)

    def shutdown(self) -> None:
        """Met en pause ce qui tourne (état sauvegardé) pour reprendre au prochain lancement."""
        self._closing = True
        with self._lock:
            running = list(self._stops.items())
        for _, ev in running:
            ev.set()
        for th in list(self._threads.values()):
            th.join(timeout=10)
        self._wake.set()

    # ------------------------------------------------------------------ interne

    def _restore(self) -> None:
        for t in self.db.all():
            if t.status in (Status.RUNNING, Status.PROCESSING):
                t.status = Status.PAUSED         # interrompu à la fermeture → reprise possible
                self.db.save(t)
            self._tasks[t.id] = t

    def _emit(self, t: Task) -> None:
        for fn in list(self._listeners):
            try:
                fn(t)
            except Exception:  # noqa: BLE001
                pass

    def _persist(self, t: Task) -> None:
        self.db.save(t)
        self._emit(t)

    def _schedule_loop(self) -> None:
        while not self._closing:
            self._wake.wait(1.0)
            self._wake.clear()
            if self._closing:
                break
            with self._lock:
                running = sum(1 for t in self._tasks.values() if t.status in (Status.RUNNING, Status.PROCESSING))
                free = max(0, int(self.settings.max_concurrent) - running)
                for t in sorted(self._tasks.values(), key=lambda t: t.created_at):
                    if free <= 0:
                        break
                    if t.status == Status.QUEUED and t.id not in self._threads:
                        t.status = Status.RUNNING
                        self._stops[t.id] = threading.Event()
                        th = threading.Thread(target=self._run, args=(t,), daemon=True, name=f"vdl-{t.id}")
                        self._threads[t.id] = th
                        th.start()
                        free -= 1

    def _run(self, t: Task) -> None:
        stop = self._stops[t.id]
        self._persist(t)
        try:
            outdir = Path(self.settings.download_dir).expanduser()
            outdir.mkdir(parents=True, exist_ok=True)
            if t.kind == Kind.DIRECT:
                self._run_direct(t, outdir, stop)
            else:
                self._run_ytdlp(t, outdir, stop)
            t.status, t.finished_at, t.speed, t.eta = Status.DONE, time.time(), 0, 0
            if t.total_bytes:
                t.downloaded_bytes = t.total_bytes
        except (Interrupted, _Stop):
            self._on_interrupt(t)
        except Exception as e:  # noqa: BLE001
            if stop.is_set():
                self._on_interrupt(t)
            else:
                t.status, t.speed = Status.ERROR, 0
                t.error = re.sub(r"^ERROR:\s*", "", str(e)).strip() or e.__class__.__name__
        finally:
            with self._lock:
                self._stops.pop(t.id, None)
                self._threads.pop(t.id, None)
                self._cancel.discard(t.id)
            self._persist(t)
            self._wake.set()

    def _on_interrupt(self, t: Task) -> None:
        t.speed, t.eta = 0, 0
        if t.id in self._cancel:
            t.status = Status.CANCELED
            self._cleanup_partial(t)
        else:
            t.status = Status.PAUSED

    def _cleanup_partial(self, t: Task) -> None:
        if not t.filepath:
            return
        p = Path(t.filepath)
        # yt-dlp : « titre [id].f137.mp4.part », « titre [id].f140.m4a.part »… → préfixe commun
        base = glob.escape(p.name if t.kind == Kind.DIRECT else re.sub(r"\.f[\w-]+$", "", p.stem))
        for pattern in (f"{base}*.part*", f"{base}*.vdl.json", f"{base}*.ytdl"):
            for f in p.parent.glob(pattern):
                f.unlink(missing_ok=True)

    # ---------------------------------------------------------------- moteur multi-segments

    def _run_direct(self, t: Task, outdir: Path, stop: threading.Event) -> None:
        if not t.filepath:
            r = head(t.url, t.options)
            name = filename_from_response(t.url, r)
            t.filepath = str(unique_path(outdir / name))
            t.title = name if t.title in ("", t.url) else t.title
            self._persist(t)

        def progress(done: int, total: int, speed: float) -> None:
            t.downloaded_bytes, t.total_bytes, t.speed = done, total, speed
            t.eta = (total - done) / speed if speed > 0 and total else 0
            self._emit(t)

        dl = SegmentedDownloader(t.url, Path(t.filepath), headers=request_headers(t.options),
                                 segments=int(self.settings.segments), progress=progress, stop=stop)
        dl.run()

    # ---------------------------------------------------------------- moteur yt-dlp

    def _run_ytdlp(self, t: Task, outdir: Path, stop: threading.Event) -> None:
        opts = build_ydl_opts(t.options, self.settings, outdir)
        if t.title and t.title != t.url and not has_specific_extractor(t.url):
            # Flux capté sur un site inconnu (« master.m3u8 ») : on nomme le fichier d'après la page
            safe = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", t.title).strip(" .")[:150] or "video"
            opts["outtmpl"] = {"default": str(outdir / f"{safe}.%(ext)s")}
        # Plusieurs fichiers (vidéo + audio) : on additionne pour une progression globale
        finished_bytes = {"sum": 0, "current": ""}

        def hook(d: dict) -> None:
            if stop.is_set():
                raise _Stop()
            info = d.get("info_dict") or {}
            if t.title in ("", t.url) and info.get("title"):
                t.title = info["title"]
            fname = d.get("filename") or ""
            if d["status"] == "downloading":
                t.status = Status.RUNNING
                total = d.get("total_bytes") or d.get("total_bytes_estimate") or 0
                done = d.get("downloaded_bytes") or 0
                t.total_bytes = int(finished_bytes["sum"] + total)
                t.downloaded_bytes = int(finished_bytes["sum"] + done)
                t.speed = float(d.get("speed") or 0)
                t.eta = float(d.get("eta") or 0)
                if fname != finished_bytes["current"]:
                    finished_bytes["current"] = fname
                    if not t.filepath:
                        t.filepath = str(Path(fname))
                        self.db.save(t)
                self._emit(t)
            elif d["status"] == "finished":
                finished_bytes["sum"] += int(d.get("total_bytes") or d.get("downloaded_bytes") or 0)
                t.downloaded_bytes = t.total_bytes = finished_bytes["sum"]
                t.status = Status.PROCESSING
                self._emit(t)

        def pp_hook(d: dict) -> None:
            if d.get("status") == "started":
                t.status = Status.PROCESSING
                self._emit(t)
            if d.get("status") == "finished":
                path = (d.get("info_dict") or {}).get("filepath")
                if path:
                    t.filepath = path

        opts["progress_hooks"] = [hook]
        opts["postprocessor_hooks"] = [pp_hook]

        with yt_dlp.YoutubeDL(opts) as ydl:
            info = ydl.extract_info(t.url, download=True)
            if info:
                info = ydl.sanitize_info(info)
                if has_specific_extractor(t.url) or t.title in ("", t.url):
                    t.title = info.get("title") or t.title
                final = (info.get("requested_downloads") or [{}])[-1].get("filepath") or info.get("filepath")
                if final:
                    t.filepath = final
        if stop.is_set():
            raise _Stop()
        # Garde-fou : un flux de piste seule (playlist HLS audio) donnerait un « MP4 » sans image
        if t.options.mode == "video" and t.filepath and has_video_stream(t.filepath) is False:
            raise RuntimeError("Le fichier ne contient que le son : le lien capté était la piste audio "
                               "du lecteur, pas la vidéo. Relancez la lecture quelques secondes puis réessayez.")

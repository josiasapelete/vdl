"""Ligne de commande.

    vdl get URL [URL…] [-a] [-q 1080] [--subs fr,en] [--playlist]
    vdl info URL
    vdl list | resume [ID|all] | update | serve | gui | config [clé valeur]
"""

from __future__ import annotations

import argparse
import os
import shutil
import sys
import time

from . import __version__
from .config import Settings
from .extractor import ProbeError, ProbeResult, probe
from .manager import DownloadManager
from .models import Kind, Options, Status, Task, human_size, human_time


# ------------------------------------------------------------------ affichage

def _enable_ansi() -> None:
    if sys.platform == "win32":
        os.system("")   # active les séquences ANSI dans la console Windows
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8")
        except (AttributeError, ValueError):
            pass


def _bar(p: float, width: int = 24) -> str:
    full = int(p * width)
    return "█" * full + "░" * (width - full)


def _line(t: Task) -> str:
    cols = shutil.get_terminal_size((100, 20)).columns
    title = (t.title or t.url)
    stats = f"{t.progress * 100:5.1f}% {human_size(t.downloaded_bytes):>9}/{human_size(t.total_bytes):<9}"
    if t.status == Status.RUNNING:
        stats += f" {human_size(t.speed)}/s ETA {human_time(t.eta)}"
    else:
        stats += f" {t.status.label}"
    stats = stats.ljust(58)
    room = max(10, cols - len(stats) - 27)
    title = title[: room - 1] + "…" if len(title) > room else title.ljust(room)
    return f"{title} {_bar(t.progress)} {stats}"[: cols - 1]


def _watch(manager: DownloadManager, ids: list[str]) -> None:
    """Affiche la progression jusqu'à la fin ; Ctrl+C met en pause (reprise : vdl resume)."""
    printed = 0
    try:
        while True:
            tasks = [t for t in manager.snapshot() if t.id in ids]
            if printed:
                sys.stdout.write(f"\x1b[{printed}F")
            for t in tasks:
                sys.stdout.write("\x1b[2K" + _line(t) + "\n")
            sys.stdout.flush()
            printed = len(tasks)
            if not any(t.status.active for t in tasks):
                break
            time.sleep(0.5)
    except KeyboardInterrupt:
        print("\nPause… (reprendre avec : vdl resume)")
        manager.shutdown()
        return
    for t in manager.snapshot():
        if t.id in ids:
            if t.status == Status.DONE:
                print(f"✔ {t.filepath}")
            elif t.status == Status.ERROR:
                print(f"✘ {t.title}: {t.error}")


def _ask(prompt: str, default: str = "") -> str:
    if not sys.stdin.isatty():
        return default
    try:
        ans = input(prompt).strip()
    except EOFError:
        return default
    return ans or default


# ------------------------------------------------------------------ commandes

def cmd_info(args, settings: Settings) -> int:
    try:
        r = probe(args.url, settings)
    except ProbeError as e:
        print(f"Erreur : {e}", file=sys.stderr)
        return 1
    _print_info(r)
    if r.formats:
        print(f"\n{'ID':<12}{'EXT':<6}{'RÉSO':<8}{'FPS':<5}{'VIDÉO':<14}{'AUDIO':<12}{'TAILLE':<10}NOTE")
        for f in r.formats:
            print(f"{f.format_id:<12}{f.ext:<6}{(str(f.height) + 'p') if f.height else '':<8}"
                  f"{int(f.fps) if f.fps else '':<5}{f.vcodec[:13]:<14}{f.acodec[:11]:<12}"
                  f"{human_size(f.filesize):<10}{f.note[:30]}")
    return 0


def _print_info(r: ProbeResult) -> None:
    print(f"Titre      : {r.title}")
    print(f"Moteur     : {'multi-segments (fichier direct)' if r.kind == Kind.DIRECT else 'yt-dlp — ' + r.extractor}")
    if r.uploader:
        print(f"Auteur     : {r.uploader}")
    if r.duration:
        print(f"Durée      : {human_time(r.duration)}")
    if r.filesize:
        print(f"Taille     : {human_size(r.filesize)}")
    if r.is_playlist:
        print(f"Playlist   : {len(r.entries)} éléments")
    elif r.qualities and r.kind == Kind.YTDLP:
        print(f"Qualités   : {', '.join(q if q == 'best' else q + 'p' for q in r.qualities)}")


def _options_from_args(args, settings: Settings) -> Options:
    return Options(
        mode="audio" if args.audio else settings.default_mode,
        quality=args.quality or settings.default_quality,
        format_id=args.format or "",
        audio_bitrate=args.bitrate or settings.audio_bitrate,
        subtitles=args.subs if args.subs is not None else settings.subtitles,
        auto_subtitles=args.auto_subs or settings.auto_subtitles,
        referer=args.referer or "",
    )


def cmd_get(args, settings: Settings) -> int:
    if args.output:
        settings.download_dir = args.output
    if args.segments:
        settings.segments = args.segments
    manager = DownloadManager(settings)
    ids: list[str] = []

    for url in args.urls:
        print(f"Analyse de {url} …")
        opts = _options_from_args(args, settings)
        try:
            r = probe(url, settings, opts)
        except ProbeError as e:
            print(f"  Erreur : {e}", file=sys.stderr)
            continue

        if r.is_playlist:
            print(f"  Playlist « {r.title} » : {len(r.entries)} éléments")
            take_all = args.playlist
            if not take_all and r.single_video is not None:
                take_all = _ask("  Télécharger toute la playlist ? [o/N] ", "n").lower().startswith("o")
                if not take_all:
                    r = r.single_video
            elif not take_all:
                take_all = _ask("  Tout télécharger ? [O/n] ", "o").lower().startswith("o")
                if not take_all:
                    continue
            if take_all:
                for e in r.entries:
                    ids.append(manager.add(e.url, Options(**opts.__dict__), Kind.YTDLP, e.title).id)
                continue

        _print_info(r)
        opts.referer = opts.referer or r.referer
        opts.impersonate = r.impersonate
        if r.kind == Kind.YTDLP and opts.mode == "video" and not args.quality and not args.format \
                and len(r.qualities) > 1 and sys.stdin.isatty():
            choices = [q if q == "best" else q + "p" for q in r.qualities]
            ans = _ask(f"  Qualité [{'/'.join(choices)}, a = audio MP3] (défaut best) : ", "best").lower()
            if ans in ("a", "audio", "mp3"):
                opts.mode = "audio"
            else:
                opts.quality = ans.rstrip("p") if ans.rstrip("p") in r.qualities else "best"
        ids.append(manager.add(r.url if r.kind == Kind.YTDLP else url, opts, r.kind, r.title).id)

    if not ids:
        return 1
    _watch(manager, ids)
    return 0 if all(manager.get(i) and manager.get(i).status == Status.DONE for i in ids) else 1


def cmd_list(args, settings: Settings) -> int:
    manager = DownloadManager(settings)
    tasks = manager.snapshot()
    if not tasks:
        print("Aucun téléchargement.")
        return 0
    for t in tasks:
        print(f"{t.id}  {t.status.label:<14} {human_size(t.total_bytes):>9}  {(t.title or t.url)[:70]}")
        if t.status == Status.ERROR:
            print(f"{'':14}↳ {t.error[:100]}")
    return 0


def cmd_resume(args, settings: Settings) -> int:
    manager = DownloadManager(settings)
    targets = [t for t in manager.snapshot() if t.status in (Status.PAUSED, Status.ERROR)
               and (args.id in (None, "all") or t.id == args.id)]
    if not targets:
        print("Rien à reprendre.")
        return 0
    for t in targets:
        manager.resume(t.id)
    _watch(manager, [t.id for t in targets])
    return 0


def cmd_update(args, settings: Settings) -> int:
    from .updater import update
    _, msg = update(force=True)
    print(msg)
    return 0


def cmd_serve(args, settings: Settings) -> int:
    from .server import LocalServer
    manager = DownloadManager(settings)
    srv = LocalServer(manager, settings.server_port).start()
    print(f"Serveur VDL sur http://127.0.0.1:{settings.server_port} — Ctrl+C pour arrêter")
    try:
        last = {}
        while True:
            for t in manager.snapshot():
                if last.get(t.id) != t.status:
                    last[t.id] = t.status
                    print(f"[{t.status.label}] {t.title or t.url}" + (f" — {t.error}" if t.error else ""))
            time.sleep(1)
    except KeyboardInterrupt:
        srv.stop()
        manager.shutdown()
    return 0


def cmd_config(args, settings: Settings) -> int:
    if args.key is None:
        for k, v in settings.__dict__.items():
            if not k.startswith("_"):
                print(f"{k:<22} = {v!r}")
        return 0
    if not hasattr(settings, args.key) or args.key.startswith("_"):
        print(f"Clé inconnue : {args.key}", file=sys.stderr)
        return 1
    cur = getattr(settings, args.key)
    val: object = args.value
    if isinstance(cur, bool):
        val = str(args.value).lower() in ("1", "true", "oui", "yes", "on")
    elif isinstance(cur, int):
        val = int(args.value)
    setattr(settings, args.key, val)
    settings.save()
    print(f"{args.key} = {val!r}")
    return 0


def cmd_gui(args, settings: Settings) -> int:
    from .gui.app import main as gui_main
    return gui_main()


# ------------------------------------------------------------------ point d'entrée

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="vdl", description="Téléchargeur vidéo et fichiers (yt-dlp + multi-segments)")
    p.add_argument("--version", action="version", version=f"VDL {__version__}")
    sub = p.add_subparsers(dest="cmd")

    g = sub.add_parser("get", help="télécharger une ou plusieurs URL")
    g.add_argument("urls", nargs="+")
    g.add_argument("-a", "--audio", action="store_true", help="extraire l'audio en MP3")
    g.add_argument("-q", "--quality", help="hauteur max : 2160, 1080, 720… ou best")
    g.add_argument("-f", "--format", help="sélecteur de format yt-dlp brut (ex. 137+140)")
    g.add_argument("-b", "--bitrate", help="débit MP3 en kbps (défaut 192)")
    g.add_argument("--subs", help="langues de sous-titres, ex. fr,en")
    g.add_argument("--auto-subs", action="store_true", help="inclure les sous-titres automatiques")
    g.add_argument("--playlist", action="store_true", help="télécharger toute la playlist sans demander")
    g.add_argument("-o", "--output", help="dossier de destination")
    g.add_argument("-n", "--segments", type=int, help="connexions par fichier direct (défaut 8)")
    g.add_argument("--referer", help="en-tête Referer à envoyer")
    g.set_defaults(fn=cmd_get)

    i = sub.add_parser("info", help="analyser une URL sans télécharger")
    i.add_argument("url")
    i.set_defaults(fn=cmd_info)

    sub.add_parser("list", help="file d'attente et historique").set_defaults(fn=cmd_list)

    r = sub.add_parser("resume", help="reprendre les téléchargements en pause")
    r.add_argument("id", nargs="?", help="ID d'une tâche, ou all (défaut)")
    r.set_defaults(fn=cmd_resume)

    sub.add_parser("update", help="mettre à jour yt-dlp").set_defaults(fn=cmd_update)
    sub.add_parser("serve", help="serveur local pour l'extension, sans interface").set_defaults(fn=cmd_serve)
    sub.add_parser("gui", help="ouvrir l'interface graphique").set_defaults(fn=cmd_gui)

    c = sub.add_parser("config", help="afficher ou modifier les réglages")
    c.add_argument("key", nargs="?")
    c.add_argument("value", nargs="?")
    c.set_defaults(fn=cmd_config)
    return p


def main(argv: list[str] | None = None) -> int:
    _enable_ansi()
    parser = build_parser()
    args = parser.parse_args(argv)
    if not getattr(args, "fn", None):
        parser.print_help()
        return 0
    settings = Settings.load()
    if args.cmd in ("get", "serve") and settings.auto_update_ytdlp:
        from .updater import update
        updated, msg = update()
        if updated:
            print(msg)
    return args.fn(args, settings)


if __name__ == "__main__":
    sys.exit(main())

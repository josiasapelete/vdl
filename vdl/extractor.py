"""Analyse d'une URL : quel moteur utiliser, quelles qualités, playlist ou non.

- Site connu de yt-dlp (YouTube, TikTok, Facebook…) ou page HTML  → yt-dlp
- Flux HLS (.m3u8) / DASH (.mpd)                                   → yt-dlp
- Fichier classique (zip, exe, mp4 direct, pdf…)                   → multi-segments
"""

from __future__ import annotations

import functools
import re
import shutil
import sys
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import unquote, urljoin, urlparse

import requests
import yt_dlp

from .config import Settings
from .models import Kind, Options

DEFAULT_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/130.0 Safari/537.36"
)
STREAM_TYPES = ("mpegurl", "dash+xml")
STREAM_EXT = re.compile(r"\.(m3u8|mpd)(\?|$)", re.I)
STANDARD_QUALITIES = ["best", "2160", "1440", "1080", "720", "480", "360"]

# Repli « n'importe quel site » : liens média et lecteurs intégrés trouvés dans le HTML
MEDIA_IN_PAGE = re.compile(r"""https?:(?:\\?/){2}[^"'\s<>()]+?\.(?:m3u8|mpd|mp4|webm|m4v|mov|mkv)(?:\?[^"'\s<>()]*)?""", re.I)
IFRAME_SRC = re.compile(r"""<iframe[^>]+?\b(?:data-src|data-lazy-src|src)\s*=\s*["']([^"']+)["']""", re.I)
NOISE = re.compile(r"doubleclick|googlesyndication|googletagmanager|google\.com/recaptcha|facebook\.com/plugins|"
                   r"disqus|twitter\.com/widgets|platform\.twitter|about:blank|javascript:|/ads?/|adserver|analytics", re.I)
MAX_CANDIDATES = 8
NOT_FOUND_MSG = ("Aucune vidéo trouvée directement dans cette page (le lecteur est sans doute chargé par "
                 "JavaScript).\n\nOuvrez la page dans votre navigateur et lancez la lecture : l'extension VDL "
                 "détectera le flux et affichera « Télécharger cette vidéo ».")


class ProbeError(Exception):
    pass


class _QuietLogger:
    """yt-dlp écrit ses erreurs sur stderr même en mode quiet : on les remonte nous-mêmes."""
    def debug(self, msg): pass
    def info(self, msg): pass
    def warning(self, msg): pass
    def error(self, msg): pass


@dataclass
class FormatInfo:
    format_id: str
    ext: str
    height: int
    fps: float
    vcodec: str
    acodec: str
    filesize: int
    note: str

    @property
    def has_video(self) -> bool:
        return self.vcodec not in ("none", "")

    @property
    def has_audio(self) -> bool:
        return self.acodec not in ("none", "")


@dataclass
class PlaylistEntry:
    url: str
    title: str


@dataclass
class ProbeResult:
    url: str
    kind: Kind
    title: str = ""
    extractor: str = ""
    duration: float = 0
    thumbnail: str = ""
    uploader: str = ""
    filesize: int = 0
    qualities: list[str] = field(default_factory=lambda: list(STANDARD_QUALITIES))
    formats: list[FormatInfo] = field(default_factory=list)
    is_playlist: bool = False
    entries: list[PlaylistEntry] = field(default_factory=list)
    single_video: "ProbeResult | None" = None   # si l'URL désigne aussi une vidéo précise
    referer: str = ""                           # page d'origine à renvoyer au serveur (lecteurs intégrés)
    impersonate: bool = False                   # le serveur n'accepte que les clients « Chrome »


# --------------------------------------------------------------------------- utilitaires

def ffmpeg_location() -> str | None:
    """ffmpeg livré avec l'application (dossier « ffmpeg » à côté de l'exécutable), sinon celui du PATH."""
    if getattr(sys, "frozen", False):
        exe = "ffmpeg.exe" if sys.platform == "win32" else "ffmpeg"
        bundled = Path(sys.executable).resolve().parent / "ffmpeg" / exe
        if bundled.is_file():
            return str(bundled.parent)
    return shutil.which("ffmpeg")


SAFE_HEADER = re.compile(r"^(referer|origin|cookie|user-agent|authorization|accept-language|x-[\w-]+)$", re.I)


def has_video_stream(path: str) -> bool | None:
    """True/False selon que le fichier contient une piste vidéo ; None si ffprobe est indisponible."""
    import subprocess
    folder = ffmpeg_location()
    exe = "ffprobe.exe" if sys.platform == "win32" else "ffprobe"
    probe_exe = str(Path(folder) / exe) if folder and Path(folder).is_dir() else shutil.which("ffprobe")
    if not probe_exe or not Path(path).is_file():
        return None
    try:
        out = subprocess.run([probe_exe, "-v", "error", "-select_streams", "v", "-show_entries",
                              "stream=codec_type", "-of", "csv=p=0", path],
                             capture_output=True, text=True, timeout=60,
                             creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    except (OSError, subprocess.SubprocessError):
        return None
    if out.returncode != 0:
        return None
    return any(line.strip() == "video" for line in out.stdout.splitlines())


def request_headers(opts: Options | None = None) -> dict:
    h = {"User-Agent": (opts and opts.user_agent) or DEFAULT_UA}
    if opts and opts.referer:
        h["Referer"] = opts.referer
        # Les lecteurs (hls.js…) chargent les flux en XHR : les hébergeurs vérifient souvent Origin
        ref = urlparse(opts.referer)
        if ref.scheme and ref.netloc:
            h["Origin"] = f"{ref.scheme}://{ref.netloc}"
    # En-têtes exacts envoyés par le navigateur pour ce flux (capturés par l'extension)
    for name, value in ((opts.headers if opts else None) or {}).items():
        if isinstance(name, str) and isinstance(value, str) and SAFE_HEADER.match(name):
            for k in [k for k in h if k.lower() == name.lower()]:
                del h[k]
            h["-".join(part.capitalize() for part in name.split("-"))] = value
    return h


def can_impersonate() -> bool:
    try:
        import curl_cffi  # noqa: F401
        return True
    except ImportError:
        return False


def _impersonate_target():
    from yt_dlp.networking.impersonate import ImpersonateTarget
    return ImpersonateTarget("chrome")


@functools.lru_cache(maxsize=1)
def _extractors():
    return [ie for ie in yt_dlp.extractor.gen_extractor_classes() if ie.ie_key() != "Generic"]


def pick_stream(url: str, alternatives: list[str] | None, opts: Options | None = None) -> str:
    """Parmi les playlists HLS captées, retourne la playlist maître (#EXT-X-STREAM-INF :
    elle référence vidéo ET audio). Une playlist de piste seule donnerait un fichier sans image."""
    candidates = [u for u in dict.fromkeys([url, *(alternatives or [])])
                  if isinstance(u, str) and re.search(r"\.m3u8(\?|$)|mpegurl", u, re.I)]
    if len(candidates) < 2 and not (candidates and alternatives):
        return url
    headers = request_headers(opts)
    for cand in candidates[:15]:
        try:
            text = requests.get(cand, headers=headers, timeout=10).text[:200_000]
        except requests.RequestException:
            continue
        if "#EXT-X-STREAM-INF" in text:
            return cand
    return url


def has_specific_extractor(url: str) -> bool:
    return any(ie.suitable(url) for ie in _extractors())


def filename_from_response(url: str, resp: requests.Response | None) -> str:
    name = ""
    if resp is not None:
        cd = resp.headers.get("Content-Disposition", "")
        m = re.search(r"filename\*\s*=\s*[^']*''([^;]+)", cd) or re.search(r'filename\s*=\s*"?([^";]+)"?', cd)
        if m:
            name = unquote(m.group(1)).strip()
    if not name:
        name = unquote(Path(urlparse(resp.url if resp is not None else url).path).name)
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name).strip(" .")
    return name or "telechargement"


def head(url: str, opts: Options | None = None) -> requests.Response | None:
    """HEAD, ou GET Range 0-0 si le serveur refuse HEAD. Ne lit pas le corps."""
    headers = request_headers(opts)
    try:
        r = requests.head(url, headers=headers, allow_redirects=True, timeout=15)
        if r.status_code < 400:
            return r
        r = requests.get(url, headers={**headers, "Range": "bytes=0-0"}, stream=True,
                         allow_redirects=True, timeout=15)
        r.close()
        return r if r.status_code < 400 else None
    except requests.RequestException:
        return None


def detect_kind(url: str, opts: Options | None = None) -> tuple[Kind, requests.Response | None]:
    if has_specific_extractor(url) or STREAM_EXT.search(url):
        return Kind.YTDLP, None
    r = head(url, opts)
    if r is None:
        return Kind.YTDLP, None
    ctype = r.headers.get("Content-Type", "").lower()
    if not ctype or "text/html" in ctype or any(t in ctype for t in STREAM_TYPES):
        return Kind.YTDLP, r
    return Kind.DIRECT, r


def total_size(r: requests.Response | None) -> int:
    if r is None:
        return 0
    cr = r.headers.get("Content-Range", "")
    if "/" in cr and cr.rsplit("/", 1)[1].isdigit():
        return int(cr.rsplit("/", 1)[1])
    cl = r.headers.get("Content-Length", "")
    return int(cl) if cl.isdigit() and r.status_code != 206 else 0


# --------------------------------------------------------------------------- options yt-dlp

def build_ydl_opts(opts: Options, settings: Settings, outdir: Path) -> dict:
    """Traduit nos options (mode, qualité, sous-titres…) en options yt-dlp."""
    o: dict = {
        "outtmpl": {"default": str(outdir / "%(title).150B [%(id)s].%(ext)s")},
        "noplaylist": True,
        "quiet": True,
        "no_warnings": True,
        "noprogress": True,
        "logger": _QuietLogger(),
        "continuedl": True,
        "windowsfilenames": True,
        "retries": 10,
        "fragment_retries": 10,
        "concurrent_fragment_downloads": max(1, int(settings.fragment_threads)),
        "http_headers": request_headers(opts),
        "postprocessors": [],
    }
    if loc := ffmpeg_location():
        o["ffmpeg_location"] = loc
    if settings.cookies_from_browser:
        o["cookiesfrombrowser"] = (settings.cookies_from_browser,)
    if opts.impersonate and can_impersonate():
        o["impersonate"] = _impersonate_target()

    if opts.mode == "audio":
        o["format"] = "bestaudio/best"
        o["postprocessors"] += [
            {"key": "FFmpegExtractAudio", "preferredcodec": "mp3", "preferredquality": str(opts.audio_bitrate)},
            {"key": "FFmpegMetadata", "add_metadata": True},
        ]
    else:
        if opts.format_id:
            o["format"] = opts.format_id
        else:
            o["format"] = "bv*+ba/b"
            # Résolution d'abord ; à résolution égale, H.264/AAC en MP4 (lisible partout)
            sort = ["res" if opts.quality in ("", "best") else f"res:{opts.quality}", "fps",
                    "vcodec:h264", "acodec:aac", "ext:mp4:m4a"]
            o["format_sort"] = sort
        o["merge_output_format"] = "mp4/mkv"
        o["postprocessors"].append({"key": "FFmpegMetadata", "add_metadata": True})

    langs = [l.strip() for l in (opts.subtitles or "").split(",") if l.strip()]
    if langs:
        o["writesubtitles"] = True
        o["writeautomaticsub"] = bool(opts.auto_subtitles)
        o["subtitleslangs"] = langs
        if opts.mode != "audio":
            o["postprocessors"].append({"key": "FFmpegEmbedSubtitle", "already_have_subtitle": False})
    return o


# --------------------------------------------------------------------------- analyse

def _probe_opts(settings: Settings, opts: Options | None, noplaylist: bool, impersonate: bool = False) -> dict:
    o = {
        "quiet": True,
        "no_warnings": True,
        "skip_download": True,
        "logger": _QuietLogger(),
        "extract_flat": "in_playlist",
        "noplaylist": noplaylist,
        "http_headers": request_headers(opts),
    }
    if settings.cookies_from_browser:
        o["cookiesfrombrowser"] = (settings.cookies_from_browser,)
    if impersonate:
        o["impersonate"] = _impersonate_target()
    return o


FORBIDDEN_MSG = ("Le serveur vidéo refuse l'accès (HTTP 403).\n\nLe lien du flux a sans doute expiré ou est lié "
                 "à la session du navigateur : rechargez la page, relancez la lecture quelques secondes, "
                 "puis cliquez à nouveau sur « Télécharger cette vidéo ».")


def _extract(url: str, settings: Settings, opts: Options | None, noplaylist: bool) -> dict:
    """Analyse via yt-dlp. Sur un refus 403, réessaie en imitant Chrome (empreinte TLS) ;
    le résultat porte alors « _vdl_impersonate » pour que le téléchargement fasse de même."""
    attempts = [bool(opts and opts.impersonate)]
    if not attempts[0] and can_impersonate():
        attempts.append(True)
    last = ""
    for imp in attempts:
        try:
            with yt_dlp.YoutubeDL(_probe_opts(settings, opts, noplaylist, imp)) as ydl:
                info = ydl.sanitize_info(ydl.extract_info(url, download=False))
                info["_vdl_impersonate"] = imp
                return info
        except yt_dlp.utils.DownloadError as e:
            last = re.sub(r"^ERROR:\s*", "", str(e))
            if not re.search(r"\b(403|401|429)\b|Forbidden|Cloudflare|impersonat", last, re.I):
                break
    if re.search(r"\b403\b|Forbidden", last):
        raise ProbeError(FORBIDDEN_MSG)
    raise ProbeError(last)


def _video_result(url: str, info: dict) -> ProbeResult:
    formats = []
    for f in info.get("formats") or []:
        if f.get("format_note") == "storyboard" or f.get("ext") == "mhtml":
            continue
        formats.append(FormatInfo(
            format_id=str(f.get("format_id", "")),
            ext=f.get("ext") or "",
            height=int(f.get("height") or 0),
            fps=float(f.get("fps") or 0),
            vcodec=f.get("vcodec") or ("none" if f.get("video_ext") == "none" else ""),
            acodec=f.get("acodec") or ("none" if f.get("audio_ext") == "none" else ""),
            filesize=int(f.get("filesize") or f.get("filesize_approx") or 0),
            note=f.get("format_note") or f.get("format") or "",
        ))
    heights = sorted({f.height for f in formats if f.height and f.has_video}, reverse=True)
    qualities = ["best"] + [str(h) for h in heights] if heights else ["best"]
    return ProbeResult(
        impersonate=bool(info.get("_vdl_impersonate")),
        url=info.get("webpage_url") or url,
        kind=Kind.YTDLP,
        title=info.get("title") or "",
        extractor=info.get("extractor_key") or info.get("extractor") or "",
        duration=float(info.get("duration") or 0),
        thumbnail=info.get("thumbnail") or "",
        uploader=info.get("uploader") or "",
        filesize=int(info.get("filesize") or info.get("filesize_approx") or 0),
        qualities=qualities,
        formats=formats,
    )


def probe(url: str, settings: Settings | None = None, opts: Options | None = None,
          _depth: int = 0) -> ProbeResult:
    settings = settings or Settings.load()
    url = url.strip()
    if not re.match(r"^https?://", url, re.I):
        raise ProbeError("L'adresse doit commencer par http:// ou https://")

    kind, resp = detect_kind(url, opts)
    if kind == Kind.DIRECT:
        return ProbeResult(url=url, kind=Kind.DIRECT, title=filename_from_response(url, resp),
                           filesize=total_size(resp), qualities=[], referer=opts.referer if opts else "")

    try:
        info = _extract(url, settings, opts, noplaylist=False)
    except ProbeError as e:
        if _depth == 0 and "Unsupported URL" in str(e):
            return _scan_page(url, settings, opts)
        raise
    if info.get("_type") in ("playlist", "multi_video"):
        entries = [
            PlaylistEntry(url=e.get("url") or e.get("webpage_url") or "", title=e.get("title") or "")
            for e in info.get("entries") or [] if e
        ]
        res = ProbeResult(url=url, kind=Kind.YTDLP, title=info.get("title") or "Playlist",
                          impersonate=bool(info.get("_vdl_impersonate")),
                          extractor=info.get("extractor_key") or "", is_playlist=True,
                          entries=[e for e in entries if e.url])
        # L'URL vise peut-être aussi une vidéo précise (ex. watch?v=…&list=…)
        try:
            single = _extract(url, settings, opts, noplaylist=True)
            if single.get("_type") not in ("playlist", "multi_video"):
                res.single_video = _video_result(url, single)
        except ProbeError:
            pass
        return res
    res = _video_result(url, info)
    res.referer = opts.referer if opts else ""
    return res


def _scan_page(url: str, settings: Settings, opts: Options | None) -> ProbeResult:
    """Repli pour les sites inconnus de yt-dlp : cherche dans le HTML les liens vidéo
    directs et les lecteurs intégrés (iframes), puis analyse chacun."""
    try:
        r = requests.get(url, headers=request_headers(opts), timeout=20)
        html = r.text[:3_000_000]
        page_title = re.search(r"<title[^>]*>([^<]+)</title>", html, re.I)
    except requests.RequestException:
        raise ProbeError(NOT_FOUND_MSG) from None

    candidates: list[str] = []
    for m in MEDIA_IN_PAGE.finditer(html):
        candidates.append(m.group(0).replace("\\/", "/"))
    for m in IFRAME_SRC.finditer(html):
        src = m.group(1).strip()
        if src.startswith("//"):
            src = "https:" + src
        candidates.append(urljoin(r.url, src))
    seen: set[str] = set()
    candidates = [c for c in candidates
                  if c.startswith("http") and not NOISE.search(c) and c != url and not (c in seen or seen.add(c))]

    sub_opts = Options(**{**(opts.__dict__ if opts else {}), "referer": url})
    title = page_title.group(1).strip() if page_title else ""
    for cand in candidates[:MAX_CANDIDATES]:
        try:
            res = probe(cand, settings, sub_opts, _depth=1)
        except ProbeError:
            continue
        if res.kind == Kind.DIRECT and not re.search(r"\.(mp4|webm|m4v|mov|mkv)(\?|$)", res.url + res.title, re.I):
            continue    # fichier quelconque (image, script…) : pas une vidéo
        res.referer = url
        if title and (res.kind == Kind.DIRECT or not res.title or res.title.lower() in ("video", "index")):
            res.title = title
        return res
    raise ProbeError(NOT_FOUND_MSG)

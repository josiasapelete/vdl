"""Petit serveur HTTP local (127.0.0.1) qui reçoit les liens de l'extension navigateur.

Sécurité : n'importe quelle page web peut tenter d'appeler localhost. On
n'accepte donc que les requêtes sans en-tête Origin (outils locaux) ou venant
d'une extension (chrome-extension://, moz-extension://…), et on exige un
en-tête personnalisé, ce qui force un pré-contrôle CORS que nous refusons aux sites.
"""

from __future__ import annotations

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable

from . import __version__
from .manager import DownloadManager
from .models import Options

EXT_ORIGINS = ("chrome-extension://", "moz-extension://", "safari-web-extension://", "extension://")
CLIENT_HEADER = "X-VDL-Client"

# Appelé quand l'extension envoie un lien. Retourne True si traité (ex. dialogue affiché par la GUI).
RequestHandler = Callable[[dict], bool]


def make_handler(manager: DownloadManager, on_request: RequestHandler | None,
                 on_show: Callable[[], None] | None = None):
    class Handler(BaseHTTPRequestHandler):
        server_version = "VDL"

        def log_message(self, *args):  # silencieux
            pass

        def _origin_ok(self) -> bool:
            origin = self.headers.get("Origin")
            return origin is None or origin.startswith(EXT_ORIGINS)

        def _send(self, code: int, payload: dict | list) -> None:
            body = json.dumps(payload, ensure_ascii=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            origin = self.headers.get("Origin")
            if origin and origin.startswith(EXT_ORIGINS):
                self.send_header("Access-Control-Allow-Origin", origin)
            self.end_headers()
            self.wfile.write(body)

        def do_OPTIONS(self):
            if not self._origin_ok():
                self.send_response(403)
                self.end_headers()
                return
            self.send_response(204)
            self.send_header("Access-Control-Allow-Origin", self.headers.get("Origin", ""))
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", f"Content-Type, {CLIENT_HEADER}")
            self.end_headers()

        def do_GET(self):
            if not self._origin_ok() or not self.headers.get(CLIENT_HEADER):
                return self._send(403, {"error": "forbidden"})
            if self.path == "/show" and on_show:     # 2e lancement : afficher la fenêtre existante
                on_show()
                return self._send(200, {"status": "shown"})
            if self.path == "/ping":
                from yt_dlp.version import __version__ as ytdlp_version
                return self._send(200, {"app": "VDL", "version": __version__, "ytdlp": ytdlp_version})
            if self.path == "/tasks":
                return self._send(200, [t.to_dict() for t in manager.snapshot()])
            self._send(404, {"error": "not found"})

        def do_POST(self):
            if not self._origin_ok() or not self.headers.get(CLIENT_HEADER):
                return self._send(403, {"error": "forbidden"})
            if self.path != "/download":
                return self._send(404, {"error": "not found"})
            try:
                length = min(int(self.headers.get("Content-Length", 0)), 1_000_000)
                data = json.loads(self.rfile.read(length) or b"{}")
                url = str(data.get("url", "")).strip()
                if not url.lower().startswith(("http://", "https://")):
                    return self._send(400, {"error": "URL invalide"})
            except (ValueError, TypeError):
                return self._send(400, {"error": "JSON invalide"})

            headers = data.get("headers")
            data["headers"] = {str(k)[:100]: str(v)[:8000] for k, v in headers.items()} \
                if isinstance(headers, dict) and len(headers) <= 50 else {}
            if on_request and on_request(data):
                return self._send(200, {"status": "prompted"})
            opts = Options(
                mode=data.get("mode") or manager.settings.default_mode,
                quality=str(data.get("quality") or manager.settings.default_quality),
                subtitles=manager.settings.subtitles,
                auto_subtitles=manager.settings.auto_subtitles,
                audio_bitrate=manager.settings.audio_bitrate,
                referer=data.get("referer") or "",
                user_agent=data.get("userAgent") or "",
                headers=data["headers"],
            )
            alternatives = [a for a in data.get("alternatives") or [] if isinstance(a, str)][:15]

            def add():
                from .extractor import pick_stream
                manager.add(pick_stream(url, alternatives, opts), opts, title=data.get("title") or "")

            threading.Thread(target=add, daemon=True).start()
            self._send(200, {"status": "queued"})

    return Handler


class LocalServer:
    def __init__(self, manager: DownloadManager, port: int, on_request: RequestHandler | None = None,
                 on_show: Callable[[], None] | None = None):
        self.httpd = ThreadingHTTPServer(("127.0.0.1", port), make_handler(manager, on_request, on_show))
        self.httpd.daemon_threads = True
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True, name="vdl-server")

    def start(self) -> "LocalServer":
        self.thread.start()
        return self

    def stop(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()

"""Persistance SQLite : file d'attente et historique."""

from __future__ import annotations

import json
import sqlite3
import threading
from pathlib import Path

from .config import data_dir
from .models import Kind, Options, Status, Task

SCHEMA = """
CREATE TABLE IF NOT EXISTS tasks (
    id TEXT PRIMARY KEY,
    url TEXT NOT NULL,
    kind TEXT NOT NULL,
    options TEXT NOT NULL,
    title TEXT,
    status TEXT NOT NULL,
    filepath TEXT,
    total_bytes INTEGER DEFAULT 0,
    downloaded_bytes INTEGER DEFAULT 0,
    error TEXT,
    created_at REAL,
    finished_at REAL
);
"""


class Database:
    def __init__(self, path: Path | None = None):
        self.path = path or data_dir() / "vdl.sqlite3"
        self._lock = threading.Lock()
        self._conn = sqlite3.connect(self.path, check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        self._conn.execute(SCHEMA)
        self._conn.commit()

    def save(self, t: Task) -> None:
        with self._lock:
            self._conn.execute(
                """INSERT INTO tasks (id, url, kind, options, title, status, filepath,
                       total_bytes, downloaded_bytes, error, created_at, finished_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                   ON CONFLICT(id) DO UPDATE SET
                       title=excluded.title, status=excluded.status, filepath=excluded.filepath,
                       total_bytes=excluded.total_bytes, downloaded_bytes=excluded.downloaded_bytes,
                       error=excluded.error, finished_at=excluded.finished_at, options=excluded.options""",
                (t.id, t.url, t.kind.value, json.dumps(t.options.__dict__), t.title, t.status.value,
                 t.filepath, t.total_bytes, t.downloaded_bytes, t.error, t.created_at, t.finished_at),
            )
            self._conn.commit()

    def delete(self, task_id: str) -> None:
        with self._lock:
            self._conn.execute("DELETE FROM tasks WHERE id=?", (task_id,))
            self._conn.commit()

    def clear_finished(self) -> None:
        with self._lock:
            self._conn.execute("DELETE FROM tasks WHERE status IN ('done','error','canceled')")
            self._conn.commit()

    def all(self) -> list[Task]:
        with self._lock:
            rows = self._conn.execute("SELECT * FROM tasks ORDER BY created_at").fetchall()
        return [self._row_to_task(r) for r in rows]

    @staticmethod
    def _row_to_task(r: sqlite3.Row) -> Task:
        return Task(
            id=r["id"],
            url=r["url"],
            kind=Kind(r["kind"]),
            options=Options.from_dict(json.loads(r["options"] or "{}")),
            title=r["title"] or "",
            status=Status(r["status"]),
            filepath=r["filepath"] or "",
            total_bytes=r["total_bytes"] or 0,
            downloaded_bytes=r["downloaded_bytes"] or 0,
            error=r["error"] or "",
            created_at=r["created_at"] or 0,
            finished_at=r["finished_at"] or 0,
        )

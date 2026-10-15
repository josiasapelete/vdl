"""Fenêtre principale façon IDM : liste des téléchargements, catégories, barre d'outils,
icône dans la zone de notification, capture du presse-papiers et serveur pour l'extension."""

from __future__ import annotations

import os
import re
import subprocess
import sys
import time
from pathlib import Path

import requests
from PySide6.QtCore import QObject, QThread, QTimer, QUrl, Qt, Signal
from PySide6.QtGui import QAction, QDesktopServices, QIcon, QKeySequence
from PySide6.QtWidgets import (
    QAbstractItemView, QApplication, QFrame, QHBoxLayout, QHeaderView, QLabel, QLineEdit,
    QListWidget, QMainWindow, QMenu, QMessageBox, QPushButton, QSplitter, QStyle,
    QStyledItemDelegate, QStyleOptionProgressBar, QSystemTrayIcon, QTableWidget,
    QTableWidgetItem, QToolBar, QVBoxLayout, QWidget,
)

from .. import APP_NAME, __version__
from ..config import Settings
from ..extractor import ProbeError, ProbeResult, probe
from ..manager import DownloadManager
from ..models import Kind, Options, Status, Task, human_size, human_time
from .dialogs import DownloadDialog, SettingsDialog

URL_RE = re.compile(r"^https?://\S+$", re.I)
COLUMNS = ["Nom", "Taille", "Progression", "Vitesse", "Restant", "Statut", "Ajouté"]
CATEGORIES = [
    ("Tous", lambda t: True),
    ("En cours", lambda t: t.status in (Status.QUEUED, Status.RUNNING, Status.PROCESSING, Status.PAUSED)),
    ("Terminés", lambda t: t.status == Status.DONE),
    ("Erreurs", lambda t: t.status in (Status.ERROR, Status.CANCELED)),
    ("Vidéos", lambda t: t.kind == Kind.YTDLP and t.options.mode == "video"),
    ("Audio", lambda t: t.options.mode == "audio"),
    ("Fichiers", lambda t: t.kind == Kind.DIRECT),
]


# --------------------------------------------------------------------------- tâches de fond

class ProbeWorker(QThread):
    done = Signal(object, object)   # (ProbeResult, extra) ou (None, message)

    def __init__(self, url: str, settings: Settings, opts: Options | None = None, extra: dict | None = None):
        super().__init__()
        self.url, self.settings, self.opts, self.extra = url, settings, opts, extra or {}

    def run(self):
        try:
            if self.extra.get("alternatives"):
                from ..extractor import pick_stream
                self.url = pick_stream(self.url, self.extra["alternatives"], self.opts)
            self.done.emit(probe(self.url, self.settings, self.opts), self.extra)
        except ProbeError as e:
            self.done.emit(None, str(e))
        except Exception as e:  # noqa: BLE001
            self.done.emit(None, f"{e.__class__.__name__}: {e}")


class UpdateWorker(QThread):
    finished_msg = Signal(bool, str)
    app_update = Signal(object)        # updater.AppRelease

    def run(self):
        from ..updater import check_app_update, update
        self.finished_msg.emit(*update())
        found = check_app_update()
        if found:
            self.app_update.emit(found)


class AppUpdateWorker(QThread):
    """Télécharge et vérifie l'installeur de la nouvelle version."""
    progress = Signal(float)
    done = Signal(object, str)         # (chemin de l'installeur ou None, message d'erreur)

    def __init__(self, info):
        super().__init__()
        self.info = info

    def run(self):
        from ..updater import download_app_update
        try:
            self.done.emit(download_app_update(self.info, self.progress.emit), "")
        except Exception as e:  # noqa: BLE001
            self.done.emit(None, str(e))


class Bridge(QObject):
    """Transporte les requêtes de l'extension (thread serveur) vers le thread de l'UI."""
    request = Signal(dict)
    show = Signal()


# --------------------------------------------------------------------------- barre de progression

class ProgressDelegate(QStyledItemDelegate):
    def paint(self, painter, option, index):
        value = index.data(Qt.UserRole)
        if value is None:
            return super().paint(painter, option, index)
        opt = QStyleOptionProgressBar()
        opt.rect = option.rect.adjusted(4, 5, -4, -5)
        opt.minimum, opt.maximum = 0, 1000
        opt.progress = int(value * 1000) if value >= 0 else 0
        opt.text = f"{value * 100:.1f} %" if value >= 0 else ""
        opt.textVisible = True
        opt.state = option.state
        QApplication.style().drawControl(QStyle.CE_ProgressBar, opt, painter)


# --------------------------------------------------------------------------- fenêtre principale

class MainWindow(QMainWindow):
    def __init__(self, manager: DownloadManager, settings: Settings):
        super().__init__()
        self.manager = manager
        self.settings = settings
        self._workers: set[QThread] = set()
        self._rows: dict[str, int] = {}
        self._last_clip = ""
        self._quitting = False
        self.setWindowTitle(f"{APP_NAME} — Gestionnaire de téléchargements")
        self.resize(1100, 620)
        self.setWindowIcon(self._app_icon())

        self._build_toolbar()
        central = QWidget()
        root = QVBoxLayout(central)

        # Barre d'adresse
        bar = QHBoxLayout()
        self.url_edit = QLineEdit()
        self.url_edit.setPlaceholderText("Collez un lien : YouTube, TikTok, Facebook, Instagram, X, "
                                         "lien direct vers un fichier…")
        self.url_edit.returnPressed.connect(self.analyze_from_bar)
        self.analyze_btn = QPushButton("Télécharger")
        self.analyze_btn.setDefault(True)
        self.analyze_btn.clicked.connect(self.analyze_from_bar)
        bar.addWidget(self.url_edit, 1)
        bar.addWidget(self.analyze_btn)
        root.addLayout(bar)

        # Bandeau « lien détecté dans le presse-papiers »
        self.clip_banner = QFrame()
        self.clip_banner.setStyleSheet("QFrame{background:#2d6cdf22;border:1px solid #2d6cdf66;border-radius:6px;}")
        cb = QHBoxLayout(self.clip_banner)
        cb.setContentsMargins(10, 4, 6, 4)
        self.clip_label = QLabel()
        clip_yes = QPushButton("Télécharger")
        clip_no = QPushButton("Ignorer")
        clip_yes.clicked.connect(self._clip_accept)
        clip_no.clicked.connect(self.clip_banner.hide)
        cb.addWidget(self.clip_label, 1)
        cb.addWidget(clip_yes)
        cb.addWidget(clip_no)
        self.clip_banner.hide()
        root.addWidget(self.clip_banner)

        # Catégories + tableau
        split = QSplitter()
        self.categories = QListWidget()
        for name, _ in CATEGORIES:
            self.categories.addItem(name)
        self.categories.setCurrentRow(0)
        self.categories.setMaximumWidth(170)
        self.categories.currentRowChanged.connect(lambda _: self.refresh(full=True))
        split.addWidget(self.categories)

        self.table = QTableWidget(0, len(COLUMNS))
        self.table.setHorizontalHeaderLabels(COLUMNS)
        self.table.verticalHeader().hide()
        self.table.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.table.setEditTriggers(QAbstractItemView.NoEditTriggers)
        self.table.setShowGrid(False)
        self.table.setAlternatingRowColors(True)
        self.table.setItemDelegateForColumn(2, ProgressDelegate(self.table))
        hdr = self.table.horizontalHeader()
        hdr.setSectionResizeMode(0, QHeaderView.Stretch)
        for col, w in ((1, 90), (2, 160), (3, 95), (4, 75), (5, 110), (6, 120)):
            hdr.setSectionResizeMode(col, QHeaderView.Interactive)
            self.table.setColumnWidth(col, w)
        self.table.setContextMenuPolicy(Qt.CustomContextMenu)
        self.table.customContextMenuRequested.connect(self._context_menu)
        self.table.doubleClicked.connect(lambda _: self.open_file())
        self.table.itemSelectionChanged.connect(self._sync_actions)
        split.addWidget(self.table)
        split.setStretchFactor(1, 1)
        root.addWidget(split, 1)
        self.setCentralWidget(central)

        # Barre d'état
        self.status_speed = QLabel()
        self.status_server = QLabel()
        self.statusBar().addPermanentWidget(self.status_server)
        self.statusBar().addPermanentWidget(self.status_speed)

        # Presse-papiers
        QApplication.clipboard().dataChanged.connect(self._on_clipboard)

        # Zone de notification
        self.tray = QSystemTrayIcon(self._app_icon(), self)
        menu = QMenu()
        menu.addAction("Afficher", self.show_normal)
        menu.addAction("Tout reprendre", self.manager.resume_all)
        menu.addAction("Tout mettre en pause", self.manager.pause_all)
        menu.addSeparator()
        menu.addAction("Quitter", self.quit)
        self.tray.setContextMenu(menu)
        self.tray.activated.connect(lambda r: self.show_normal() if r == QSystemTrayIcon.Trigger else None)
        self.tray.setToolTip(APP_NAME)
        self.tray.show()
        self._notified: set[str] = {t.id for t in manager.snapshot() if t.status in (Status.DONE, Status.ERROR)}

        # Rafraîchissement périodique (le gestionnaire tourne dans ses propres threads).
        # Après la création de l'icône de notification : refresh() s'en sert.
        self.timer = QTimer(self, interval=500, timeout=self.refresh)
        self.timer.start()
        self.refresh(full=True)

        # Mise à jour de yt-dlp en arrière-plan
        if settings.auto_update_ytdlp:
            w = UpdateWorker()
            w.finished_msg.connect(lambda updated, msg: updated and self.statusBar().showMessage(msg, 15000))
            w.app_update.connect(self._on_app_update)
            self._run_worker(w)

    def _on_app_update(self, info):
        self._update_btn = QPushButton(f"VDL {info.version} disponible — mettre à jour")
        self._update_btn.setFlat(True)
        self._update_btn.setStyleSheet("color:#2d6cdf;font-weight:600;")
        self._update_btn.clicked.connect(lambda: self._start_app_update(info))
        self.statusBar().addPermanentWidget(self._update_btn)
        self.tray.showMessage(APP_NAME, f"Une nouvelle version de VDL ({info.version}) est disponible.",
                              QSystemTrayIcon.Information, 6000)

    def _start_app_update(self, info):
        from ..updater import can_self_update
        if not can_self_update(info):        # version de développement : page de téléchargement
            QDesktopServices.openUrl(QUrl(info.page_url))
            return
        if any(t.status in (Status.RUNNING, Status.PROCESSING) for t in self.manager.snapshot()) and \
                QMessageBox.question(self, APP_NAME, "Des téléchargements sont en cours. Ils seront mis en pause "
                                     "et reprendront après la mise à jour. Continuer ?") != QMessageBox.Yes:
            return
        self._update_btn.setEnabled(False)
        w = AppUpdateWorker(info)
        w.progress.connect(lambda p: self._update_btn.setText(f"Téléchargement de VDL {info.version}… {p * 100:.0f} %"))
        w.done.connect(lambda path, err: self._finish_app_update(info, path, err))
        self._run_worker(w)

    def _finish_app_update(self, info, path, err: str):
        if path is None:
            self._update_btn.setEnabled(True)
            self._update_btn.setText(f"VDL {info.version} disponible — réessayer")
            QMessageBox.warning(self, APP_NAME, f"La mise à jour n'a pas pu être téléchargée.\n\n{err}")
            return
        from ..updater import run_installer
        self.tray.showMessage(APP_NAME, f"Installation de VDL {info.version}… VDL va redémarrer.",
                              QSystemTrayIcon.Information, 4000)
        run_installer(path)
        self.quit()     # les téléchargements en cours sont mis en pause et reprendront au redémarrage

    # ------------------------------------------------------------------ construction

    def _app_icon(self) -> QIcon:
        png = Path(__file__).with_name("icon.png")
        return QIcon(str(png)) if png.exists() else self.style().standardIcon(QStyle.SP_ArrowDown)

    def _build_toolbar(self):
        tb = QToolBar("Actions")
        tb.setMovable(False)
        tb.setToolButtonStyle(Qt.ToolButtonTextUnderIcon)
        st = self.style()

        def act(text, icon, slot, shortcut=None):
            a = QAction(st.standardIcon(icon), text, self)
            a.triggered.connect(slot)
            if shortcut:
                a.setShortcut(QKeySequence(shortcut))
            tb.addAction(a)
            return a

        self.a_add = act("Ajouter", QStyle.SP_FileDialogNewFolder, self.add_from_clipboard_or_focus, "Ctrl+N")
        tb.addSeparator()
        self.a_resume = act("Reprendre", QStyle.SP_MediaPlay, lambda: self._each(self.manager.resume))
        self.a_pause = act("Pause", QStyle.SP_MediaPause, lambda: self._each(self.manager.pause))
        self.a_cancel = act("Annuler", QStyle.SP_MediaStop, lambda: self._each(self.manager.cancel))
        self.a_delete = act("Supprimer", QStyle.SP_TrashIcon, self.delete_selected, "Delete")
        tb.addSeparator()
        act("Tout reprendre", QStyle.SP_MediaSeekForward, self.manager.resume_all)
        act("Tout en pause", QStyle.SP_MediaSkipBackward, self.manager.pause_all)
        act("Nettoyer", QStyle.SP_DialogResetButton, self.clear_finished)
        tb.addSeparator()
        self.a_folder = act("Dossier", QStyle.SP_DirOpenIcon, self.open_folder)
        act("Réglages", QStyle.SP_FileDialogDetailedView, self.open_settings, "Ctrl+,")
        self.addToolBar(tb)

    # ------------------------------------------------------------------ tableau

    def _visible_tasks(self) -> list[Task]:
        pred = CATEGORIES[max(0, self.categories.currentRow())][1]
        return [t for t in reversed(self.manager.snapshot()) if pred(t)]

    def refresh(self, full: bool = False):
        tasks = self._visible_tasks()
        ids = [t.id for t in tasks]
        if full or ids != list(self._rows):
            selected = set(self._selected_ids())
            self.table.setRowCount(len(tasks))
            self._rows = {tid: i for i, tid in enumerate(ids)}
            for i, t in enumerate(tasks):
                for c in range(len(COLUMNS)):
                    if self.table.item(i, c) is None:
                        self.table.setItem(i, c, QTableWidgetItem())
                self.table.item(i, 0).setData(Qt.UserRole + 1, t.id)
            self.table.clearSelection()
            for tid in selected:
                if tid in self._rows:
                    self.table.selectRow(self._rows[tid])
        for t in tasks:
            self._fill_row(self._rows[t.id], t)

        total_speed = sum(t.speed for t in self.manager.snapshot() if t.status == Status.RUNNING)
        active = sum(1 for t in self.manager.snapshot() if t.status.active)
        self.status_speed.setText(f"  {active} actif(s) · {human_size(total_speed)}/s  ")
        self._notify_finished()
        self._sync_actions()

    def _fill_row(self, row: int, t: Task):
        it = self.table.item
        icon = QStyle.SP_MediaVolume if t.options.mode == "audio" else (
            QStyle.SP_FileIcon if t.kind == Kind.DIRECT else QStyle.SP_MediaPlay)
        name = t.title or t.url
        if it(row, 0).text() != name:
            it(row, 0).setText(name)
            it(row, 0).setIcon(self.style().standardIcon(icon))
        it(row, 0).setToolTip(f"{t.url}\n{t.filepath}" + (f"\n\n{t.error}" if t.error else ""))
        it(row, 1).setText(human_size(t.total_bytes))
        p = t.progress if (t.total_bytes or t.status == Status.DONE) else -1.0
        if t.status == Status.PROCESSING:
            p = 1.0
        it(row, 2).setData(Qt.UserRole, p)
        it(row, 3).setText(f"{human_size(t.speed)}/s" if t.status == Status.RUNNING and t.speed else "")
        it(row, 4).setText(human_time(t.eta) if t.status == Status.RUNNING else "")
        label = t.status.label + (f" : {t.error[:60]}" if t.status == Status.ERROR else "")
        it(row, 5).setText(label)
        it(row, 5).setToolTip(t.error)
        it(row, 6).setText(time.strftime("%d/%m %H:%M", time.localtime(t.created_at)))

    def _selected_ids(self) -> list[str]:
        rows = {i.row() for i in self.table.selectedIndexes()}
        out = []
        for r in sorted(rows):
            item = self.table.item(r, 0)
            if item and item.data(Qt.UserRole + 1):
                out.append(item.data(Qt.UserRole + 1))
        return out

    def _selected_tasks(self) -> list[Task]:
        return [t for t in (self.manager.get(i) for i in self._selected_ids()) if t]

    def _each(self, fn):
        for tid in self._selected_ids():
            fn(tid)
        self.refresh()

    def _sync_actions(self):
        ts = self._selected_tasks()
        self.a_resume.setEnabled(any(t.status in (Status.PAUSED, Status.ERROR, Status.CANCELED) for t in ts))
        self.a_pause.setEnabled(any(t.status in (Status.QUEUED, Status.RUNNING) for t in ts))
        self.a_cancel.setEnabled(any(t.status.active or t.status == Status.PAUSED for t in ts))
        self.a_delete.setEnabled(bool(ts))

    def _context_menu(self, pos):
        ts = self._selected_tasks()
        if not ts:
            return
        m = QMenu(self)
        m.addAction("Ouvrir", self.open_file).setEnabled(any(t.status == Status.DONE for t in ts))
        m.addAction("Ouvrir le dossier", self.open_folder)
        m.addSeparator()
        m.addAction(self.a_resume)
        m.addAction(self.a_pause)
        m.addAction(self.a_cancel)
        m.addSeparator()
        m.addAction("Copier le lien", lambda: QApplication.clipboard().setText("\n".join(t.url for t in ts)))
        m.addAction("Retélécharger", lambda: [self._readd(t) for t in ts])
        m.addSeparator()
        m.addAction("Retirer de la liste", self.delete_selected)
        m.addAction("Supprimer avec le fichier", lambda: self.delete_selected(with_file=True))
        m.exec(self.table.viewport().mapToGlobal(pos))

    # ------------------------------------------------------------------ actions

    def _readd(self, t: Task):
        self.manager.add(t.url, Options(**t.options.__dict__), t.kind, t.title)

    def delete_selected(self, with_file: bool = False):
        ts = self._selected_tasks()
        if not ts:
            return
        if with_file and QMessageBox.question(
                self, APP_NAME, f"Supprimer {len(ts)} fichier(s) du disque ?") != QMessageBox.Yes:
            return
        for t in ts:
            self.manager.remove(t.id, delete_file=with_file)
        self.refresh(full=True)

    def clear_finished(self):
        self.manager.clear_finished()
        self.refresh(full=True)

    def open_file(self):
        for t in self._selected_tasks():
            if t.status == Status.DONE and t.filepath and Path(t.filepath).exists():
                QDesktopServices.openUrl(QUrl.fromLocalFile(t.filepath))
                return

    def open_folder(self):
        ts = self._selected_tasks()
        if ts and ts[0].filepath and Path(ts[0].filepath).exists():
            path = ts[0].filepath
            if sys.platform == "win32":
                subprocess.Popen(["explorer", "/select,", os.path.normpath(path)])
                return
            QDesktopServices.openUrl(QUrl.fromLocalFile(str(Path(path).parent)))
            return
        folder = Path(self.settings.download_dir).expanduser()
        folder.mkdir(parents=True, exist_ok=True)
        QDesktopServices.openUrl(QUrl.fromLocalFile(str(folder)))

    def open_settings(self):
        dlg = SettingsDialog(self.settings, self)
        if dlg.exec():
            dlg.apply()
            self.manager._wake.set()   # nouveau nombre de téléchargements simultanés
            self.statusBar().showMessage("Réglages enregistrés", 4000)

    def add_from_clipboard_or_focus(self):
        text = QApplication.clipboard().text().strip()
        if URL_RE.match(text) and not self.url_edit.text().strip():
            self.url_edit.setText(text)
        self.url_edit.setFocus()
        self.url_edit.selectAll()

    # ------------------------------------------------------------------ analyse → dialogue

    def _run_worker(self, w: QThread):
        self._workers.add(w)
        w.finished.connect(lambda: self._workers.discard(w))
        w.start()

    def analyze_from_bar(self):
        urls = [u for u in re.split(r"\s+", self.url_edit.text().strip()) if URL_RE.match(u)]
        if not urls:
            self.statusBar().showMessage("Collez un lien http(s) valide.", 4000)
            return
        self.url_edit.clear()
        for u in urls:
            self.analyze(u)

    def analyze(self, url: str, opts: Options | None = None, extra: dict | None = None):
        self.statusBar().showMessage(f"Analyse de {url} …")
        w = ProbeWorker(url, self.settings, opts, extra)
        w.done.connect(self._on_probed)
        self._run_worker(w)

    def _on_probed(self, result: ProbeResult | None, extra):
        if result is None:
            self.statusBar().clearMessage()
            QMessageBox.warning(self, APP_NAME, f"Impossible d'analyser ce lien :\n\n{extra}")
            return
        page_title = (extra or {}).get("title")
        if page_title and (result.kind == Kind.DIRECT or result.extractor in ("", "Generic")):
            result.title = page_title     # flux capté : le titre de l'onglet vaut mieux que « master »
        self.statusBar().showMessage(f"Prêt : {result.title}", 3000)
        self.show_normal()
        dlg = DownloadDialog(result, self.settings, self)
        if not dlg.exec():
            return
        opts = dlg.options()
        extra = extra or {}
        opts.referer = extra.get("referer") or result.referer
        opts.user_agent = extra.get("userAgent", "")
        opts.headers = extra.get("headers") or {}
        opts.impersonate = result.impersonate
        folder = dlg.download_dir()
        if folder != self.settings.download_dir:
            self.settings.download_dir = folder
            self.settings.save()
        targets = dlg.targets()
        kind = Kind.YTDLP if result.is_playlist else result.kind
        for url, title in targets:
            self.manager.add(url, Options(**opts.__dict__), kind, title, start=dlg.start_now)
        self.categories.setCurrentRow(1)
        self.refresh(full=True)

    # ------------------------------------------------------------------ presse-papiers

    def _on_clipboard(self):
        if not self.settings.clipboard_watch:
            return
        text = QApplication.clipboard().text().strip()
        if text == self._last_clip or not URL_RE.match(text) or len(text) > 2000:
            return
        self._last_clip = text
        self.clip_label.setText(f"Lien copié détecté : <b>{text[:90]}{'…' if len(text) > 90 else ''}</b>")
        self.clip_banner.setProperty("url", text)
        self.clip_banner.show()
        if not self.isVisible() or self.isMinimized():
            self.tray.showMessage(APP_NAME, "Lien copié — cliquez pour télécharger", QSystemTrayIcon.Information, 4000)
            try:
                self.tray.messageClicked.disconnect()
            except (RuntimeError, TypeError):
                pass
            self.tray.messageClicked.connect(self._clip_accept)

    def _clip_accept(self):
        self.clip_banner.hide()
        url = self.clip_banner.property("url")
        if url:
            self.analyze(url)

    # ------------------------------------------------------------------ extension navigateur

    def on_extension_request(self, data: dict):
        opts = Options(referer=data.get("referer") or "", user_agent=data.get("userAgent") or "",
                       headers=data.get("headers") or {})
        self.analyze(data["url"], opts, {"referer": opts.referer, "userAgent": opts.user_agent,
                                         "headers": opts.headers, "title": data.get("title") or "",
                                         "alternatives": data.get("alternatives") or []})

    # ------------------------------------------------------------------ notifications

    def _notify_finished(self):
        for t in self.manager.snapshot():
            if t.id in self._notified or t.status not in (Status.DONE, Status.ERROR):
                continue
            self._notified.add(t.id)
            if t.status == Status.DONE:
                self.tray.showMessage("Téléchargement terminé", t.title or t.url, QSystemTrayIcon.Information, 4000)
            else:
                self.tray.showMessage("Échec du téléchargement", f"{t.title}\n{t.error[:120]}",
                                      QSystemTrayIcon.Warning, 6000)

    # ------------------------------------------------------------------ fenêtre / fermeture

    def show_normal(self):
        self.show()
        self.setWindowState((self.windowState() & ~Qt.WindowMinimized) | Qt.WindowActive)
        self.raise_()
        self.activateWindow()

    def closeEvent(self, event):
        if self._quitting:
            return super().closeEvent(event)
        # Comme IDM : on reste dans la zone de notification pour continuer à recevoir les liens
        event.ignore()
        self.hide()
        if not getattr(self, "_tray_hint", False):
            self._tray_hint = True
            self.tray.showMessage(APP_NAME, "VDL continue en arrière-plan. Clic droit sur l'icône pour quitter.",
                                  QSystemTrayIcon.Information, 4000)

    def quit(self):
        self._quitting = True
        self.timer.stop()
        self.tray.hide()
        QApplication.quit()


# --------------------------------------------------------------------------- démarrage

def _show_existing(port: int) -> bool:
    """Demande à l'instance déjà lancée d'afficher sa fenêtre."""
    try:
        r = requests.get(f"http://127.0.0.1:{port}/show", headers={"X-VDL-Client": "gui"}, timeout=2)
        return r.ok
    except requests.RequestException:
        return False


def _install_crash_handler() -> None:
    """Lancé sans console (VDL.bat / pythonw), une erreur serait invisible :
    on l'écrit dans crash.log et on l'affiche."""
    import traceback
    from ..config import data_dir

    def hook(exc_type, exc, tb):
        text = "".join(traceback.format_exception(exc_type, exc, tb))
        log = data_dir() / "crash.log"
        try:
            with open(log, "a", encoding="utf-8") as f:
                f.write(f"\n--- {time.strftime('%Y-%m-%d %H:%M:%S')} ---\n{text}")
        except OSError:
            pass
        if sys.stderr:
            sys.stderr.write(text)
        if QApplication.instance():
            QMessageBox.critical(None, APP_NAME, f"Erreur inattendue :\n\n{exc}\n\nDétails dans :\n{log}")

    sys.excepthook = hook


def main() -> int:
    _install_crash_handler()
    QApplication.setApplicationName(APP_NAME)
    QApplication.setQuitOnLastWindowClosed(False)
    app = QApplication.instance() or QApplication(sys.argv)
    settings = Settings.load()

    # Une seule instance : deux VDL partageraient la même file d'attente et se disputeraient le port
    from PySide6.QtCore import QLockFile
    from ..config import data_dir
    lock = QLockFile(str(data_dir() / "vdl.lock"))
    lock.setStaleLockTime(0)          # verrou d'un VDL planté : libéré automatiquement
    if not lock.tryLock(200):
        if not _show_existing(settings.server_port):
            QMessageBox.information(None, APP_NAME, "VDL est déjà ouvert (voir la zone de notification, "
                                                    "près de l'horloge).")
        return 0

    manager = DownloadManager(settings)
    win = MainWindow(manager, settings)

    bridge = Bridge()
    bridge.request.connect(win.on_extension_request)
    bridge.show.connect(win.show_normal)

    def on_request(data: dict) -> bool:
        if data.get("quick"):        # média détecté envoyé tel quel : file d'attente directe
            return False
        bridge.request.emit(data)    # sinon : dialogue de choix du format, comme IDM
        return True

    server = None
    try:
        from ..server import LocalServer
        server = LocalServer(manager, settings.server_port, on_request, bridge.show.emit).start()
        win.status_server.setText(f"  Extension : port {settings.server_port}  ")
    except OSError as e:
        win.status_server.setText("  Extension : serveur indisponible  ")
        win.statusBar().showMessage(f"Serveur local indisponible ({e})", 8000)

    if "--minimized" not in sys.argv:
        win.show()
    code = app.exec()
    lock.unlock()
    if server:
        server.stop()
    manager.shutdown()
    return code


if __name__ == "__main__":
    sys.exit(main())

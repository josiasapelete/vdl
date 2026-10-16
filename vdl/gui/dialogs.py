"""Dialogues : choix du format avant téléchargement, et réglages."""

from __future__ import annotations

from pathlib import Path

import requests
from PySide6.QtCore import QSize, Qt, QThread, Signal
from PySide6.QtGui import QPixmap
from PySide6.QtWidgets import (
    QButtonGroup, QCheckBox, QComboBox, QDialog, QDialogButtonBox, QFileDialog, QFormLayout,
    QGroupBox, QHBoxLayout, QLabel, QLineEdit, QListWidget, QListWidgetItem, QPushButton,
    QRadioButton, QSpinBox, QVBoxLayout, QWidget,
)

from ..config import Settings
from ..extractor import ProbeResult
from ..models import Kind, Options, human_size, human_time


class ThumbLoader(QThread):
    loaded = Signal(bytes)

    def __init__(self, url: str):
        super().__init__()
        self.url = url

    def run(self):
        try:
            r = requests.get(self.url, timeout=10)
            if r.ok:
                self.loaded.emit(r.content)
        except requests.RequestException:
            pass


def _folder_row(parent: QWidget, edit: QLineEdit) -> QWidget:
    w = QWidget(parent)
    lay = QHBoxLayout(w)
    lay.setContentsMargins(0, 0, 0, 0)
    btn = QPushButton("Parcourir…")

    def pick():
        d = QFileDialog.getExistingDirectory(parent, "Dossier de destination", edit.text())
        if d:
            edit.setText(d)

    btn.clicked.connect(pick)
    lay.addWidget(edit, 1)
    lay.addWidget(btn)
    return w


def _quality_label(q: str) -> str:
    return "Meilleure qualité" if q == "best" else f"{q}p" + (" (4K)" if q == "2160" else "")


class DownloadDialog(QDialog):
    """Équivalent de la fenêtre « Informations sur le fichier » d'IDM."""

    def __init__(self, result: ProbeResult, settings: Settings, parent=None):
        super().__init__(parent)
        self.result = result
        self.settings = settings
        self.setWindowTitle("Télécharger")
        self.setMinimumWidth(560)
        root = QVBoxLayout(self)

        # --- en-tête : miniature + infos
        head = QHBoxLayout()
        self.thumb = QLabel()
        self.thumb.setFixedSize(QSize(176, 99))
        self.thumb.setAlignment(Qt.AlignCenter)
        self.thumb.setStyleSheet("background:#00000022;border-radius:6px;")
        head.addWidget(self.thumb)
        info = QVBoxLayout()
        title = QLabel(f"<b>{_esc(result.title or result.url)}</b>")
        title.setWordWrap(True)
        info.addWidget(title)
        meta = []
        if result.kind == Kind.DIRECT:
            meta.append("Fichier direct — téléchargement multi-segments")
        elif result.extractor:
            meta.append(result.extractor)
        if result.uploader:
            meta.append(result.uploader)
        if result.duration:
            meta.append(human_time(result.duration))
        if result.filesize:
            meta.append(human_size(result.filesize))
        if result.is_playlist:
            meta.append(f"Playlist : {len(result.entries)} éléments")
        info.addWidget(QLabel(" · ".join(meta)))
        info.addStretch(1)
        head.addLayout(info, 1)
        root.addLayout(head)
        if result.thumbnail:
            self._loader = ThumbLoader(result.thumbnail)
            self._loader.loaded.connect(self._set_thumb)
            self._loader.start()
        else:
            self.thumb.setText("🎬" if result.kind == Kind.YTDLP else "📄")
            self.thumb.setStyleSheet(self.thumb.styleSheet() + "font-size:40px;")

        # --- playlist
        self.entries_list: QListWidget | None = None
        self.only_single: QCheckBox | None = None
        if result.is_playlist:
            box = QGroupBox("Éléments de la playlist")
            bl = QVBoxLayout(box)
            if result.single_video is not None:
                self.only_single = QCheckBox("Seulement la vidéo de ce lien (pas toute la playlist)")
                self.only_single.setChecked(True)
                bl.addWidget(self.only_single)
            self.entries_list = QListWidget()
            for e in result.entries:
                it = QListWidgetItem(e.title or e.url)
                it.setFlags(it.flags() | Qt.ItemIsUserCheckable)
                it.setCheckState(Qt.Checked)
                it.setData(Qt.UserRole, e)
                self.entries_list.addItem(it)
            self.entries_list.setMinimumHeight(160)
            bl.addWidget(self.entries_list)
            row = QHBoxLayout()
            for label, state in (("Tout cocher", Qt.Checked), ("Tout décocher", Qt.Unchecked)):
                b = QPushButton(label)
                b.clicked.connect(lambda _=False, s=state: self._check_all(s))
                row.addWidget(b)
            row.addStretch(1)
            bl.addLayout(row)
            if self.only_single:
                self.only_single.toggled.connect(lambda on: self.entries_list.setEnabled(not on))
                self.entries_list.setEnabled(False)
            root.addWidget(box)

        # --- format
        form = QFormLayout()
        self.mode_video = QRadioButton("Vidéo (MP4)")
        self.mode_audio = QRadioButton("Audio (MP3)")
        grp = QButtonGroup(self)
        grp.addButton(self.mode_video)
        grp.addButton(self.mode_audio)
        (self.mode_audio if settings.default_mode == "audio" else self.mode_video).setChecked(True)
        mode_row = QWidget()
        ml = QHBoxLayout(mode_row)
        ml.setContentsMargins(0, 0, 0, 0)
        ml.addWidget(self.mode_video)
        ml.addWidget(self.mode_audio)
        ml.addStretch(1)

        self.quality = QComboBox()
        qualities = (result.single_video.qualities if result.single_video and not result.qualities
                     else result.qualities)
        for q in qualities:
            self.quality.addItem(_quality_label(q), q)
        want = settings.default_quality
        idx = self.quality.findData(want)
        if idx < 0 and want != "best":   # ex. 1080 demandé mais la vidéo s'arrête à 720
            idx = next((i for i in range(self.quality.count())
                        if self.quality.itemData(i) != "best" and int(self.quality.itemData(i)) <= int(want)), 0)
        self.quality.setCurrentIndex(max(0, idx))

        self.bitrate = QComboBox()
        for b in ("128", "192", "256", "320"):
            self.bitrate.addItem(f"{b} kbps", b)
        self.bitrate.setCurrentIndex(max(0, self.bitrate.findData(settings.audio_bitrate)))

        self.subs = QLineEdit(settings.subtitles)
        self.subs.setPlaceholderText("ex. fr,en — vide = aucun")
        self.auto_subs = QCheckBox("Inclure les sous-titres automatiques")
        self.auto_subs.setChecked(settings.auto_subtitles)

        self.folder = QLineEdit(settings.download_dir)

        if result.kind == Kind.YTDLP:
            form.addRow("Type :", mode_row)
            form.addRow("Qualité vidéo :", self.quality)
            form.addRow("Qualité audio :", self.bitrate)
            form.addRow("Sous-titres :", self.subs)
            form.addRow("", self.auto_subs)
        form.addRow("Enregistrer dans :", _folder_row(self, self.folder))
        root.addLayout(form)

        self.mode_video.toggled.connect(self._sync_mode)
        self._sync_mode()

        buttons = QDialogButtonBox()
        self.later_btn = buttons.addButton("Plus tard", QDialogButtonBox.ActionRole)
        buttons.addButton("Télécharger", QDialogButtonBox.AcceptRole)
        buttons.addButton("Annuler", QDialogButtonBox.RejectRole)
        self.start_now = True
        self.later_btn.clicked.connect(self._later)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        root.addWidget(buttons)

    def _later(self):
        self.start_now = False
        self.accept()

    def _set_thumb(self, data: bytes):
        pm = QPixmap()
        if pm.loadFromData(data):
            self.thumb.setPixmap(pm.scaled(self.thumb.size(), Qt.KeepAspectRatio, Qt.SmoothTransformation))

    def _check_all(self, state):
        for i in range(self.entries_list.count()):
            self.entries_list.item(i).setCheckState(state)

    def _sync_mode(self):
        video = self.mode_video.isChecked()
        self.quality.setEnabled(video)
        self.bitrate.setEnabled(not video)

    # --- résultat
    def options(self) -> Options:
        return Options(
            mode="video" if self.mode_video.isChecked() else "audio",
            quality=self.quality.currentData() or "best",
            audio_bitrate=self.bitrate.currentData() or "192",
            subtitles=self.subs.text().strip(),
            auto_subtitles=self.auto_subs.isChecked(),
        )

    def targets(self) -> list[tuple[str, str]]:
        """Liste (url, titre) à ajouter à la file."""
        r = self.result
        if r.is_playlist:
            if self.only_single and self.only_single.isChecked() and r.single_video:
                return [(r.single_video.url, r.single_video.title)]
            out = []
            for i in range(self.entries_list.count()):
                it = self.entries_list.item(i)
                if it.checkState() == Qt.Checked:
                    e = it.data(Qt.UserRole)
                    out.append((e.url, e.title))
            return out
        return [(r.url, r.title)]

    def download_dir(self) -> str:
        return self.folder.text().strip() or self.settings.download_dir


class SettingsDialog(QDialog):
    def __init__(self, settings: Settings, parent=None):
        super().__init__(parent)
        self.s = settings
        self.setWindowTitle("Réglages")
        self.setMinimumWidth(520)
        form = QFormLayout(self)

        self.folder = QLineEdit(settings.download_dir)
        form.addRow("Dossier de téléchargement :", _folder_row(self, self.folder))

        self.concurrent = QSpinBox(minimum=1, maximum=16, value=int(settings.max_concurrent))
        form.addRow("Téléchargements simultanés :", self.concurrent)
        self.segments = QSpinBox(minimum=1, maximum=32, value=int(settings.segments))
        form.addRow("Connexions par fichier :", self.segments)
        self.fragments = QSpinBox(minimum=1, maximum=16, value=int(settings.fragment_threads))
        form.addRow("Fragments vidéo en parallèle :", self.fragments)

        self.mode = QComboBox()
        self.mode.addItem("Vidéo", "video")
        self.mode.addItem("Audio MP3", "audio")
        self.mode.setCurrentIndex(max(0, self.mode.findData(settings.default_mode)))
        form.addRow("Type par défaut :", self.mode)

        self.quality = QComboBox()
        for q in ("best", "2160", "1440", "1080", "720", "480", "360"):
            self.quality.addItem(_quality_label(q), q)
        self.quality.setCurrentIndex(max(0, self.quality.findData(settings.default_quality)))
        form.addRow("Qualité par défaut :", self.quality)

        self.bitrate = QComboBox()
        for b in ("128", "192", "256", "320"):
            self.bitrate.addItem(f"{b} kbps", b)
        self.bitrate.setCurrentIndex(max(0, self.bitrate.findData(settings.audio_bitrate)))
        form.addRow("Débit MP3 :", self.bitrate)

        self.subs = QLineEdit(settings.subtitles)
        self.subs.setPlaceholderText("ex. fr,en")
        form.addRow("Sous-titres par défaut :", self.subs)
        self.auto_subs = QCheckBox("Inclure les sous-titres automatiques")
        self.auto_subs.setChecked(settings.auto_subtitles)
        form.addRow("", self.auto_subs)

        self.cookies = QComboBox()
        for label, val in (("Aucun", ""), ("Chrome", "chrome"), ("Edge", "edge"), ("Firefox", "firefox"),
                           ("Brave", "brave"), ("Opera", "opera")):
            self.cookies.addItem(label, val)
        self.cookies.setCurrentIndex(max(0, self.cookies.findData(settings.cookies_from_browser)))
        self.cookies.setToolTip("Utilise votre session du navigateur pour les vidéos privées "
                                "ou réservées aux membres (Facebook, Instagram…)")
        form.addRow("Cookies du navigateur :", self.cookies)

        self.floating = QCheckBox("Afficher la bulle flottante (cliquer ou y glisser un lien)")
        self.floating.setChecked(settings.floating_button)
        form.addRow("Bulle :", self.floating)
        self.clipboard = QCheckBox("Détecter les liens copiés")
        self.clipboard.setChecked(settings.clipboard_watch)
        form.addRow("Presse-papiers :", self.clipboard)
        self.autoupdate = QCheckBox("Mettre à jour yt-dlp automatiquement")
        self.autoupdate.setChecked(settings.auto_update_ytdlp)
        form.addRow("Mises à jour :", self.autoupdate)
        self.port = QSpinBox(minimum=1024, maximum=65535, value=int(settings.server_port))
        form.addRow("Port de l'extension :", self.port)

        buttons = QDialogButtonBox(QDialogButtonBox.Ok | QDialogButtonBox.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        form.addRow(buttons)

    def apply(self) -> None:
        s = self.s
        s.download_dir = self.folder.text().strip() or s.download_dir
        s.max_concurrent = self.concurrent.value()
        s.segments = self.segments.value()
        s.fragment_threads = self.fragments.value()
        s.default_mode = self.mode.currentData()
        s.default_quality = self.quality.currentData()
        s.audio_bitrate = self.bitrate.currentData()
        s.subtitles = self.subs.text().strip()
        s.auto_subtitles = self.auto_subs.isChecked()
        s.cookies_from_browser = self.cookies.currentData()
        s.clipboard_watch = self.clipboard.isChecked()
        s.floating_button = self.floating.isChecked()
        s.auto_update_ytdlp = self.autoupdate.isChecked()
        s.server_port = self.port.value()
        Path(s.download_dir).expanduser().mkdir(parents=True, exist_ok=True)
        s.save()


def _esc(s: str) -> str:
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

"""Bulle flottante (comme la « cible de dépôt » d'IDM) : un rond VDL toujours au premier plan.

- Clic : télécharge le lien copié dans le presse-papiers.
- Glisser-déposer : un lien (navigateur, Telegram Desktop…) ou un fichier lâché dessus est téléchargé.
- Se déplace à la souris ; clic droit pour la masquer.
"""

from __future__ import annotations

import re
from pathlib import Path

from PySide6.QtCore import QPoint, QRectF, Qt, Signal
from PySide6.QtGui import QAction, QColor, QGuiApplication, QPainter, QPixmap
from PySide6.QtWidgets import QApplication, QMenu, QWidget

URL_RE = re.compile(r"https?://[^\s<>\"']+", re.I)
SIZE = 64


class FloatingButton(QWidget):
    activated = Signal(str)      # lien à télécharger ("" = aucun lien copié)
    hide_requested = Signal()
    open_requested = Signal()
    moved = Signal(int, int)

    def __init__(self, x: int | None = None, y: int | None = None):
        super().__init__(None, Qt.FramelessWindowHint | Qt.WindowStaysOnTopHint | Qt.Tool)
        self.setAttribute(Qt.WA_TranslucentBackground)
        self.setAcceptDrops(True)
        self.setFixedSize(SIZE, SIZE)
        self.setToolTip("VDL — cliquez après avoir copié un lien, ou glissez un lien ici")
        self._icon = QPixmap(str(Path(__file__).with_name("icon.png")))
        self._press: QPoint | None = None
        self._origin = QPoint()
        self._dragging = False
        self._hover_drop = False

        screen = QGuiApplication.primaryScreen().availableGeometry()
        if x is None or y is None or not screen.adjusted(-SIZE, -SIZE, 0, 0).contains(x, y):
            x, y = screen.right() - SIZE - 24, screen.bottom() - SIZE - 120
        self.move(x, y)

    # ------------------------------------------------------------------ dessin

    def paintEvent(self, _):
        p = QPainter(self)
        p.setRenderHint(QPainter.Antialiasing)
        p.setRenderHint(QPainter.SmoothPixmapTransform)
        # Ombre légère, puis l'icône
        p.setPen(Qt.NoPen)
        p.setBrush(QColor(0, 0, 0, 60))
        p.drawEllipse(QRectF(6, 8, SIZE - 12, SIZE - 12))
        target = QRectF(4, 4, SIZE - 12, SIZE - 12)
        p.drawPixmap(target.toRect(), self._icon)
        if self._hover_drop:     # un lien survole la bulle : anneau de confirmation
            p.setBrush(Qt.NoBrush)
            p.setPen(QColor("#1d7a46"))
            pen = p.pen()
            pen.setWidth(4)
            p.setPen(pen)
            p.drawEllipse(target.adjusted(-2, -2, 2, 2))
        p.end()

    # ------------------------------------------------------------------ souris

    def mousePressEvent(self, e):
        if e.button() == Qt.LeftButton:
            self._press = e.globalPosition().toPoint()
            self._origin = self.pos()
            self._dragging = False

    def mouseMoveEvent(self, e):
        if self._press is None:
            return
        delta = e.globalPosition().toPoint() - self._press
        if not self._dragging and delta.manhattanLength() > QApplication.startDragDistance():
            self._dragging = True
        if self._dragging:
            self.move(self._origin + delta)

    def mouseReleaseEvent(self, e):
        if e.button() != Qt.LeftButton or self._press is None:
            return
        if self._dragging:
            self.moved.emit(self.x(), self.y())
        else:
            text = QApplication.clipboard().text().strip()
            m = URL_RE.search(text)
            self.activated.emit(m.group(0) if m else "")
        self._press = None
        self._dragging = False

    def contextMenuEvent(self, e):
        menu = QMenu(self)
        open_act = QAction("Ouvrir VDL", menu)
        open_act.triggered.connect(self.open_requested.emit)
        hide_act = QAction("Masquer la bulle", menu)
        hide_act.triggered.connect(self.hide_requested.emit)
        menu.addAction(open_act)
        menu.addSeparator()
        menu.addAction(hide_act)
        menu.exec(e.globalPos())

    # ------------------------------------------------------------------ glisser-déposer

    @staticmethod
    def _link_from(mime) -> str:
        if mime.hasUrls():
            for u in mime.urls():
                s = u.toString()
                if s.lower().startswith(("http://", "https://")):
                    return s
        if mime.hasText():
            m = URL_RE.search(mime.text())
            if m:
                return m.group(0)
        return ""

    def dragEnterEvent(self, e):
        if self._link_from(e.mimeData()):
            self._hover_drop = True
            self.update()
            e.acceptProposedAction()

    def dragLeaveEvent(self, _):
        self._hover_drop = False
        self.update()

    def dropEvent(self, e):
        self._hover_drop = False
        self.update()
        link = self._link_from(e.mimeData())
        if link:
            e.acceptProposedAction()
            self.activated.emit(link)

"""Génère les icônes PNG de l'application et de l'extension (flèche de téléchargement)."""

import sys
from pathlib import Path

from PySide6.QtCore import QPointF, QRectF, Qt
from PySide6.QtGui import QColor, QGuiApplication, QImage, QPainter, QPainterPath, QPen

ROOT = Path(__file__).resolve().parent.parent


def draw(size: int) -> QImage:
    img = QImage(size, size, QImage.Format_ARGB32)
    img.fill(Qt.transparent)
    p = QPainter(img)
    p.setRenderHint(QPainter.Antialiasing)
    s = size / 128
    p.setPen(Qt.NoPen)
    p.setBrush(QColor("#2d6cdf"))
    p.drawRoundedRect(QRectF(4 * s, 4 * s, 120 * s, 120 * s), 28 * s, 28 * s)
    arrow = QPainterPath()
    arrow.moveTo(52 * s, 24 * s)
    arrow.lineTo(76 * s, 24 * s)
    arrow.lineTo(76 * s, 58 * s)
    arrow.lineTo(96 * s, 58 * s)
    arrow.lineTo(64 * s, 90 * s)
    arrow.lineTo(32 * s, 58 * s)
    arrow.lineTo(52 * s, 58 * s)
    arrow.closeSubpath()
    p.setBrush(QColor("white"))
    p.drawPath(arrow)
    pen = QPen(QColor("white"), 9 * s, Qt.SolidLine, Qt.RoundCap)
    p.setPen(pen)
    p.drawLine(QPointF(32 * s, 104 * s), QPointF(96 * s, 104 * s))
    p.end()
    return img


if __name__ == "__main__":
    app = QGuiApplication(sys.argv)
    for n in (16, 32, 48, 128):
        draw(n).save(str(ROOT / "extension" / "icons" / f"{n}.png"))
    draw(256).save(str(ROOT / "vdl" / "gui" / "icon.png"))
    print("icônes générées")

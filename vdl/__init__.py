"""VDL — gestionnaire de téléchargements vidéo et fichiers."""

__version__ = "0.1.0"
APP_NAME = "VDL"
# Dépôt GitHub « pseudo/projet » qui publie les versions (vérification des mises à jour de VDL).
# Vide = pas de vérification.
UPDATE_REPO = "josiasapelete/vdl"

# Application compilée : un yt-dlp plus récent téléchargé par l'updater passe avant
# la copie embarquée. Doit s'exécuter avant tout « import yt_dlp ».
from .ytdlp_loader import install as _install_ytdlp  # noqa: E402

_install_ytdlp()

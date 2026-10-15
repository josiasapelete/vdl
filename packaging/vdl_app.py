"""Point d'entrée de l'application compilée (VDL.exe / VDL.app)."""

import os
import sys

# Application sans console : stdout/stderr valent None, ce que certaines
# bibliothèques (yt-dlp, ffmpeg) supportent mal. On les redirige vers le néant.
if sys.stdout is None:
    sys.stdout = open(os.devnull, "w", encoding="utf-8")
if sys.stderr is None:
    sys.stderr = open(os.devnull, "w", encoding="utf-8")

from vdl.gui.app import main  # noqa: E402

sys.exit(main())

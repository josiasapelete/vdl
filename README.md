# VDL — gestionnaire de téléchargements vidéo et fichiers

Télécharge les vidéos de YouTube, TikTok, Facebook, Instagram, X, Vimeo… (plus de 1800 sites
via [yt-dlp](https://github.com/yt-dlp/yt-dlp)), les flux HLS/DASH de n'importe quelle page, et
les fichiers classiques en **multi-segments** comme IDM.

## Installation (Windows, macOS, Linux)

Prérequis : Python 3.10+ et **ffmpeg** dans le PATH (Windows : `winget install Gyan.FFmpeg`).

```bash
python -m venv .venv
.venv\Scripts\activate            # macOS/Linux : source .venv/bin/activate
pip install -e ".[gui]"
```

## Utilisation

### Interface graphique
```bash
vdl gui            # ou double-clic sur VDL.bat
```
Collez un lien → choisissez Vidéo/MP3, la qualité, les sous-titres → **Télécharger**.
Fermer la fenêtre laisse VDL dans la zone de notification (il continue à recevoir les liens de
l'extension et du presse-papiers). Quitter : clic droit sur l'icône → Quitter.

### Ligne de commande
```bash
vdl get URL                      # demande la qualité
vdl get URL -q 1080              # 1080p max
vdl get URL -a                   # MP3
vdl get URL --subs fr,en         # sous-titres intégrés à la vidéo
vdl get URL --playlist           # toute la playlist
vdl get https://site/fichier.zip -n 16   # fichier direct, 16 connexions
vdl info URL                     # formats disponibles, sans télécharger
vdl list                         # file d'attente et historique
vdl resume                       # reprendre ce qui est en pause (Ctrl+C = pause)
vdl update                       # mettre à jour yt-dlp
vdl serve                        # serveur pour l'extension, sans interface
vdl config                       # voir les réglages ; vdl config segments 16 pour modifier
```

## Extension navigateur

**Chrome / Edge / Brave / Opera** : `chrome://extensions` → activer le *Mode développeur* →
*Charger l'extension non empaquetée* → choisir le dossier `extension/`.

**Firefox** : `about:debugging#/runtime/this-firefox` → *Charger un module temporaire* →
choisir `extension/manifest.json`.

Fonctions :
- panneau **⬇ Télécharger cette vidéo** affiché automatiquement dès qu'une vidéo est lue ou
  qu'un flux est capté (aussi dans les lecteurs intégrés en iframe et en plein écran), et au survol ;
- **n'importe quel site** : l'extension capte le flux réel pendant la lecture (MP4, HLS `.m3u8`,
  DASH `.mpd`) et l'envoie avec le bon Referer, même si yt-dlp ne connaît pas le site ;
- icône de l'extension : liste des médias détectés sur la page (MP4, HLS, DASH, MP3…) ;
- clic droit sur un lien, une vidéo ou la page → *Télécharger avec VDL*.

L'application (GUI ou `vdl serve`) doit être lancée. Elle écoute uniquement sur `127.0.0.1:9614`
et refuse les requêtes venant de sites web.

## Fonctionnalités

| | |
|---|---|
| Sites vidéo | yt-dlp (YouTube, TikTok, Facebook, Instagram, X, Vimeo, Dailymotion, Twitch…) |
| Fichiers directs | multi-segments avec découpage dynamique, reprise après fermeture |
| Flux HLS/DASH | fragments téléchargés en parallèle |
| Formats | MP4 (H.264 de préférence), MP3 128-320 kbps, choix de la qualité jusqu'à 4K/8K |
| Sous-titres | langues au choix, automatiques en option, intégrés au MP4 |
| Playlists | sélection des éléments, ou seulement la vidéo du lien |
| File d'attente | téléchargements simultanés réglables, pause/reprise/annulation, historique SQLite |
| Presse-papiers | un lien copié propose le téléchargement |
| Mises à jour | yt-dlp vérifié toutes les 12 h et mis à jour automatiquement |
| Contenus privés | réglage *Cookies du navigateur* (Chrome, Edge, Firefox…) |

## Architecture

```
vdl/
  extractor.py   analyse d'URL : yt-dlp ou fichier direct, qualités, playlist ; options yt-dlp
  segmented.py   téléchargeur multi-segments (Range HTTP, découpage dynamique, reprise)
  manager.py     file d'attente, threads, pause/reprise/annulation, persistance
  db.py          SQLite (file + historique)
  server.py      API locale pour l'extension
  updater.py     mise à jour automatique de yt-dlp
  cli.py         ligne de commande
  gui/           interface PySide6
extension/       extension Manifest V3 (Chrome, Edge, Firefox)
```

Données : `%APPDATA%\VDL` (Windows), `~/Library/Application Support/VDL` (macOS),
`~/.local/share/VDL` (Linux). Téléchargements : `~/Downloads/VDL` par défaut.

## Limites

- Les contenus protégés par **DRM** (Netflix, Disney+, Prime Video, Spotify…) ne sont pas pris en charge.
- Respectez le droit d'auteur et les conditions d'utilisation des sites.
- Mobile (Android/iOS) : le moteur `vdl` est prévu pour être réutilisé côté serveur (voir le plan multiplateforme).

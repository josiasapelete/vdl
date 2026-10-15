# Mettre VDL en ligne (gratuit)

Tout passe par GitHub : il héberge le code, **construit** l'installeur Windows et les APK
Android à chaque nouvelle version, et publie le site vitrine. Coût : 0 €.

Résultat : un site `https://TON-PSEUDO.github.io/vdl/` avec les boutons de téléchargement.

## 1. Une seule fois : créer le dépôt

1. Crée un compte sur https://github.com (gratuit).
2. Crée un dépôt **public** nommé `vdl` (bouton *New repository*, sans README).
3. Dans [site/config.js](site/config.js), remplace `TON-PSEUDO/vdl` par ton dépôt
   et mets ton adresse de contact.
4. Dans un terminal, depuis ce dossier :
   ```powershell
   git init
   git add .
   git commit -m "VDL 0.1.0"
   git branch -M main
   git remote add origin https://github.com/TON-PSEUDO/vdl.git
   git push -u origin main
   ```

## 2. Une seule fois : la clé de signature Android

Les APK sont signés avec la clé `packaging/android/vdl-release.jks` (créée sur ce PC).
**Sauvegarde ce dossier `packaging/android/` (clé USB, cloud privé).** Si tu perds la clé,
les utilisateurs devront désinstaller VDL pour installer une mise à jour.
Elle n'est volontairement **pas** envoyée sur GitHub (.gitignore).

Pour que GitHub puisse signer les APK, ajoute deux secrets
(dépôt → *Settings* → *Secrets and variables* → *Actions* → *New repository secret*) :

| Nom | Valeur |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | le résultat de la commande ci-dessous |
| `ANDROID_KEYSTORE_PASSWORD` | la ligne `storePassword=` de `packaging/android/keystore.properties` |

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("packaging\android\vdl-release.jks")) | Set-Clipboard
```
(la valeur est copiée dans le presse-papiers, colle-la dans le secret)

## 3. Une seule fois : activer le site

Dépôt → *Settings* → *Pages* → *Source* : **GitHub Actions**.
Le site se publie automatiquement (onglet *Actions* pour suivre).

## 4. Publier une version

1. Mets à jour le numéro dans [vdl/__init__.py](vdl/__init__.py) (`__version__ = "0.1.1"`).
2. Puis :
   ```powershell
   git commit -am "Version 0.1.1"
   git tag v0.1.1
   git push origin main --tags
   ```
3. GitHub construit tout (~15 min, onglet *Actions*) et crée la *Release*.
   Le site propose aussitôt les nouveaux fichiers, et les applications déjà installées
   signalent la mise à jour à leurs utilisateurs.

## Construire sur ce PC (sans GitHub)

- Windows : `powershell -ExecutionPolicy Bypass -File packaging\build_windows.ps1`
  → `dist\VDL-Setup-x.y.z.exe`
- Android (Android Studio ou JDK 17 + SDK Android) : `cd android` puis `gradlew assembleRelease`
  → `android\app\build\outputs\apk\release\`

# Changelog

Toutes les modifications notables de **StoryUnchained**.

Le format suit [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) et le versionnage
sémantique [SemVer](https://semver.org/lang/fr/).

## [0.1.1] — 2026-08-15

### Ajouté

- Sélecteur de dossier/fichier natif dans les réglages (Electron `dialog.showOpenDialog` exposé via
  IPC `dialog:openPath`), pour choisir le chemin de la bibliothèque et le fichier de base.
- Sync de la bibliothèque automatique au démarrage de l'application (en arrière-plan).
- Toast de résultat de sync affichant le nombre de packs synchronisés, invalides et en échec.

### Modifié

- Dialogue de réglages réécrit avec les **Signal Forms** (`@angular/forms/signals`).
- Le bouton « Sync library » des réglages déclenche le **sync classique** : la synchronisation des
  métadonnées non-officielles est désormais incluse dans le sync classique.
- Le répertoire des packs invalides est basé sur `defaultLibraryPath` (fini le chemin codé en dur
  `~/Documents/StudioKMP`).

### Supprimé

- Endpoint `POST /metadata/refresh-unofficial` et use case/adaptateurs dédiés
  (`SyncUnofficialMetadataUseCase`, ports et adaptateurs `UpdateUnofficialMetadata`,
  `LoadUnofficialMetadataFromFile`, `UnofficialJsonEntry`).
- Gestion du fichier `unofficial.json` (`Settings.unofficialDbPath`, lecture/écriture/nettoyage dans
  `MetadataStore`).
- Modale de progression de sync (`SyncProgressDialogComponent`).
- Bouton « Sync library » de la page de bibliothèque (remplacé par le sync automatique + réglages).

## [0.1.0] — 2026-08-15

### Ajouté

- **Backend Spring Boot (Kotlin)** (`api/`) :
  - Scan de la bibliothèque (formats Archive, RAW, FS) et indexation en base de données (H2).
  - Catalogue officiel Lunii (`official.json`).
  - Recherche, filtres, pagination, vignettes locales, édition de métadonnées, forks.
  - Détection Lunii (hotplug USB), transferts device ↔ bibliothèque, SSE `/devices/events`.
  - Conversion de formats Archive / RAW / FS.
  - Sortie complète des dépendances `studio-*` (readers/writers/chiffrement portés en Kotlin pur).
- **Frontend Angular** (`library-web/`) : bibliothèque, filtres, vignettes, panneau device, édition.
- **App desktop Electron** (`desktop/`) : backend + frontend packagés, JRE jlink embarqué,
  icône native, mac (arm64) + win (nsis) — natif **arm64** Apple Silicon (natif usb4java compilé).
- **Structure communautaire** : `CONTRIBUTING.md`, templates de PR/Issues, `CHANGELOG.md`, `ROADMAP.md`.

### Modifié

- Dépendances `studio-core` / `studio-metadata` / `studio-driver` supprimées (Kotlin pur + usb4java).
- Modules `server` et `shared` (Ktor/KMP legacy) supprimés.

### Licence

- Ajout de la **Non-Commercial Source-Available License** (voir `LICENSE`).

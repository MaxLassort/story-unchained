# Changelog

Toutes les modifications notables de **StoryUnchained**.

Le format suit [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) et le versionnage
sémantique [SemVer](https://semver.org/lang/fr/).

## [0.1.0] — 2026-08-15

### Ajouté

- **Backend Spring Boot (Kotlin)** (`api/`) :
  - Scan de la bibliothèque (formats Archive, RAW, FS) et indexation en base de données (H2).
  - Catalogue officiel Lunii (`official.json`) + métadonnées non officielles (`unofficial.json`).
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

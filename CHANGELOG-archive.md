# Changelog — Archive

Releases precedentes. Voir aussi [CHANGELOG.md](CHANGELOG.md) pour les versions recentes.

## [0.1.1] — 2026-08-15

### Ajoute

- Selecteur de dossier/fichier natif dans les reglages (Electron `dialog.showOpenDialog` expose via
  IPC `dialog:openPath`), pour choisir le chemin de la bibliotheque et le fichier de base.
- Sync de la bibliotheque automatique au demarrage de l'application (en arriere-plan).
- Toast de resultat de sync affichant le nombre de packs synchronises, invalides et en echec.

### Modifie

- Dialogue de reglages reecrit avec les **Signal Forms** (`@angular/forms/signals`).
- Le bouton « Sync library » des reglages declenche le **sync classique** : la synchronisation des
  metadonnees non-officielles desormais incluse dans le sync classique.
- Le repertoire des packs invalides est base sur `defaultLibraryPath` (fini le chemin code en dur
  `~/Documents/StudioKMP`).

### Supprime

- Endpoint `POST /metadata/refresh-unofficial` et use case/adapteurs dedies
  (`SyncUnofficialMetadataUseCase`, ports et adaptateurs `UpdateUnofficialMetadata`,
  `LoadUnofficialMetadataFromFile`, `UnofficialJsonEntry`).
- Gestion du fichier `unofficial.json` (`Settings.unofficialDbPath`, lecture/ecriture/nettoyage dans
  `MetadataStore`).
- Modale de progression de sync (`SyncProgressDialogComponent`).
- Bouton « Sync library » de la page de bibliotheque (remplace par le sync automatique + reglages).

## [0.1.0] — 2026-08-15

### Ajoute

- **Backend Spring Boot (Kotlin)** (`api/`) :
  - Scan de la bibliotheque (formats Archive, RAW, FS) et indexation en base de donnees (H2).
  - Catalogue officiel Lunii (`official.json`).
  - Recherche, filtres, pagination, vignettes locales, edition de metadonnees, forks.
  - Detection Lunii (hotplug USB), transferts device ↔ bibliotheque, SSE `/devices/events`.
  - Conversion de formats Archive / RAW / FS.
  - Sortie complete des dependances `studio-*` (readers/writers/chiffrement portes en Kotlin pur).
- **Frontend Angular** (`library-web/`) : bibliotheque, filtres, vignettes, panneau device, edition.
- **App desktop Electron** (`desktop/`) : backend + frontend packages, JRE jlink embarque,
  icone native, mac (arm64) + win (nsis) — natif **arm64** Apple Silicon (natif usb4java compile).
- **Structure communautaire** : `CONTRIBUTING.md`, templates de PR/Issues, `CHANGELOG.md`, `ROADMAP.md`.

### Modifie

- Dependances `studio-core` / `studio-metadata` / `studio-driver` supprimees (Kotlin pur + usb4java).
- Modules `server` et `shared` (Ktor/KMP legacy) supprimes.

### Licence

- Ajout de la **Non-Commercial Source-Available License** (voir `LICENSE`).

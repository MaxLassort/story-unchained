# Changelog

Toutes les modifications notables de **StoryUnchained**.

Le format suit [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) et le versionnage
semantique [SemVer](https://semver.org/lang/fr/).

> Les releases precedentes sont dans [CHANGELOG-archive.md](CHANGELOG-archive.md).

## [0.2.0] — 2026-09-14

### Ajoute

- **Wizard de creation de stories** : edition assistee en etapes (etape 1 : structure, etape 2 :
  ajout de chapitres) avec draft en memoire (`StoryDraftStore`).
- **Synthese TTS au moment de la sauvegarde** : les fichiers audio titre sont generes automatiquement
  a l'enregistrement de chaque etape du draft.
- **Moteur TTS multi-fournisseurs** : OpenAI, ElevenLabs et fallback gratuit, configurable dans les
  reglages.
- **Generation d'images de chapitres** depuis des icones Lucide (SVG) ou un numero de chapitre.
- **Endpoint `POST /story-draft`** pour la creation de draft et `PUT /story-draft/{id}/step` pour
  l'avancement par etapes.
- **Filtre de recherche officielle** avec use case dedie (`ListOfficialPacksUseCase`) et tests
  unitaires.
- **Reponse des snapshots device enrichie** : les thumbnails sont desormais incluses dans la reponse.
- **Televersement audio titre** : import de fichier audio titre pour les chapters via le frontend.
- **Audio de choix chapitre** : fichier `chapter-choice.mp3` integre a l'application.
- Tests unitaires sur `PackRepositoryAdapterFilter`, `SyncPacksServiceNormalizeThumbnail`,
  `SyncPacksServiceSyncFlow`, `ElevenLabsTtsAdapter`, `StoryDraftStore` et plus.

### Modifie

- **Refactor best-practices transversal** front/back :
  - Cancellation des `CoroutineScope` non geres via `@PreDestroy` (`DeviceController`,
    `PackController`, `SyncPacksService`).
  - Tous les endpoints `StoryDraftController` sont desormais `suspend`.
  - Gestion d'erreurs centralisee via `GlobalExceptionHandler` (exceptions typees :
    `DraftIncompleteException` → 409, `NoSuchElementException` → 404).
  - Ajout du DTO `FinalizedPackResponse`.
- **Refactor du sync** : `SyncPacksService` reecrit avec SSE (`SseFlowEmitter`), suppression des
  entites `SyncJob`/`InvalidPackMoveQueue` en base.
- **StoryDraftController allege** : la logique de creation est deplacee dans les use cases
  (`CreateStoryUseCase`, `StoryDraftStore`).
- **Frontend** : `StoryDraftService` simplifie, theme mis a jour, erreurs UI pour le TTS
  indisponible.
- Nettoyage des fichiers de plans (`plans/`) et des documents redondants.

### Corrige

- Recherche de packs officiels corrigee (filtrage et pagination).
- Conversion audio des enregistrements.
- Erreurs multiples sur les devices Lunii.
- Thumbnails manquantes dans la reponse des snapshots device.

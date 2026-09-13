# Documentation — StoryUnchained

> Documentation technique du projet **StoryUnchained** — fork de [STUdio](https://github.com/marian-m12l/studio) pour la gestion de packs Lunii.

---

## Structure du projet

| Module | Rôle | Technologie |
|---|---|---|
| `api/` | Backend : sync, metadata, conversion, driver USB, SSE | Spring Boot (Kotlin), port 9090 |
| `library-web/` | Frontend : bibliothèque, filtres, vignettes, panneau device | Angular |
| `desktop/` | App de bureau : backend + frontend packagés | Electron |

---

## Documentation technique

### Concepts & fonctionnalités

| Doc | Description | Statut |
|---|---|---|
| [Création d'histoires](story-creation-flow.md) | Pipeline complet : draft → zip STUdio → bibliothèque | Actif |
| [Brouillon d'histoire (API)](story-draft-api.md) | API REST du draft sur disque (cycle de vie, endpoints, cas d'usage) | Actif |
| [Moteur TTS](tts-engine.md) | Architecture TTS BYOK : OpenAI, ElevenLabs, fallback gratuit | Actif |
| [Configuration TTS](tts-settings.md) | Stockage et configuration des clés API TTS | Actif |
| [Images de chapitre](chapter-image-generator.md) | Génération d'images : chiffres, icônes Lucide, conversion SVG | Actif |
| [Métadonnées officielles](official-metadata-fetch.md) | Fetch et cache du catalogue Lunii (official.json) | Actif |

### Format des packs

| Doc | Description |
|---|---|
| [README format](format/README.md) | Vue d'ensemble : qu'est-ce qu'un pack, les 3 formats, cycle de vie |
| [Modèle de nœuds](format/pack-model.md) | Types, champs, transitions, types éditeur |
| [Format ARCHIVE (zip)](format/studio-archive-format.md) | Zip STUdio : story.json, assets, vignette |
| [Format LUNII (folder/FS)](format/lunii-folder-format.md) | Dossier appareil : index binaires (ni/li/ri/si), assets |
| [Audio](format/audio.md) | Formats, compressions, conversions par format |
| [Images](format/images.md) | Formats, BMP RLE4, conversions par format |
| [Stockage Lunii](format/device-storage.md) | Layout disque, index, chiffrement XXTEA/AES |

---

## Plans

| Plan | Description | Statut |
|---|---|---|
| [Création d'histoires](../plans/story-creation-plan.md) | Plan complet en 6 étapes (draft + TTS + images + finalisation) | Terminé |
| [Refactor TTS → validation](../plans/tts-to-draft-validation-refactor.md) | Synthèse TTS à la validation des steps, consolidation endpoints | Terminé |
| [Refactor recherche packs](../plans/pack-search-refactor.md) | Filtres nom, âge, toggle officiel, display catalog | En cours |
| [Factorisation sync](../plans/facto.md) | Suppression jobs/queues, SSE unifié | Terminé |

---

## Liens utiles

- **[README.md](../README.md)** — Présentation du projet, setup, build
- **[CHANGELOG.md](../CHANGELOG.md)** — Journal des versions
- **[ROADMAP.md](../ROADMAP.md)** — Feuille de route
- **[todo.md](../todo.md)** — Tâches en cours
- **[AGENTS.md](../AGENTS.md)** — Instructions agents (graft)

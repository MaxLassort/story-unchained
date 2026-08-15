# Roadmap

Feuille de route de **StoryUnchained**. Les fonctionnalités sont regroupées par thème et priorité.
Chaque fonctionnalité peut devenir une **Issue** puis une **feature branch** → **PR** (voir `CONTRIBUTING.md`).

> **Légende priorités** : 🔴 haute · 🟠 moyenne · 🟢 basse

---

## 📦 Gestion de la bibliothèque

| Fonctionnalité | Description | Priorité | Statut |
|---|---|---|---|
| Scan + BDD | Scanner la bibliothèque et indexer les packs (H2) | 🔴 | ✅ Fait |
| Recherche / filtres de base | Recherche, officiel/non officiel, fork, pagination | 🔴 | ✅ Fait |
| **Import de packs** | Importer des packs dans la bibliothèque (glisser-déposer, copie depuis un dossier, depuis un fichier `.zip`/`.pack`, depuis une archive non triée) | 🔴 | 🟡 En cours |
| **Filtres plus précis** | Filtres combinables : format (Archive/RAW/FS), âge, durée, nombre d'histoires, langue, fabricant, présence sur l'appareil, avec/sans vignette, favoris | 🟠 | ⬜ |
| Vignettes | Extraction/génération de vignettes pour tous les formats, aperçu inline | 🟠 | ⬜ |

## 🔄 Synchronisation & appareils

| Fonctionnalité | Description | Priorité | Statut |
|---|---|---|---|
| Sync bibliothèque | Sync incrémental de la bibliothèque en tâche de fond | 🔴 | ✅ Fait |
| Transferts Lunii | Copier / supprimer / lire les packs depuis la Lunii (raw + FS) | 🔴 | ✅ Fait |
| **Synchronisation multiple (file d'attente)** | Planifier plusieurs sync/transferts (device → bibliothèque, bibliothèque → device) et les exécuter **en file d'attente** avec progression et reprise des échecs | 🟠 | ⬜ |
| Multi-appareils | Suivi de plusieurs Lunii (déjà des snapshots), bascule entre appareils | 🟢 | ⬜ |

## 🔄 Conversion & médias

| Fonctionnalité | Description | Priorité | Statut |
|---|---|---|---|
| Conversion de formats | Archive / RAW / FS selon l'appareil cible | 🔴 | ✅ Fait |
| **Conversion automatique de livre audio** | Convertir un livre audio (MP3/OGG/WAV, chapitres) en pack Lunii : découpage par chapitres, génération des `stageNodes`, conversion audio mono 44,1 kHz | 🟠 | ⬜ |
| Optimisation des assets | Compression audio/image, nettoyage des packs volumineux | 🟢 | ⬜ |

## 🎧 Lecture & prévisualisation

| Fonctionnalité | Description | Priorité | Statut |
|---|---|---|---|
| **Visualiser un pack (lecture audio)** | Prévisualiser un pack : lecture de l'audio d'un stage, navigation dans les nœuds (stage/action), affichage de l'image — sans Lunii | 🟠 | ⬜ |
| Éditeur de métadonnées | Édition titre/description/vignette (existant) + âge/durée/langue | 🟠 | ✅ Partiel |

## 🛠️ Qualité & plateforme

| Fonctionnalité | Description | Priorité | Statut |
|---|---|---|---|
| App desktop | Electron (backend + frontend + JRE) | 🔴 | ✅ Fait |
| Windows | Packaging `.exe` (nsis) sur machine Windows / CI | 🟠 | ⬜ |
| Signing & distribution | Signing macOS/Windows, release automatiques | 🟢 | ⬜ |
| Tests | Couverture readers/writers/drivers, E2E | 🟠 | ⬜ |
| Docker | Image serveur pour déploiement headless | 🟢 | ⬜ |

---

## Suggestions de prochaines Issues

1. **Import de packs** — `feature/import-packs`
2. **Livre audio → pack Lunii** — `feature/audiobook-conversion`
3. **Sync en file d'attente** — `feature/queue-sync`
4. **Lecture audio d'un pack** — `feature/pack-preview`
5. **Filtres avancés** — `feature/advanced-filters`

Chaque fonctionnalité est une Issue → branche `feature/*` → PR → review → merge par le maintainer.

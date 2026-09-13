# Finalisation d'une histoire : draft → zip STUdio → bibliothèque

> Pipeline complet de transformation d'un brouillon en pack STUdio indexé en bibliothèque.

---

## Métadonnées

- **Statut** : Actif
- **Dernière mise à jour** : 2026-08-21
- **Liens** : [Brouillon d'histoire](story-draft-api.md) · [Format ARCHIVE](format/studio-archive-format.md) · [Plan étape 5](../plans/story-creation-plan.md)

---

## 1. Pipeline

```
draft (dossier temp)                     bibliothèque + BDD
┌─────────────────────────┐  finalize   ┌──────────────────────────────┐
│ draft.json + binaires   │ ──────────► │ {uuid}.zip (ARCHIVE)          │
│ (title, cover, chapters,│             │ Pack(id=uuid, variants=[ARCHIVE]) │
│  thumbnail, audios)     │             │ draft purgé                   │
└─────────────────────────┘             └──────────────────────────────┘
        │
        └── POST /packs/{id}/convert (ARCHIVE → RAW/FS) pour la Lunii
```

- **Rien n'est écrit en bibliothèque/BDD avant la finalisation** : le draft vit entièrement dans le dossier temp.
- La finalisation est **atomique côté draft** : si le draft est incomplet (409), rien n'est sauvegardé.

## 2. API

```
POST /stories/drafts/{id}/finalize
  200 {packId}   — pack créé, indexé, draft purgé
  409            — draft incomplet (message listant les champs manquants)
  404            — draft inconnu
```

## 3. Règles de validation (409)

| Champ | Obligatoire | Notes |
|---|---|---|
| `title` | ✅ | non vide |
| `thumbnail` | ✅ | sert de `meta/thumbnail.png` |
| `cover` | ✅ | image du squareOne (page principale Lunii) |
| `chapters` | ✅ | au moins un chapitre |
| `chapters[].name` | ✅ | non vide |
| `chapters[].narration` | ✅ | l'audio du chapitre lui-même |

L'image d'un chapitre, le titre audio et l'audio du pack sont **optionnels** (fallbacks).

## 4. Construction du graphe

Un histoire linéaire est encodée avec le **menu classique Lunii** (question → options → histoire) :

```
cover (squareOne, type=cover)
   │ okTransition ──► actionQ (menu.questionaction) ──► menuQuestion (menu.questionstage)
   │
menuQuestion (audio = prompt, autoplay=true)
   │ okTransition ──► actionOptions (menu.optionsaction)
   │
option k (menu.optionstage : image du chapitre + titre audio, wheel=true, ok=true)
   │ okTransition ──► storyAction_k ──► story k (type=story)
   │
story k (audio = narration seule, autoplay=true, ok=true, home=true)
   │ okTransition / fin de l'audio ──► story k+1
   │ homeTransition ──► actionQ ──► menuQuestion
   │
story N (dernier)
   │ okTransition / fin de l'audio ──► actionQ ──► menuQuestion
   │ homeTransition ──► actionQ ──► menuQuestion
```

### Règles de transition

- **OK = avant, HOME = arrière** — les deux ne sont jamais redondants.
- Chaque `stageNode` a un `okTransition` valide : la Lunii affiche « error card » sur un nœud dont l'OK est indéfini.
- **menuQuestion** : audio = prompt de sélection (pas le titre du livre, déjà lu sur la cover).
- **option k** : une page par chapitre — le menu lit **chaque titre** pendant la sélection.
- **story k** : le titre n'est **pas relu** après OK : il a déjà été annoncé à la sélection.

## 5. Audio

### Audio du pack (cover)

1. `titleAudioFile` uploadé (bytes d'origine) s'il existe
2. sinon TTS de `titleText` s'il existe
3. sinon TTS du `title`

### Audio d'un chapitre

Deux audios par chapitre :
- **option (menu)** : le **titre** du chapitre — `titleAudioFile` uploadé **ou** TTS de `titleText` **ou** TTS du nom.
- **story (chapitre)** : la **narration seule** (`narrationAudioFile`, normalisée en MP3).

### Format audio compatible Lunii

`AudioConversion.anyToMp3` encode en **CBR 128 kbps mono 44,1 kHz** (sans tags ID3). Le décodeur MP3 de la Lunii rejette les frames bas débit (32 kbps).

## 6. Image d'un chapitre (hiérarchie)

1. `imageFile` uploadé (PNG/JPEG) → tel quel
2. sinon `iconId` → icône Lucide rendue blanc sur noir 320×240 (`SvgIconRenderer`)
3. sinon → chiffre du chapitre généré (`ChapterImageGenerator`)

La cover est utilisée telle quelle. La thumbnail est ré-encodée en PNG et injectée dans `meta/thumbnail.png`.

## 7. Structure du zip

```
{uuid}.zip
├── story.json        (graphe v1, assets nommés par SHA-1 + extension)
├── assets/
│   ├── {sha1}.mp3    (audios, MP3 mono 44,1 kHz)
│   ├── {sha1}.png    (images de nœud)
└── meta/
    └── thumbnail.png (vignette bibliothèque)
```

Le pack est enregistré via `PackRepositoryPort.savePack` avec `id = {uuid}`, `metadata` (titre, description, thumbnail, `version=1`, `official=false`), `variants = [ARCHIVE]`.

## 8. Références code

| Rôle | Fichier |
|---|---|
| Finalisation | `pack/service/CreateStoryUseCase.kt` (`finalize(draftId)`) |
| Controller | `pack/web/StoryDraftController.kt` (`POST /stories/drafts/{id}/finalize`) |
| Writer zip | `pack/format/writer/ArchiveStoryPackWriter.kt` |
| Conversion audio | `pack/format/utils/AudioConversion.kt` (`anyToMp3`, `anyToWave`) |
| Conversion image | `pack/format/utils/ImageConversion.kt` (`anyToRLECompressedBitmap`) |

## 9. Checklist de tests manuels

1. Créer une histoire (titre, thumbnail, cover, audio pack, chapitres complets) → finaliser → 200.
2. Vérifier que le zip est dans la bibliothèque et que `meta/thumbnail.png` est servi.
3. Convertir en FS (`POST /packs/{id}/convert`) et copier sur la Lunii → navigation cover → menu → chapitres → pas de « error card ».
4. Draft incomplet (pas de narration) → finalize → 409, aucun fichier en bibliothèque/BDD.
5. Après finalisation, un nouveau `GET /stories/drafts/current` renvoie 404 (draft purgé).

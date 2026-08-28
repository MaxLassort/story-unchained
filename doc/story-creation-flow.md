# Finalisation d'une histoire : draft → zip STUdio → bibliothèque

> Étape 5 du plan de création d'histoires. Transforme un brouillon complet (voir
> `doc/story-draft-api.md`) en **zip STUdio** écrit dans la bibliothèque et indexé en BDD.
> La conversion RAW/FS pour l'appareil se fait ensuite via l'existant
> `POST /packs/{id}/convert`.

## Pipeline

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

- **Rien n'est écrit en bibliothèque/BDD avant la finalisation** : le draft vit
  entièrement dans le dossier temp (doc `story-draft-api.md`).
- La finalisation est **atomique côté draft** : si le draft est incomplet (409), rien n'est
  sauvegardé ; en cas de succès, le zip est écrit + indexé **une seule fois**, puis le
  draft est purgé.

## API

```
POST /stories/drafts/{id}/finalize
  200 {packId}   — pack créé, indexé, draft purgé
  409            — draft incomplet (message listant les champs manquants)
  404            — draft inconnu
```

## Règles de validation (409)

| Champ | Obligatoire | Notes |
|---|---|---|
| `title` | ✅ | non vide |
| `thumbnail` | ✅ | sert de `meta/thumbnail.png` |
| `cover` | ✅ | image du squareOne (page principale Lunii) |
| `chapters` | ✅ | au moins un chapitre |
| `chapters[].name` | ✅ | non vide |
| `chapters[].narration` | ✅ | l'audio du chapitre lui-même |

L'image d'un chapitre, le titre audio et l'audio du pack sont **optionnels** (fallbacks,
voir plus bas).

## Construction du graphe linéaire

Un histoire linéaire (cover → chapitre 1 → … → chapitre N) est encodée ainsi
(`CreateStoryUseCase.finalize`) :

```
cover (squareOne, type=COVER)
   │ okTransition ──► action #1 ── option 0 ──► chapitre 1 (type=STORY)
   │                                               │ okTransition ──► action #2 ──► chapitre 2
   │                                               │                                     │ okTransition
   │                                               │                                     ▼
   │                                               │                        (dernier chapitre)
   │                                               │ okTransition ──► action #fin ── option 0 ──► cover
```

- La cover a `okTransition → action #1` ; chaque chapitre k a `okTransition → action #(k+1)`
  dont l'unique option est le chapitre suivant ; **le dernier chapitre a un `okTransition`
  valide** vers un action node de fin dont l'unique option est **la cover** (boucle).
- `controlSettings` par défaut : `ok=on, home=on, pause=on` (voir § « Rôle des transitions »).
- Les types d'enrichissement (champs éditeur, ignorés par la Lunii) : cover → `COVER`,
  chapitre → `STORY`, action → `ACTION`.

### ⚠️ Le bug « error card » et la règle des transitions

Chaque `stageNode` d'un pack **doit avoir un `okTransition` valide**. En laissant le
dernier chapitre sans transition (`okTransition=null` → `ok=(-1,-1,-1)` en FS), la Lunii
affiche **« error card »** quand l'histoire atteint ce nœud (typiquement après avoir
appuyé sur OK à la fin du dernier chapitre). C'est la cause du premier bug rencontré.

Les packs officiels/convertis qui fonctionnent respectent tous cette règle : **aucun
`stageNode` n'a un OK non défini**.

## Rôle des transitions (analyse des packs existants)

Observé sur les packs qui marchent (Hayat, Disney, packs convertis) :

| | Nœuds interactifs (menu/sélection) | Nœuds d'histoire (chapitres) |
|---|---|---|
| `okTransition` | ✅ **toujours présent** — OK confirme le choix et **avance** | ✅ **toujours présent** — OK **avance** (à l'étape suivante) ou revient à l'origine |
| `homeTransition` | souvent `null` | présent — HOME retourne au menu/parent |
| `wheel` | on | off |
| `ok` (bouton) | on | off |
| `home` | — | on |
| `pause` | off | on |
| `autoplay` | off | on |

- **Le rôle principal de `okTransition` est « passer à l'étape suivante »** : sur un menu,
  OK sélectionne une option et avance vers l'histoire ; sur un chapitre (story), OK avance
  vers la suite du récit (et, en fin d'histoire, revient au point de départ / au menu).
- Le **`homeTransition`** est la transition *retour* (sortie vers le menu/parent).
- Les deux transitions ne sont **jamais redondantes** : OK = avant, HOME = arrière.
- Dans notre graphe linéaire, le dernier chapitre a un OK valide qui boucle vers la cover :
  à la fin du récit, OK relance depuis la cover (comportement des histoires sans menu).

> Référence format : `doc/pack-format-archive.md` § 5 (Transitions).
> **Référence nœuds** (format de chaque node + rôle de chaque option) : `doc/story-nodes.md`.

## Audio

### Audio du pack (cover)

1. `titleAudioFile` uploadé (bytes d'origine) s'il existe
2. sinon TTS de `titleText` s'il existe
3. sinon TTS du `title`

### Audio d'un chapitre

`audio_chapitre = concat(titre, narration)` où :

- `titre` = `titleAudioFile` uploadé (bytes d'origine) **ou** TTS de `titleText` **ou** TTS
  du nom du chapitre (hiérarchie)
- `narration` = le fichier uploadé (`narrationAudioFile`)

Le concat décodage PCM → concaténation → ré-encodage MP3 : les deux sources sont normalisées
en WAV mono 32 kHz (`AudioConversion.anyToWave`), leurs PCM concaténés, re-enveloppés en WAV,
puis ré-encodés via `AudioConversion.anyToMp3`.

### ⚠️ Format audio compatible Lunii

Le décodeur MP3 de la Lunii exige un **bitrate minimum** (~64 kbps) : les frames très bas
débit (32 kbps) produites par le VBR par défaut de jump3r causent un **« error card »
intermittent**. `AudioConversion.anyToMp3` encode donc en **CBR 128 kbps mono 44,1 kHz**
(mono 44,1 kHz sans tags ID3 : exigences du format, voir `FsStoryPackWriter`). C'était la
deuxième cause suspectée du « error card » (la vraie cause était la transition OK manquante,
mais le CBR 128 reste le format sûr).

## Image d'un chapitre (hiérarchie)

1. `imageFile` uploadé (PNG/JPEG) → tel quel
2. sinon `iconId` → icône Lucide rendue blanc sur noir 320×240 (`SvgIconRenderer`)
3. sinon → chiffre du chapitre généré (`ChapterImageGenerator`)

La cover (`coverFile`) est utilisée telle quelle pour le squareOne. La thumbnail
(`thumbnailFile`) est ré-encodée en **PNG** et injectée dans `meta/thumbnail.png` via
`UpdatePackFileMetadataPort` (la bibliothèque la sert comme `image/png`).

## Fichiers / structure du zip

```
{uuid}.zip
├── story.json        (graphe v1, assets nommés par SHA-1 + extension)
├── assets/
│   ├── {sha1}.mp3    (audios, MP3 mono 44,1 kHz)
│   ├── {sha1}.png    (images de nœud — la conversion FS les rend BMP 4bpp RLE)
└── meta/
    └── thumbnail.png (vignette bibliothèque)
```

Le pack est enregistré via `PackRepositoryPort.savePack` avec :
- `id = {uuid}` (nouveau UUID généré à la finalisation)
- `metadata` : titre, description, thumbnail (data-URI PNG), `version=1`,
  `nightModeAvailable=true`, `official=false`
- `variants = [ARCHIVE → {libraryPath}/{uuid}.zip]`

## Où

- `pack/service/CreateStoryUseCase.kt` : `finalize(draftId)` — validation, TTS, graphe,
  writer, thumbnail, écriture bibliothèque, `savePack`, purge du draft ; exceptions
  `DraftIncompleteException` (→ 409) et `NoSuchElementException` (→ 404).
- `pack/web/StoryDraftController.kt` : `POST /stories/drafts/{id}/finalize`.
- `pack/format/writer/ArchiveStoryPackWriter.kt` : writer du zip (inchangé).
- `pack/format/utils/AudioConversion.kt` : `anyToMp3` (CBR 128) et `anyToWave` (concat).
- `pack/format/utils/ImageConversion.kt` : `anyToRLECompressedBitmap` (BMP FS), utilisé à
  la conversion ARCHIVE → FS (inchangé).

## Checklist de tests manuels

1. Créer une histoire (titre, thumbnail, cover, audio pack, chapitres complets) → finaliser
   → 200 + navigation vers `/packs/{id}` dans le front.
2. Vérifier que le zip est dans la bibliothèque et que `meta/thumbnail.png` est servi.
3. Convertir en FS (`POST /packs/{id}/convert`) et copier sur la Lunii → l'histoire joue,
   **OK sur le dernier chapitre revient à la cover sans « error card »**.
4. Draft incomplet (pas de narration) → finalize → 409, aucun fichier en bibliothèque/BDD.
5. Après finalisation, un nouveau `GET /stories/drafts/current` renvoie 404 (draft purgé).
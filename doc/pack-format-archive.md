# Format STUdio d'un pack « archive » (zip)

Documentation du format de pack **archive** utilisé par StoryUnchained (`.zip` / `.pack`), basée sur
`ArchiveStoryPackWriter` / `ArchiveStoryPackReader` (`api/src/main/kotlin/com/maxlass/studio/pack/format/`).
Ce format est le format **STUdio** ([marian-m12l/studio](https://github.com/marian-m12l/studio),
`studio-core`) : il a été reverse-engineered par la communauté STUdio pour les packs Lunii — ce
**n'est pas** un format publié officiellement par Lunii. StoryUnchained en est un portage Kotlin pur.

> ⚠️ **Distinction importante** : le *format de pack* (structure `story.json`, nœuds, assets) est le
> format **STUdio**. Le *catalogue de métadonnées* `official.json` (téléchargé depuis l'API Lunii,
> §10.4) est bien un catalogue Lunii, mais il ne définit pas le format du pack.

---

## 1. Vue d'ensemble

Un pack archive est un fichier **zip standard** contenant :

| Entrée (entry) | Rôle | Obligatoire |
|---|---|---|
| `story.json` | Graphe de l'histoire (nœuds, transitions, métadonnées) | ✅ Oui |
| `assets/` | Fichiers image/audio référencés par `story.json` | ✅ Oui (si des assets sont référencés) |
| `meta/thumbnail.png` | Vignette du pack (écrite par StoryUnchained lors de l'update des métadonnées) | ⛔ Non |
| `thumbnail.png` | Vignette héritée (ancien emplacement racine, lu en fallback) | ⛔ Non |

> Un zip **sans** `story.json` n'est pas reconnu comme un pack (le lecteur retourne `null`).

> **Spécificité StoryUnchained** : réimplémentation **pure Kotlin** du format STUdio
> (`studio-core`), via kotlinx.serialization et le `ZipOutputStream` du JDK au lieu de
> gson/commons-compress. Structure identique — détaillé en §11.

L'UUID du pack **n'est pas** dans un champ dédié : il est dérivé du premier `stageNode`
(`uuid` du nœud `squareOne`). Deux packs sont donc identiques si leurs nœuds ont les mêmes UUID.

---

## 2. `story.json`

JSON racine (le writer utilise `prettyPrint`) :

```json
{
  "format": "v1",
  "title": "Mon Histoire",
  "description": "Une jolie histoire",
  "version": 1,
  "nightModeAvailable": true,
  "stageNodes": [ ... ],
  "actionNodes": [ ... ]
}
```

| Champ | Type | Description |
|---|---|---|
| `format` | string | Version du format, toujours `"v1"` |
| `title` | string | Titre du pack (métadonnées éditeur, pas affiché sur la Lunii) |
| `description` | string? | Description du pack (optionnel) |
| `version` | int | Version du format interne |
| `nightModeAvailable` | bool | Mode nuit disponible (fonctionnalité Lunii 2) |
| `stageNodes` | array | Nœuds de scène (voir §3) |
| `actionNodes` | array | Nœuds de choix (voir §4) |

> Les champs `title`, `description`, `version`… peuvent être modifiés sans réécrire les assets
> (cf. `UpdateZipMetadataAdapter`).

---

## 3. Nœuds de scène (`stageNodes`)

Un `stageNode` est une **page** : une image affichée, un audio joué, et des transitions.

```json
{
  "uuid": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "name": "Chapitre 1",
  "type": "story",
  "groupId": "grp-1",
  "position": { "x": 0, "y": 0 },
  "squareOne": true,
  "image": "e8f2a1c9...png",
  "audio": "3b7d9e10...mp3",
  "okTransition": { "actionNode": "uuid-action-node", "optionIndex": 0 },
  "homeTransition": null,
  "controlSettings": { "wheel": true, "ok": true, "home": false, "pause": true, "autoplay": false }
}
```

### 3.1 Champs

| Champ | Type | Description |
|---|---|---|
| `uuid` | string | Identifiant unique du nœud (UUDI du stageNode) |
| `name` | string? | Nom du nœud (métadonnées éditeur) |
| `type` | string? | Type éditeur : `stage`, `action`, `cover`, `story`, `story.storyaction`, `menu.*`… |
| `groupId` | string? | Groupe éditeur (optionnel) |
| `position` | {x,y}? | Position dans l'éditeur (optionnel) |
| `squareOne` | bool | `true` **uniquement sur le 1er nœud** (page d'accueil du pack) — écrit par le writer sur l'index 0 |
| `image` | string? | Nom du fichier image dans `assets/`, ou `null` |
| `audio` | string? | Nom du fichier audio dans `assets/`, ou `null` |
| `okTransition` | object? | Transition déclenchée par la touche OK (voir §5) |
| `homeTransition` | object? | Transition déclenchée par la touche HOME (voir §5) |
| `controlSettings` | object | Touches actives sur ce nœud (voir §3.2) |

> `controlSettings` est **requis** : le lecteur lève une erreur s'il est absent.

### 3.2 `controlSettings`

| Champ | Type | Signification |
|---|---|---|
| `wheel` | bool | Molette active (navigation entre options) |
| `ok` | bool | Touche OK active |
| `home` | bool | Touche HOME active (retour au menu) |
| `pause` | bool | Touche pause active |
| `autoplay` | bool | Saut automatique (sans pression de touche) |

---

## 4. Nœuds de choix (`actionNodes`)

Un `actionNode` est un **point de choix** : il propose une liste d'options, chacune pointant vers
un `stageNode`. Il n'a ni image ni audio.

```json
{
  "id": "uuid-action-node",
  "name": "Menu chapitres",
  "type": "menu.optionsaction",
  "options": [
    "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "f2g3h4i5-j6k7-8901-efgh-ij2345678901"
  ]
}
```

| Champ | Type | Description |
|---|---|---|
| `id` | string | Identifiant unique du nœud de choix (référencé par les transitions) |
| `name` | string? | Nom du nœud (métadonnées éditeur) |
| `type` | string? | Type éditeur (ex. `menu.optionsaction`) |
| `options` | array\<string\> | UUID des `stageNode` proposés (ordre = ordre molette) |

> Le `id` d'un actionNode est **local au zip** : il n'a pas besoin d'être un UUID global.
> Le writer en génère un (`UUID.randomUUID()`) lors de l'écriture.

---

## 5. Transitions

Une transition relie un `stageNode` à l'une des options d'un `actionNode` :

```json
{ "actionNode": "uuid-action-node", "optionIndex": 0 }
```

| Champ | Type | Description |
|---|---|---|
| `actionNode` | string | `id` de l'`actionNode` cible |
| `optionIndex` | int | Index de l'option sélectionnée dans `actionNode.options` |

Le flux de lecture d'un pack STUdio typique :

```
stageNode (cover, squareOne)
   │ okTransition ──► actionNode (menu chapitres)
   │                      option 0 ──► stageNode (chapitre 1)
   │                      option 1 ──► stageNode (chapitre 2)
   │ homeTransition ──► actionNode (menu)
```

---

## 6. Assets (`assets/`)

Les fichiers image/audio sont stockés dans `assets/`, **nommés par le SHA-1 hexadécimal de leur
contenu + extension** (déduplication automatique : deux nœuds partageant le même fichier
pointent vers la même entrée).

### 6.1 Types supportés

| Type | Extension | MIME | Notes |
|---|---|---|---|
| Image | `.bmp` | `image/bmp` | |
| Image | `.png` | `image/png` | |
| Image | `.jpg` / `.jpeg` | `image/jpeg` | |
| Audio | `.wav` | `audio/x-wav` | |
| Audio | `.mp3` | `audio/mpeg` | |
| Audio | `.ogg` / `.oga` | `audio/ogg` | Lecture seule (pas d'encodage OGG) |

> Le format d'un asset est déduit de son **extension** à la lecture. Les MIME ci-dessus sont
> uniquement utilisés par le writer pour choisir l'extension.

### 6.2 Exemple

```
pack.zip
├── story.json
├── meta/thumbnail.png
└── assets/
    ├── e8f2a1c9...png          # image du cover
    ├── 3b7d9e10...mp3          # audio du cover
    ├── 9c4e6a2b...png          # image du chapitre 1
    └── 7f1a8c3d...mp3          # audio du chapitre 1
```

---

## 7. Vignette (`thumbnail`)

- Emplacement moderne : `meta/thumbnail.png` (préféré par `findThumbnailEntry`).
- Emplacement hérité : `thumbnail.png` à la racine (fallback).
- Écrite par StoryUnchained via `UpdateZipMetadataAdapter` lors d'un update de métadonnées
  (upload depuis le front, `PATCH /packs/{id}/thumbnail`).

---

## 8. Exemple complet minimal

```json
{
  "format": "v1",
  "title": "Mon Histoire",
  "description": "Une description",
  "version": 1,
  "nightModeAvailable": true,
  "actionNodes": [
    { "id": "action-1", "options": ["stage-2"] }
  ],
  "stageNodes": [
    {
      "uuid": "stage-1",
      "squareOne": true,
      "name": "Cover",
      "type": "cover",
      "image": "img.bmp",
      "audio": "a.mp3",
      "okTransition": { "actionNode": "action-1", "optionIndex": 0 },
      "controlSettings": { "wheel": true, "ok": true, "home": false, "pause": true, "autoplay": false }
    },
    {
      "uuid": "stage-2",
      "name": "Chapitre 1",
      "type": "story",
      "controlSettings": { "wheel": true, "ok": true, "home": true, "pause": true, "autoplay": false }
    }
  ]
}
```

Fichiers : `story.json`, `assets/img.bmp`, `assets/a.mp3` (exemple repris des tests `PackFixtures`).

---

## 9. Contraintes pour un pack jouable sur Lunii

Ces contraintes ne s'appliquent pas au format archive lui-même, mais sont **requises avant
conversion vers le format FS** (`archiveToFs`) :

| Ressource | Contrainte FS |
|---|---|
| Image | BMP **320×240**, **4 bits (16 couleurs)**, compression **RLE4** |
| Audio | MP3 **mono 44,1 kHz**, **sans tag ID3** |

Les conversions sont disponibles dans `AudioConversion` / `ImageConversion` (`pack/format/utils/`)
et déjà utilisées par `StudioCorePackFormatConverterAdapter`.

---

## 10. Métadonnées (spécifiques StoryUnchained)

Le format STUdio (de base) ne porte que les champs `title` / `description` au niveau pack.
StoryUnchained **étend et exploite les métadonnées** à plusieurs niveaux : dans le zip,
en base H2, et via le catalogue Lunii officiel.

### 10.1 Champs étendus dans `story.json` (lecture + écriture)

En plus des champs §2, StoryUnchained lit/écrit des champs **optionnels propres à la
bibliothèque** (absents des packs STUdio bruts) :

```json
{
  "format": "v1",
  "title": "Mon Histoire",
  "description": "Une jolie histoire",
  "locale": "fr_FR",
  "ageMin": 3,
  "ageMax": 7,
  "durationMs": 1250000,
  "storyCount": 5,
  ...
}
```

| Champ | Type | Rôle |
|---|---|---|
| `locale` | string? | Langue (ex. `fr_FR`, `en_US`) — longueur max 10 en BDD |
| `ageMin` | int? | Âge minimum conseillé |
| `ageMax` | int? | Âge maximum conseillé |
| `durationMs` | int? | Durée totale en millisecondes |
| `storyCount` | int? | Nombre d'histoires du pack |

Lecture : `MetaDataReaderAdapter.readExtendedArchiveMetadata` (re-parse `story.json` après
`readMetadata`). Écriture : `UpdateZipMetadataAdapter.modifyStoryJson` (update métadonnées).
Les autres formats (RAW/FS) ne portent pas ces champs — ils restent `null` en BDD.

### 10.2 Métadonnées enrichies de l'éditeur (nœuds)

Les `stageNodes`/`actionNodes` peuvent porter des champs éditeur **non utilisés par la Lunii** :
`name`, `type` (ex. `cover`, `story`, `menu.optionsaction`), `groupId`, `position {x,y}`
(cf. §3.1). Le writer les écrit si présents en mémoire (`EnrichedNodeMetadata`), le reader les
relit sans les exiger. Un pack produit par StoryUnchained (création d'histoire) exploite
`name` (titre du nœud) et `type` (COVER/STORY) pour l'affichage éditeur.

### 10.3 Métadonnées en base (H2, `pack_metadata`)

Table `pack_metadata` (`PackMetadataEntity`) — une ligne par pack, clé primaire `packId` :

| Colonne | Source |
|---|---|
| `packId` | UUID du pack (= UUID du 1er stageNode) |
| `title`, `description` | Catalogue officiel **ou** fichier |
| `thumbnail` | URL officielle **ou** `data:image/png;base64,...` (extraite du zip) |
| `version` | Version du format du fichier |
| `factoryDisabled` | Toujours `false` chez StoryUnchained (lecture raw) |
| `nightModeAvailable` | Depuis le fichier |
| `official` | `true` si le pack est dans le catalogue officiel |
| `linkedOfficialPackId` | UUID officiel d'origine pour un pack fork — **doit être `NULL` si `official=true`** (contrainte `CHECK` Hibernate) |
| `locale`, `ageMin`, `ageMax`, `durationMs`, `storyCount` | Champs étendus (§10.1) |

Contrainte métier : un pack **officiel** ne peut pas être lié à un pack officiel
(`official = TRUE AND linkedOfficialPackId IS NULL`), et inversement un fork référence un pack
officiel existant (validé par `UpdatePackMetadataUseCase.resolveLinkedOfficialPackId`).

### 10.4 Catalogue officiel — priorité des sources

Au sync, StoryUnchained charge le **catalogue Lunii officiel** (`official.json`, téléchargé
depuis l'API Lunii via un token invité — portage du `DatabaseMetadataService` de STUdio,
`MetadataRefreshPort`), puis fusionne les métadonnées par UUID (`PackMetaExtractor.buildPack`) :

```
métadonnée finale = fromOfficial?.X  ?:  meta.X   (fichier)
```

- Si l'UUID du pack correspond à une entrée officielle → `official=true`, et **toutes** les
  métadonnées (titre, description, vignette, locale, âges, durée, storyCount) viennent du
  catalogue, même si le fichier local en a d'autres.
- Sinon → métadonnées lues depuis le fichier (zip : §10.1 ; raw/fs : champs de base).
- Vignette officielle = **URL** (`https://...`), vignette locale = **data URI base64** stockée
  en colonne `thumbnail` (TEXT).

### 10.5 Flux d'update des métadonnées (front)

| Endpoint | Comportement |
|---|---|
| `PATCH /packs/{id}/metadata` | Met à jour **en BDD** (titre, description, locale, âges, durée, storyCount, lien fork) **et dans le zip** (`story.json` réécrit + `meta/thumbnail.png` ajouté/remplacé) via `UpdateZipMetadataAdapter` — sans toucher aux assets |
| `PATCH /packs/{id}/thumbnail` | Upload vignette → BDD (data URI) + `meta/thumbnail.png` dans le zip (même flux, `thumbnailPngBytes`) |

L'update zip est **atomique** (fichier temp + `ATOMIC_MOVE`) ; si le pack n'a pas de variante
ARCHIVE, seul le BDD est modifié.

### 10.6 Vignette en BDD vs dans le zip

| Couche | Représentation |
|---|---|
| Dans le zip | `meta/thumbnail.png` (PNG brut) ou `thumbnail.png` racine (héritage) |
| En BDD | `data:image/png;base64,...` (pack local) ou URL (pack officiel) |
| Cache | `ThumbnailCache` (mémoire, clé = packId) + endpoint `GET /packs/{id}/thumbnail` |

La résolution à l'indexation (`PackMetaExtractor.resolveArchiveThumbnail`) : cache → zip
(`findThumbnailEntry` : préférence `meta/thumbnail.png`, fallback racine) → base64 en BDD.

---

## 11. Autres spécificités StoryUnchained

StoryUnchained ne réécrit pas le format : il l'**implémente en Kotlin pur** et ajoute ses propres
comportements autour. Cette section détaille ce qui est propre au projet (vs. un pack STUdio
brut ou produit par le studio original).

### 11.1 Écriture — `ArchiveStoryPackWriter`

- **JSON via kotlinx.serialization** (`Json { prettyPrint = true }`), pas gson.
- **Nommage des assets par SHA-1 du contenu** : `sha1Hex(rawData) + extension` (déduplique
  automatiquement les fichiers identiques). L'ordre d'écriture dans le zip est l'ordre
  lexicographique des noms (`sortedMapOf`), `assets/` étant une entrée répertoire explicite.
- **`squareOne` auto** : le writer met `squareOne: true` sur le nœud d'index 0 (l'ordre de
  `stageNodes` est donc significatif ; à la relecture, le nœud `squareOne` est ramené en tête).
- **UUID des `actionNodes` générés localement** (`UUID.randomUUID()`), mémorisés par référence
  objet : un même `ActionNode` partagé par deux transitions reçoit le même id dans le zip.
- **Fallbacks d'écriture** : `title` absent → `"MISSING_PACK_TITLE"`, `name` absent →
  `"MISSING_NAME"`, image/audio/transitions absents → `null` explicite dans le JSON.
- **`controlSettings` toujours écrit**, même si `null` en mémoire (défauts `false`).
- **Extensions d'écriture** : `image/bmp→.bmp`, `image/png→.png`, `image/jpeg→.jpg`,
  `audio/x-wav→.wav`, `audio/mpeg→.mp3`, `audio/ogg→.ogg` ; autre MIME → extension vide.
- Le writer **n'écrit pas la vignette** : c'est `UpdateZipMetadataAdapter` qui l'injecte
  (`meta/thumbnail.png`) lors de l'update de métadonnées (cf. §10.5).

### 11.2 Lecture — `ArchiveStoryPackReader`

- **Format détecté par contenu, pas par extension** : `PackFileInspector.looksLikeArchiveZip`
  exige `story.json` à la racine **et** au moins une entrée `assets/...`. Un zip d'archive sans
  dossier `assets/` est donc rejeté comme pack.
- **Précédence FS > archive** : un zip dont la racine est un dossier à nom UUID contenant
  `ni`/`li`/`ri` est d'abord testé comme pack FS zippé (`detectFsInsideZip`) — cas « FS embarqué
  dans un zip », lu après dézippage temporaire.
- **MIME déduits de l'extension** des fichiers `assets/` (`.bmp/.png/.jpg/.jpeg` pour les
  images, `.wav/.mp3/.ogg/.oga` pour l'audio) — le MIME contenu dans `story.json` n'existe pas,
  c'est le nom de fichier qui fait foi.
- **`controlSettings` requis** : nœud sans `controlSettings` → `IllegalStateException`.
- **`squareOne` prioritaire** : même si un autre nœud apparaît en premier dans le tableau,
  le nœud marqué `squareOne` est repositionné en tête (c'est lui qui définit l'UUID du pack).
- **Assets partagés** : un même fichier référencé par N nœuds n'est chargé qu'une fois en
  mémoire (map `assetToStageNodes`).
- **Métadonnées allégées** : `readMetadata` (utilisé par le sync) ne lit que `story.json` +
  `thumbnail.png` racine, **sans dézipper les assets** ; il ne lit que la vignette racine
  `thumbnail.png`, pas `meta/thumbnail.png` (celle-ci est gérée côté sync via
  `findThumbnailEntry`, cf. §10.6).

### 11.3 Vignette — deux emplacements, deux usages

| Emplacement | Écrit par | Lu par |
|---|---|---|
| `meta/thumbnail.png` | `UpdateZipMetadataAdapter` (update métadonnées depuis le front) | Sync : `findThumbnailEntry` (préférence) |
| `thumbnail.png` (racine) | packs produits par les outils STUdio/anciens | Sync (fallback) **et** `readMetadata` (reader) |

Résultat : la vignette **affichée dans la bibliothèque** (sync → `PackMetaExtractor` →
`ThumbnailCache`) provient de `meta/thumbnail.png` quand elle existe, sinon de la racine.

### 11.4 Update de métadonnées sans réécrire les assets

`UpdateZipMetadataAdapter` (flux `PATCH /packs/{id}/metadata` et upload de vignette) :
- recopie **toutes** les entrées du zip à l'identique, ne modifie que `story.json` (les champs
  `title`, `description`, `locale`, `ageMin`, `ageMax`, `durationMs`, `storyCount`) et
  l'entrée `meta/thumbnail.png` ;
- si le thumbnail n'existait pas encore, il est **ajouté** à la fin du zip ;
- écriture atomique (fichier temp + `ATOMIC_MOVE`) — un crash ne laisse pas de zip corrompu.

> Contrainte implicite : les assets ne peuvent pas être modifiés par ce flux — seul `story.json`
> et la vignette sont mutables sans conversion complète.

### 11.5 Conversion — `StudioCorePackFormatConverterAdapter` + `PackAssetsCompression`

Les conversions réutilisent les mêmes readers/writers (dont `ArchiveStoryPackWriter`) :

| Conversion | Transformations des assets |
|---|---|
| RAW → ARCHIVE | BMP → PNG, WAV → OGG (fallback : WAV conservé si encodeur OGG indisponible) |
| FS → ARCHIVE | BMP 4-bpp RLE Lunii → PNG (sinon vignettes illisibles dans les viewers standard) |
| ARCHIVE → RAW | PNG/JPG → BMP, OGG/MP3 → WAV |
| ARCHIVE/RAW → FS | images → BMP 320×240 4-bpp RLE ; audio → MP3 mono 44,1 kHz **sans tags ID3** (tags retirés, ré-encodage si non conforme) |

- Fichiers produits : `{uuid}.converted_{timestamp}.zip` dans le dossier bibliothèque.
- Le zip d'archive généré par conversion est donc un zip StoryUnchained standard, écrit par le
  même `ArchiveStoryPackWriter` (y compris `squareOne`, SHA-1, etc.).

### 11.6 Limites connues (différences vs studio-core)

- **Encodage OGG non supporté** : `AudioConversion.waveToOgg` lève
  `UnsupportedOperationException` (dépendance `vorbis-java` irrésolvable) → les conversions
  RAW → ARCHIVE gardent du WAV au lieu d'OGG.
- **Décodage uniquement côté archive** : WAV/MP3/OGG en entrée OK, mais l'encodage disponible
  est limité à MP3 (jump3r/LAME) et BMP/PNG (JDK ImageIO).
- **Identité par UUID de nœuds** : deux packs qui partagent le même premier `stageNode.uuid`
  sont considérés comme le même pack par le sync (fingerprint), même si leurs fichiers
  diffèrent.

---

## 12. Implémentation (références)

| Rôle | Classe | Fichier |
|---|---|---|
| Écriture zip | `ArchiveStoryPackWriter` | `pack/format/writer/ArchiveStoryPackWriter.kt` |
| Lecture zip | `ArchiveStoryPackReader` | `pack/format/reader/ArchiveStoryPackReader.kt` |
| Modèle mémoire | `StoryPack`, `StageNode`, `ActionNode`, `Transition` | `pack/format/model/` |
| Thumbnail | `findThumbnailEntry` / `UpdateZipMetadataAdapter` | `pack/util/ZipThumbnailEntry.kt` / `pack/adapter/UpdateZipMetadataAdapter.kt` |
| Constantes | `PackConstants` (archive = `PACK_FORMAT_ARCHIVE`) | `pack/format/model/Constants.kt` |
| Conversion | `AudioConversion`, `ImageConversion` | `pack/format/utils/` |
| Métadonnées fichier → BDD | `MetaDataReaderAdapter`, `PackMetaExtractor` | `pack/adapter/`, `pack/service/` |
| Entité BDD | `PackMetadataEntity` | `infrastructure/persistence/PackMetadataEntity.kt` |
| Update métadonnées | `UpdatePackMetadataUseCase` | `pack/service/UpdatePackMetadataUseCase.kt` |
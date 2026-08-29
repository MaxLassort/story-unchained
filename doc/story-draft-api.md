# Brouillon d'histoire (sur disque, dossier temp)

## Pourquoi

Créer une histoire demande plusieurs étapes (titre, thumbnails, chapitres, audios, images).
Plutôt que d'écrire quoi que ce soit en base ou dans la bibliothèque avant d'avoir un zip
valide, le brouillon vit **dans le dossier temp** : rien n'est persisté avant la finalisation
(étape 5).

## Choix

- **Un seul brouillon à la fois** : créer une nouvelle histoire **remplace** le brouillon
  courant (pas de multi-drafts, plus simple pour le front et le back).
- **Tout sur disque, rien en mémoire** : l'état structuré est sérialisé dans
  `drafts/{id}/draft.json` et les **binaires** (audio, images) sont des fichiers dans le
  même dossier temp `storageDir/drafts/{id}/`. Chaque mutation relit le JSON, l'applique et
  le réécrit. Une histoire peut durer **2-3 h d'audio** (~60-200 Mo en MP3, plus en WAV) :
  rien ne doit vivre en heap JVM.
- **Nettoyage systématique** : le dossier `drafts/` est **purge au démarrage** (résidus de
  crash) ; le dossier d'un draft est supprimé à son remplacement, à sa suppression
  (`DELETE`) et à sa finalisation. Le draft ne survit jamais à la fermeture de l'appli.
- **Audio du titre : upload OU texte TTS** : chaque chapitre a soit un fichier audio
  uploadé (MP3/WAV/OGG…), soit un texte synthétisé par le TTS (provider configuré dans les
  settings) **à la finalisation** — les deux sont mutuellement exclusifs (saisir l'un
  efface l'autre).
- **Audio du chapitre (narration)** : le contenu du chapitre lui-même (potentiellement des
  heures au total) est uploadé via `…/narration` et stocké sur disque.
- **Image du chapitre** : PNG/JPEG uploadé (un SVG doit être converti avant via
  `POST /stories/images/render`), avec l'icône Lucide (`iconId`) comme fallback ; à la
  finalisation, hiérarchie : image uploadée → icône → chiffre généré (étape 4).
- **État sans bytes** : `GET /stories/drafts/{id}` renvoie l'état complet mais pas les
  binaires (seulement leurs tailles).

## Comment ça marche

```
POST   /stories/drafts                          → 201 {draftId}        (remplace l'existant)
GET    /stories/drafts/{id}                     → état sans bytes
PATCH  /stories/drafts/{id}                     → {title?, description?}
DELETE /stories/drafts/{id}                     → 204

PUT    /stories/drafts/{id}/thumbnail           (multipart PNG/JPEG → meta/thumbnail.png)
PUT    /stories/drafts/{id}/cover               (multipart PNG/JPEG → thumbnail Lunii, squareOne)
PUT    /stories/drafts/{id}/title-audio         (multipart audio/* → audio du pack, joué sur le cover)
PUT    /stories/drafts/{id}/title-text          {text} (TTS du titre du pack, à la finalisation)
PUT    /stories/drafts/{id}/menu-audio          (multipart audio/* → audio du nœud de sélection des chapitres)
PUT    /stories/drafts/{id}/menu-text           {text} (TTS du prompt du menu, à la finalisation)

POST   /stories/drafts/{id}/chapters            → {name} → 201 {draftId, chapterId}
DELETE /stories/drafts/{id}/chapters/{chapterId}

PUT    /stories/drafts/{id}/chapters/{cid}/audio        (multipart audio/* → audio du titre)
PUT    /stories/drafts/{id}/chapters/{cid}/title-text   {text} (TTS à la finalisation)
PUT    /stories/drafts/{id}/chapters/{cid}/narration    (multipart audio/* → narration du chapitre)
PUT    /stories/drafts/{id}/chapters/{cid}/image        (multipart PNG/JPEG)
PUT    /stories/drafts/{id}/chapters/{cid}/icon         {iconId}
```

- Cycle de vie : création → remplissage → finalisation (étape 5, zip) ; le draft est
  supprimé à la finalisation et/ou remplacé à la création d'une nouvelle histoire.
- Le `draftId` est un **uuid généré automatiquement** par le serveur.
- Erreurs : `404` brouillon/chapitre inconnu, `400` payload invalide (nom vide, fichier
  non-audio, image non-PNG/JPEG, texte vide).

## API

| Endpoint | Corps | Réponse | Erreurs |
|---|---|---|---|
| `POST /stories/drafts` | — | 201 `{draftId}` | — |
| `GET /stories/drafts/{id}` | — | `StoryDraftSummary` (sans bytes) | 404 |
| `PATCH /stories/drafts/{id}` | `{title?, description?}` | `StoryDraftSummary` | 404 |
| `DELETE /stories/drafts/{id}` | — | 204 | 404 |
| `PUT /stories/drafts/{id}/thumbnail` | multipart `file` (PNG/JPEG) | `StoryDraftSummary` | 400, 404 |
| `PUT /stories/drafts/{id}/cover` | multipart `file` (PNG/JPEG) | `StoryDraftSummary` | 400, 404 |
| `PUT /stories/drafts/{id}/title-audio` | multipart `file` (audio/*) | `StoryDraftSummary` | 400, 404 |
| `PUT /stories/drafts/{id}/title-text` | `{text}` | `StoryDraftSummary` | 400, 404 |
| `PUT /stories/drafts/{id}/menu-audio` | multipart `file` (audio/*) | `StoryDraftSummary` | 400, 404 |
| `PUT /stories/drafts/{id}/menu-text` | `{text}` | `StoryDraftSummary` | 400, 404 |
| `POST /stories/drafts/{id}/chapters` | `{name}` | 201 `{draftId, chapterId}` | 400, 404 |
| `DELETE /stories/drafts/{id}/chapters/{chapterId}` | — | 204 | 404 |
| `PUT …/chapters/{cid}/audio` | multipart `file` (audio/*) | `StoryDraftSummary` | 400, 404 |
| `PUT …/chapters/{cid}/title-text` | `{text}` | `StoryDraftSummary` | 400, 404 |
| `PUT …/chapters/{cid}/narration` | multipart `file` (audio/*) | `StoryDraftSummary` | 400, 404 |
| `PUT …/chapters/{cid}/image` | multipart `file` (PNG/JPEG) | `StoryDraftSummary` | 400, 404 |
| `PUT …/chapters/{cid}/icon` | `{iconId}` | `StoryDraftSummary` | 400, 404 |

`StoryDraftSummary` : `{id, title?, description?, hasThumbnail, thumbnailBytes, hasCover,
coverBytes, hasTitleAudio, titleAudioBytes, titleText?, hasMenuAudio, menuAudioBytes,
menuText?, chapters[]}` où chaque chapitre est
`{id, name, hasTitleAudio, titleAudioBytes, titleText?, hasNarrationAudio,
narrationAudioBytes, hasImage, imageBytes, iconId?}`.

L'**audio du pack** (`title-audio`/`title-text`, mutuellement exclusifs) est l'audio joué sur
le cover (squareOne) : à la finalisation, si un `titleText` est saisi, l'audio du cover est
synthétisé par TTS depuis le titre ; sinon c'est l'audio uploadé qui est utilisé.

L'**audio du menu de sélection** (`menu-audio`/`menu-text`, mutuellement exclusifs) est joué
sur le nœud de sélection des chapitres : à la finalisation, si un `menuText` est saisi, il est
synthétisé par TTS ; sinon c'est l'audio uploadé ; si les deux sont absents, un prompt par
défaut (« Choisissez un chapitre ») est synthétisé.

## Emplacement des binaires

```
{storageDir}/drafts/{draftId}/
  thumbnail.png|jpg          → meta/thumbnail.png
  cover.png|jpg              → image du squareOne (cover Lunii)
  title-audio.mp3|wav|ogg…   → audio du pack (joué sur le cover)
  chapters/{chapterId}/
    title-audio.mp3|wav|ogg… → audio du titre
    narration.mp3|wav|ogg…   → narration du chapitre
    image.png|jpg            → image du chapitre
```

L'extension du fichier suit le `Content-Type` de l'upload (`audio/mpeg` → `.mp3`,
`image/jpeg` → `.jpg`, défaut `.bin`) pour que la finalisation connaisse le type sans
re-parse. Le dossier entier est supprimé au remplacement du draft, au `DELETE` et au
démarrage de l'appli.

## Cas d'usage — créer un pack complet (exemple curl)

Objectif : créer un pack « Ma petite histoire » avec thumbnail + cover Lunii, et un
chapitre avec image, titre audio (via TTS) et narration.

```bash
# 1. Nouveau draft (l'uuid est généré automatiquement) — remplace tout draft existant
curl -s -X POST http://localhost:8080/stories/drafts
# → {"draftId":"550e8400-e29b-41d4-a716-446655440000"}

ID=550e8400-e29b-41d4-a716-446655440000

# 2. Titre + description
curl -s -X PATCH http://localhost:8080/stories/drafts/$ID \
  -H 'Content-Type: application/json' \
  -d '{"title":"Ma petite histoire","description":"Une aventure pour les 3-6 ans"}'

# 3. Thumbnail bibliothèque (meta/thumbnail.png) + cover Lunii (squareOne)
curl -s -X PUT http://localhost:8080/stories/drafts/$ID/thumbnail \
  -F "file=@thumb.png;type=image/png"
curl -s -X PUT http://localhost:8080/stories/drafts/$ID/cover \
  -F "file=@cover.png;type=image/png"

# 3b. Audio du pack (joué sur le cover) — upload OU texte TTS
curl -s -X PUT http://localhost:8080/stories/drafts/$ID/title-audio \
  -F "file=@titre_pack.mp3;type=audio/mpeg"
#   — ou —
curl -s -X PUT http://localhost:8080/stories/drafts/$ID/title-text \
  -H 'Content-Type: application/json' -d '{"text":"Ma petite histoire"}'

# 4. Chapitre
curl -s -X POST http://localhost:8080/stories/drafts/$ID/chapters \
  -H 'Content-Type: application/json' -d '{"name":"Le réveil de Léa"}'
# → {"draftId":"$ID","chapterId":"9f8e..."}

CHAP=9f8e7d6c-5b4a-4c3d-9e2f-1a0b8c7d6e5f

# 5. Image du chapitre (PNG/JPEG — un SVG doit d'abord passer par POST /stories/images/render)
curl -s -X PUT http://localhost:8080/stories/drafts/$ID/chapters/$CHAP/image \
  -F "file=@chap1.png;type=image/png"

# 6a. Titre du chapitre par TTS (texte) — ou 6b. par upload audio
curl -s -X PUT http://localhost:8080/stories/drafts/$ID/chapters/$CHAP/title-text \
  -H 'Content-Type: application/json' -d '{"text":"Le réveil de Léa"}'
#   — ou —
curl -s -X PUT http://localhost:8080/stories/drafts/$ID/chapters/$CHAP/audio \
  -F "file=@titre1.mp3;type=audio/mpeg"

# 7. Narration du chapitre (le contenu, potentiellement long)
curl -s -X PUT http://localhost:8080/stories/drafts/$ID/chapters/$CHAP/narration \
  -F "file=@narration1.mp3;type=audio/mpeg"

# 8. Vérifier l'état (sans bytes)
curl -s http://localhost:8080/stories/drafts/$ID

# 9. Finalisation (étape 5) : POST /stories/drafts/$ID/finalize → {packId}
```

## Où

- `pack/domain/model/StoryDraft.kt` : `StoryDraft` + `StoryChapterDraft` (état + chemins)
- `pack/domain/dto/StoryDraftDtos.kt` : DTOs API (résumés sans bytes, requêtes)
- `pack/service/StoryDraftStore.kt` : store mono-draft thread-safe (verrou + `@Volatile`),
  écritures disque dans `storageDir/drafts/{id}/`, purge au démarrage/remplacement/suppression
- `pack/web/StoryDraftController.kt` : endpoints ci-dessus (annotés Swagger)
- `infrastructure/config/StudioProperties.kt` : `draftsDir` (`{storageDir}/drafts`)

## Contraintes

- Un seul draft : `POST /stories/drafts` écrase silencieusement le précédent (et son dossier).
- Aucune persistance : redémarrage de l'appli = draft perdu (assumé) ; les binaires
  résiduels sont purgés au démarrage suivant.
- La synthèse TTS du `titleText` n'a pas lieu ici — elle se fera à la finalisation
  (étape 5), avec le fallback gratuit si le provider payant échoue.
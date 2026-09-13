# Brouillon d'histoire (API draft sur disque)

> API REST du brouillon de création d'histoire : cycle de vie, endpoints, modèle de données et cas d'usage.

---

## Métadonnées

- **Statut** : Actif
- **Dernière mise à jour** : 2026-08-21
- **Liens** : [Finalisation (story-creation-flow)](story-creation-flow.md) · [Moteur TTS](tts-engine.md) · [Plan étape 3](../plans/story-creation-plan.md)

---

## 1. Contexte

Créer une histoire demande plusieurs étapes (titre, thumbnails, chapitres, audios, images). Plutôt que d'écrire quoi que ce soit en base ou dans la bibliothèque avant d'avoir un zip valide, le brouillon vit **dans le dossier temp** : rien n'est persisté avant la finalisation.

### Choix de conception

- **Un seul brouillon à la fois** : créer une nouvelle histoire **remplace** le brouillon courant (pas de multi-drafts).
- **Tout sur disque, rien en mémoire** : l'état structuré est sérialisé dans `drafts/{id}/draft.json` et les **binaires** (audio, images) sont des fichiers dans le même dossier temp. Une histoire peut durer **2-3 h d'audio** (~60-200 Mo en MP3) : rien ne doit vivre en heap JVM.
- **Nettoyage systématique** : le dossier `drafts/` est **purge au démarrage** (résidus de crash) ; le dossier d'un draft est supprimé à son remplacement, à sa suppression (`DELETE`) et à sa finalisation.
- **Audio du titre : upload OU texte TTS** : chaque chapitre a soit un fichier audio uploadé, soit un texte synthétisé par le TTS à la finalisation — les deux sont mutuellement exclusifs.
- **Image du chapitre** : PNG/JPEG uploadé (un SVG doit être converti avant via `POST /stories/images/render`), avec l'icône Lucide (`iconId`) comme fallback.
- **État sans bytes** : `GET /stories/drafts/{id}` renvoie l'état complet mais pas les binaires (seulement leurs tailles).

## 2. API

### Endpoints

| Endpoint | Méthode | Corps | Réponse | Description |
|---|---|---|---|---|
| `/stories/drafts` | POST | — | `201 {draftId}` | Créer un draft (remplace l'existant) |
| `/stories/drafts/{id}` | GET | — | `StoryDraftSummary` | État sans bytes |
| `/stories/drafts/{id}` | PATCH | `{title?, description?}` | `StoryDraftSummary` | Modifier titre/description |
| `/stories/drafts/{id}` | DELETE | — | `204` | Supprimer le draft |
| `/stories/drafts/{id}/thumbnail` | PUT | multipart `file` (PNG/JPEG) | `StoryDraftSummary` | Upload thumbnail |
| `/stories/drafts/{id}/cover` | PUT | multipart `file` (PNG/JPEG) | `StoryDraftSummary` | Upload cover (squareOne) |
| `/stories/drafts/{id}/title-audio` | PUT | multipart `file` (audio/*) | `StoryDraftSummary` | Audio du pack |
| `/stories/drafts/{id}/title-text` | PUT | `{text}` | `StoryDraftSummary` | TTS du titre (à la finalisation) |
| `/stories/drafts/{id}/menu-audio` | PUT | multipart `file` (audio/*) | `StoryDraftSummary` | Audio du menu |
| `/stories/drafts/{id}/menu-text` | PUT | `{text}` | `StoryDraftSummary` | TTS du prompt menu |
| `/stories/drafts/{id}/chapters` | POST | `{name}` | `201 {draftId, chapterId}` | Ajouter un chapitre |
| `/stories/drafts/{id}/chapters/{cid}` | DELETE | — | `204` | Supprimer un chapitre |
| `…/chapters/{cid}/audio` | PUT | multipart `file` (audio/*) | `StoryDraftSummary` | Audio du titre chapitre |
| `…/chapters/{cid}/title-text` | PUT | `{text}` | `StoryDraftSummary` | TTS du titre chapitre |
| `…/chapters/{cid}/narration` | PUT | multipart `file` (audio/*) | `StoryDraftSummary` | Narration du chapitre |
| `…/chapters/{cid}/image` | PUT | multipart `file` (PNG/JPEG) | `StoryDraftSummary` | Image du chapitre |
| `…/chapters/{cid}/icon` | PUT | `{iconId}` | `StoryDraftSummary` | Icône Lucide du chapitre |

### Modèle `StoryDraftSummary`

```json
{
  "id": "uuid",
  "title": "string",
  "description": "string",
  "hasThumbnail": true,
  "thumbnailBytes": 12345,
  "hasCover": true,
  "coverBytes": 12345,
  "hasTitleAudio": true,
  "titleAudioBytes": 12345,
  "titleText": "string",
  "hasMenuAudio": false,
  "menuAudioBytes": 0,
  "menuText": "string",
  "chapters": [
    {
      "id": "uuid",
      "name": "Chapitre 1",
      "hasTitleAudio": true,
      "titleAudioBytes": 12345,
      "titleText": "string",
      "hasNarrationAudio": true,
      "narrationAudioBytes": 12345,
      "hasImage": true,
      "imageBytes": 12345,
      "iconId": "star"
    }
  ]
}
```

### Codes d'erreur

| Code | Signification |
|---|---|
| `404` | Brouillon ou chapitre inconnu |
| `400` | Payload invalide (nom vide, fichier non-audio, image non-PNG/JPEG, texte vide) |

## 3. Structure disque

```
{storageDir}/drafts/{draftId}/
  draft.json                     ← état sérialisé
  thumbnail.png|jpg              → meta/thumbnail.png
  cover.png|jpg                  → image du squareOne
  title-audio.mp3|wav|ogg…      → audio du pack
  menu-audio.mp3|wav|ogg…       → audio du menu
  chapters/{chapterId}/
    title-audio.mp3|wav|ogg…    → audio du titre
    narration.mp3|wav|ogg…      → narration du chapitre
    image.png|jpg               → image du chapitre
```

L'extension suit le `Content-Type` de l'upload (`audio/mpeg` → `.mp3`, `image/jpeg` → `.jpg`). Le dossier entier est supprimé au remplacement, au `DELETE` et au démarrage.

## 4. Cas d'usage — curl complet

```bash
# 1. Nouveau draft
curl -s -X POST http://localhost:8080/stories/drafts
# → {"draftId":"550e8400-e29b-41d4-a716-446655440000"}

ID=550e8400-e29b-41d4-a716-446655440000

# 2. Titre + description
curl -s -X PATCH http://localhost:8080/stories/drafts/$ID \
  -H 'Content-Type: application/json' \
  -d '{"title":"Ma petite histoire","description":"Une aventure pour les 3-6 ans"}'

# 3. Thumbnail + cover
curl -s -X PUT http://localhost:8080/stories/drafts/$ID/thumbnail \
  -F "file=@thumb.png;type=image/png"
curl -s -X PUT http://localhost:8080/stories/drafts/$ID/cover \
  -F "file=@cover.png;type=image/png"

# 4. Audio du pack (upload OU texte TTS)
curl -s -X PUT http://localhost:8080/stories/drafts/$ID/title-audio \
  -F "file=@titre_pack.mp3;type=audio/mpeg"
# ou
curl -s -X PUT http://localhost:8080/stories/drafts/$ID/title-text \
  -H 'Content-Type: application/json' -d '{"text":"Ma petite histoire"}'

# 5. Chapitre
curl -s -X POST http://localhost:8080/stories/drafts/$ID/chapters \
  -H 'Content-Type: application/json' -d '{"name":"Le réveil de Léa"}'
CHAP=9f8e7d6c-5b4a-4c3d-9e2f-1a0b8c7d6e5f

# 6. Image du chapitre
curl -s -X PUT http://localhost:8080/stories/drafts/$ID/chapters/$CHAP/image \
  -F "file=@chap1.png;type=image/png"

# 7. Titre du chapitre par TTS
curl -s -X PUT http://localhost:8080/stories/drafts/$ID/chapters/$CHAP/title-text \
  -H 'Content-Type: application/json' -d '{"text":"Le réveil de Léa"}'

# 8. Narration
curl -s -X PUT http://localhost:8080/stories/drafts/$ID/chapters/$CHAP/narration \
  -F "file=@narration1.mp3;type=audio/mpeg"

# 9. Vérifier l'état
curl -s http://localhost:8080/stories/drafts/$ID

# 10. Finalisation
curl -s -X POST http://localhost:8080/stories/drafts/$ID/finalize
```

## 5. Références code

| Rôle | Fichier |
|---|---|
| Modèle | `pack/domain/model/StoryDraft.kt` (`StoryDraft` + `StoryChapterDraft`) |
| DTOs | `pack/domain/dto/StoryDraftDtos.kt` |
| Store | `pack/service/StoryDraftStore.kt` (mono-draft thread-safe, verrou + `@Volatile`) |
| Controller | `pack/web/StoryDraftController.kt` |
| Config | `infrastructure/config/StudioProperties.kt` (`draftsDir`) |

## 6. Contraintes

- **Un seul draft** : `POST /stories/drafts` écrase silencieusement le précédent.
- **Aucune persistance** : redémarrage de l'appli = draft perdu ; les binaires résiduels sont purgés au démarrage suivant.
- **TTS différé** : la synthèse du `titleText` n'a pas lieu ici — elle se fera à la finalisation.

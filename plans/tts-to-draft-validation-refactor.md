# Plan — TTS à la validation des steps + finalisation = pur zip + consolidation des endpoints

> Statut : plan validé par l'utilisateur, à implémenter en 4 étapes.
> Remplace le comportement actuel : la synthèse TTS n'est **plus** faite dans `CreateStoryUseCase.finalize()`.

## Objectif

- La synthèse TTS se fait **au moment de la validation du formulaire de chaque step** : le texte saisi est converti en audio et **stocké comme fichier dans le draft** (`drafts/{id}/`).
- La **finalisation ne synthétise plus rien** : c'est un packaging pur — lecture des assets du draft, construction du graphe, écriture du zip STUdio, indexation BDD.
- L'API de draft est consolidée : **un seul PUT binaire** + **un PATCH avec un modèle commun** pour éditer un nœud (pack ou chapitre), au lieu des multiples PUT par champ.

## Étape 1 — Backend : synthèse TTS à la validation du draft

**Code**
- `pack/service/StoryDraftStore.kt` : injecter `TtsEngine` ; quand un texte est enregistré
  (`titleText`, `menuText`, `chapter.titleText`), synthétiser **immédiatement** et stocker
  le MP3 dans le dossier du draft (`title-audio.mp3`, `menu.mp3`, `chapters/{chapterId}/title-audio.mp3`).
  - Le texte est remplacé par le fichier audio généré dans le `StoryDraftState` (le champ
    `titleText`/`menuText` peut être conservé pour ré-affichage front, mais l'audio fait foi).
- `pack/service/TtsEngine.kt` : `TtsApiKeyMissingException` levée si OPENAI/ELEVENLABS sans clé —
  elle remonte alors **au moment du save**, pas à la finalisation (déjà mappé en 409 par
  `GlobalExceptionHandler`).
- `pack/service/CreateStoryUseCase.kt` : retirer l'injection de `TtsEngine` et tous les appels
  `synthesize(...)` ; `coverAudio` / `menuPrompt` / `titleAudio` des chapitres viennent
  **exclusivement** des fichiers du draft. `validate()` exige les fichiers audio
  (`titleAudioFile`, `menuAudioFile`, `chapter.narrationAudioFile`).
- Le fallback "Choisissez un chapitre" est synthétisé **à la validation** si ni audio ni texte
  n'est fourni (le front rend ce champ requis, donc cas résiduel).

**Tests**
- Store : sauvegarde d'un texte → fichier MP3 présent dans le draft dir, `titleText` mis à jour,
  fichier audio lisible
- 409 levé si provider payant sans clé, au moment du `set*Text` (pas à la finalisation)
- Finalize sans aucun appel TTS (TtsEngine mocké → `verifyNoInteractions`)

## Étape 2 — Backend : consolidation des endpoints

**Objectif** : remplacer les multiples PUT par champ par **1 PUT binaire + 1 PATCH nœud** avec
un modèle commun.

**Code**
- `PUT /stories/drafts/{id}/files` (multipart : `file` + métadonnées cible) :
  - cible = `scope` (`pack` | `chapter`) + `chapterId` (si chapter) + `field`
    (`titleAudio` | `menuAudio` | `thumbnail` | `cover` | `narration` | `image`)
  - validation du content-type selon le champ (audio/* vs image/*)
  - remplace : `PUT /thumbnail`, `PUT /cover`, `PUT /title-audio`, `PUT /menu-audio`,
    `PUT /chapters/{chapterId}/audio`, `PUT /chapters/{chapterId}/narration`,
    `PUT /chapters/{chapterId}/image`
- `PATCH /stories/drafts/{id}/nodes/{nodeId}` avec un modèle commun :
  - `nodeId` = id du pack (racine) ou id du chapitre
  - payload : `{ name?, titleText?, menuText?, iconId? }` — champs optionnels, seuls les
    champs fournis sont appliqués
  - remplace : `PUT /title-text`, `PUT /menu-text`, `PUT /chapters/{chapterId}/title-text`,
    `PUT /chapters/{chapterId}/icon`
- Les **GET de téléchargement** (`/thumbnail/file`, `/cover/file`, `/title-audio/file`,
  `/menu-audio/file`, `/chapters/{chapterId}/...`) restent inchangés.
- Anciens endpoints : **supprimés après la migration front** (pas dans la même passe).
  Séquencement :
  1. Ajouter les nouveaux endpoints consolidés (PUT `/files`, PATCH `/nodes/{nodeId}`)
  2. Migrer le front vers les nouveaux endpoints (étape 3)
  3. Vérifier le front (typecheck + tests)
  4. **Supprimer les anciens endpoints** (`PUT /thumbnail`, `/cover`, `/title-audio`,
     `/menu-audio`, `/chapters/{chapterId}/audio`, `/narration`, `/image`, `/icon`,
     `PUT /title-text`, `/menu-text`, `/chapters/{chapterId}/title-text`) et leurs
     méthodes de store devenues orphelines
  5. Nettoyer les tests back liés aux anciens endpoints

**Tests**
- Controller : PUT fichiers (chaque scope/field, validations, 404), PATCH nœud (pack racine,
  chapitre, champs partiels, 404)
- Store : chemins relatifs + extension typée, exclusivité audio/texte après synthèse immédiate

## Étape 3 — Front : migration vers les endpoints consolidés

**Code**
- `library-web/src/app/core/services/story-draft.service.ts` :
  - remplacer `uploadDraftTitleAudio` / `uploadDraftMenuAudio` / `uploadDraftThumbnail` /
    `uploadDraftCover` / `uploadDraftChapterTitleAudio` / `uploadDraftChapterNarration` /
    `uploadDraftChapterImage` par un générique
    `uploadDraftFile(id, { scope, chapterId?, field }, file)`
  - remplacer `setDraftTitleText` / `setDraftMenuText` / `setDraftChapterTitleText` /
    `setDraftChapterIcon` par `patchDraftNode(id, nodeId, patch)`
- `story-details-step` : à la validation du formulaire, envoyer les textes via `patchDraftNode`
  (le back synthétise et stocke le MP3 dans le draft) ; afficher l'erreur 409 (clé manquante)
  dans le step
- `chapters-step` : idem pour les titres de chapitres + images/icônes via les endpoints consolidés

**Tests**
- Service : appels PUT/PATCH avec le bon payload cible
- Steps : sauvegarde via les endpoints consolidés, erreur 409 affichée

## Étape 5 — Finalisation = pur zip

**Code**
- `CreateStoryUseCase.finalize()` :
  1. Validation : titre, thumbnail, cover, chaque chapitre avec `name` + `titleAudioFile` +
     `narrationAudioFile` → sinon 409 (rien n'est écrit)
  2. Tous les audios proviennent **exclusivement** des fichiers du draft (déjà normalisés MP3)
  3. Graphe linéaire inchangé, `ArchiveStoryPackWriter`, injection thumbnail, indexation BDD,
     purge du draft
- Plus aucune injection `TtsEngine` dans le use case

**Tests**
- Finalize sans aucune interaction TTS (`verifyNoInteractions(ttsEngine)` n'a plus de sens —
  le use case n'a plus la dépendance)
- 409 si un asset manque, rien n'est écrit

## Ordre

1 → 2 → 3 → 4 (backend d'abord, front ensuite, tests en continu)

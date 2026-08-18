# Plan — Création d'histoires depuis le front (draft en mémoire + TTS BYOK)

> Statut : plan validé par l'utilisateur (questions répondues), à implémenter en 6 étapes indépendantes.
> Remplace l'ancien plan (single-shot `POST /stories`) : la création passe désormais par un **draft en mémoire Spring** finalisé en un seul `zip` STUdio.

## Objectif

Permettre à l'utilisateur de créer une histoire (pack STUdio) depuis le front :

- l'utilisateur **tape le nom de chaque chapitre** → le back génère le **titre audio** de la node via **TTS** ;
- l'utilisateur **fournit l'audio de chaque chapitre** (narration) et **une image par chapitre** (optionnelle) ;
- l'utilisateur fournit le **thumbnail** du pack et la **page principale** (cover, 1re image visible sur la Lunii) ;
- si **pas d'image** pour un chapitre → le back **génère une image** (numéro + nom du chapitre) en pur Kotlin ;
- le draft est **gardé en mémoire Spring** tant qu'il n'est pas complet : **aucun pack à moitié fait** n'est écrit sur disque ni en BDD ;
- à la finalisation uniquement : un `zip` **format STUdio** est écrit dans la bibliothèque puis indexé en BDD ;
- la conversion vers RAW/FS se fait **ensuite** via le convertisseur existant (`POST /packs/{id}/convert`) ;
- **TTS** : Spring AI (OpenAI + ElevenLabs) en **BYOK** (clé API utilisateur), **fallback gratuit** Google Translate TTS ;
- **préview TTS** à la demande dans le front (bouton ▶ dans le formulaire).

## Décisions actées (réponses utilisateur)

| Sujet | Décision |
|---|---|
| Moteur TTS | Spring AI, **OpenAI + ElevenLabs**, sélecteur de provider côté user (BYOK) |
| Clé API | Stockée dans `settings.json` (champ dédié), app locale, en clair |
| Fallback gratuit | **Google Translate TTS** (`translate.google.com/translate_tts`) |
| Préview TTS | **Oui**, à la demande dans le formulaire de création |
| Persistance | Draft **en mémoire Spring** jusqu'à finalisation ; binaires en temp dir ; TTL ~2h |
| Format de sortie | `zip` format STUdio (writer existant) ; conversion RAW/FS ensuite via l'existant |
| Structure histoire | Linéaire : cover (`squareOne`) → ch1 → ch2 → … ; `controlSettings` par défaut |
| Image chapitre manquante | Générée en pur Kotlin (`BufferedImage` + `Graphics2D`) : numéro + nom |

## Étape 1 — Settings TTS (clé API + provider) — *indépendante*

**Objectif** : stocker le BYOK (clé + provider + voix) dans le `settings.json` existant.

**Code**
- `settings/domain/Settings.kt` : + `ttsProvider: String?` (`OPENAI` | `ELEVENLABS` | `FREE`), `ttsApiKey: String?`, `ttsVoice: String?` (défaut `null`)
- `settings/data/SettingsRepositoryImpl.kt` : inchangé (sérialisation automatique kotlinx)
- `settings/web/SettingsController.kt` : inchangé (GET/PUT complets existants)
- Front `library-web/src/app/features/settings/settings-dialog` : section « Text-to-Speech » — select provider + champ clé API (+ voix si utile)

**Tests**
- Repository round-trip (Kotest) : défauts, valeurs null
- Component settings : rendu de la section, PUT settings

**📄 Documentation** — `doc/tts-settings.md` :
- Schéma `Settings` (nouveaux champs, valeurs, défauts)
- Emplacement du fichier de settings, implications (clé en clair, app locale)
- Guide : obtenir une clé OpenAI / ElevenLabs, configurer dans l'app

## Étape 2 — Moteur TTS : port + BYOK (OpenAI, ElevenLabs) + fallback gratuit — *indépendante*

**Objectif** : `TextToSpeechPort` avec 3 implémentations + endpoint de préview.

**Code**
- `pack/port/external/TextToSpeechPort.kt` :
  ```kotlin
  interface TextToSpeechPort {
      /** Synthétise [text] en MP3 mono 44,1 kHz (normalisé via AudioConversion). */
      suspend fun synthesize(text: String, voice: String? = null): ByteArray
  }
  ```
- `OpenAiTtsAdapter` : Spring AI `spring-ai-starter-model-openai`, `OpenAiAudioSpeechModel` construit **à la volée avec la clé du user** (BYOK runtime, PAS via `application.yml`)
- `ElevenLabsTtsAdapter` : starter Spring AI elevenlabs, même pattern
- `GoogleTranslateTtsAdapter` (fallback `FREE`) : `translate.google.com/translate_tts?q=…&tl=fr&client=tw-ob` → MP3 ; **segmentation ~200 chars/requête** + concat ; ré-encodage `AudioConversion.anyToMp3`
- `TtsEngine` : choisit le provider selon `settings.ttsProvider` ; **si clé absente ou erreur API → bascule automatique sur FREE** (log)
- Endpoint : `GET /tts/preview?text=…` (+ `voice`) → MP3 (préview front)

**Tests**
- Engine : fallback FREE quand clé absente / erreur API
- Normalisation MP3 (mono 44,1 kHz), segmentation texte long (Google), mock des clients Spring AI (MockK)

**Points d'attention**
- Compatibilité starters Spring AI avec **Spring Boot 4.1.0** (BOM `spring-ai-bom` 1.1.x+) à valider
- Construire les clients Spring AI par appel (clé runtime) — pas de bean configuré en yaml

**📄 Documentation** — `doc/tts-engine.md` :
- Architecture : port → adaptateurs → fallback, arbre de décision de bascule
- Contrat du port (entrée/sortie, format normalisé)
- Config Spring AI : BOM/version, construction runtime BYOK
- Contraintes du fallback gratuit (limite chars, segmentation, SLA non garanti)
- API : endpoint `/tts/preview` (params, réponse, codes d'erreur)

## Étape 3 — Draft store en mémoire Spring — *indépendante (TTS stubable)*

**Objectif** : le brouillon vit en mémoire Spring ; **aucune écriture bibliothèque/BDD avant la finalisation**.

**Code**
- `pack/service/StoryDraftStore.kt` : `ConcurrentHashMap<String, StoryDraft>` + **TTL ~2h** (purge lazy/planifiée)
- `StoryDraft` : `id`, `title`, `description`, `thumbnailPng?`, `coverImage?`, `chapters[]` (`id`, `name`, `audio?`, `image?`, `titleAudioTts?`) — l'état structuré en mémoire
- Binaires dans un **temp dir** `storageDir/drafts/{id}/` (évite de saturer la RAM) — alternative 100 % mémoire possible
- API :
  - `POST /stories/drafts` → `{draftId}`
  - `PATCH /stories/drafts/{id}` (titre, description) · `GET /stories/drafts/{id}` (état, sans bytes)
  - `POST /stories/drafts/{id}/chapters` `{name}` → `{chapterId}`
  - `PUT /stories/drafts/{id}/chapters/{chapterId}/audio` · `…/image` (multipart)
  - `DELETE /stories/drafts/{id}` · `…/chapters/{chapterId}`
  - 404 inexistant · 410 expiré (TTL)

**Tests**
- Store : CRUD, TTL, purge
- Controller : validations, multipart, 404/410

**📄 Documentation** — `doc/story-draft-api.md` :
- Cycle de vie d'un draft (création → remplissage → finalisation → purge ; rien ne survit au redémarrage)
- Modèle de données + emplacement des binaires (temp dir)
- API complète : payloads, multipart, réponses, codes d'erreur
- Justification : pourquoi on ne persiste pas avant finalisation + exemples curl

## Étape 4 — Génération d'image de chapitre (pur Kotlin) — *indépendante*

**Objectif** : générer l'image de fallback d'un chapitre (numéro + nom) sans dépendance.

**Code**
- `pack/format/utils/ChapterImageGenerator.kt` : `BufferedImage` + `Graphics2D` (Java 2D, zéro dépendance)
  - fond paramétrable (couleurs), **numéro + nom du chapitre** centrés, typographie adaptative (truncation du nom)
  - sortie **PNG** (`ImageIO.write`) ; dimensions par défaut 640×480 (ajustables)
  - API : `generate(chapterNumber: Int, chapterName: String, width: Int, height: Int, colors…): ByteArray`
- Endpoint préview : `GET /stories/images/preview?chapterNumber=1&name=…` → PNG
- **Intégration différée** : utilisé à la finalisation (étape 5) quand `chapter.image` est absent

**Tests**
- PNG valide + dimensions attendues (`ImageIO.read`)
- Rendu non vide, truncation nom long, paramétrage couleurs

**📄 Documentation** — `doc/chapter-image-generator.md` :
- Spécification du rendu : dimensions, palette, typographie, nom long
- API du générateur + endpoint préview
- Contraintes : PNG, compatibilité future conversion FS (BMP 320×240 via l'existant)

## Étape 5 — Finalisation : draft → zip STUdio + indexation — *dépend des étapes 3 & 4*

**Objectif** : transformer un draft complet en **zip format STUdio**, écrit et indexé **une seule fois**.

**Code**
- `pack/service/CreateStoryUseCase.kt` — `finalize(draftId)` :
  1. **Validation** : titre, thumbnail, cover, chaque chapitre avec `name` + `audio` → sinon **409 « pack incomplet »** (rien n'est sauvegardé)
  2. Titre audio : `TTS(name)` si absent (`titleAudioTts`) → **concat TTS + narration fournie** (décodage PCM → concat → `anyToMp3`) ; cover audio = `TTS(titre)`
  3. Image chapitre manquante → `ChapterImageGenerator` (étape 4)
  4. Graphe **linéaire** : cover `squareOne` (type COVER, image=cover, audio=TTS(titre), `okTransition` → actionNode #1) → actionNode #n (1 option : chapitre n) → chapitre n (type STORY, image + audio concat, transition → #n+1) ; dernier chapitre sans transition ; `controlSettings` par défaut (`ok`, `home`, `pause`)
  5. `ArchiveStoryPackWriter.write(...)` (writer existant, inchangé) → zip temp
  6. `UpdatePackFileMetadataPort.updateArchiveMetadata(zipPath, thumbnailPngBytes)` → injecte `meta/thumbnail.png` (réutilise le flux d'update existant)
  7. Écrit `{uuid}.zip` dans le library path (`SettingsService`) → `packRepository.savePack(...)` (non officiel, variante `ARCHIVE`) → `{packId}` → purge du draft
- `pack/web/StoryController.kt` : `POST /stories/drafts/{id}/finalize` → `200 {packId}` ; `409` incomplet ; `404` draft inconnu
- **Conversion RAW/FS** : hors scope — réutilise l'existant `POST /packs/{id}/convert`

**Tests**
- Finalize OK : zip relisible par `ArchiveStoryPackReader`, graphe linéaire (nœuds/transitions), fallback image, TTS mocké, concat audio, thumbnail injecté
- Finalize incomplet → 409 **sans aucune écriture** (bibliothèque + BDD intacts)

**📄 Documentation** — `doc/story-creation-flow.md` :
- Pipeline complet (draft → zip → BDD → conversion), diagramme de séquence
- Mapping draft → `story.json` (squareOne, types COVER/STORY, transitions linéaires, nommage SHA-1 des assets, concat audio)
- Règles de validation (obligatoires/optionnels) + codes d'erreur
- Référence croisée `doc/pack-format-archive.md` (format STUdio) + rappel convertisseur existant

## Étape 6 — Front : formulaire de création d'histoire — *dépend du back (1→5)*

**Objectif** : le formulaire complet de création (feature Angular `story-creation`).

**Code**
- `library-web/src/app/features/story-creation/` :
  - infos (titre, description) → upload **thumbnail** + **cover** → chapitres (nom + bouton ▶ **préview TTS** via `/tts/preview`, upload audio, image optionnelle) → ajout/suppression/réordonnancement
  - bouton « Créer le pack » → `finalize` → snackbar + navigation vers le pack (refresh liste existant)
- `PacksService` : méthodes drafts (create/patch/upload/delete), `finalize`, `previewTts`, `previewImage`
- Réutilise `MatFormField`/`MatButton`/signals de `pack-detail`

**Tests**
- Component : ajout/suppression chapitres, préview TTS, appel finalize

**📄 Documentation** — `doc/story-creation-frontend.md` :
- Guide utilisateur : créer une histoire pas à pas (captures d'écran attendues)
- Structure de la feature (composants, services, state signals)
- Règles UX : champs obligatoires/optionnels, préview TTS, erreurs, post-finalisation
- Checklist de tests manuels

---

## Ordre recommandé & dépendances

`1 → 2 → 3 → 4 → 5 → 6`

- Étapes 1, 2, 3, 4 : **totalement indépendantes** (4 livrable en premier pour valider le rendu des images vite)
- Étape 5 : dépend de 3 (draft) et 4 (image) — testable avec TTS stubé
- Étape 6 : dépend de l'ensemble du back

## Hors scope (à venir)

- Moteur TTS local (Kokoro/Piper) — seule l'impl du port change
- Menus de chapitres (actionNode de choix), audio de chapitre 100 % texte → TTS
- Génération directe RAW/FS (conversion déjà existante via `POST /packs/{id}/convert`)
- Sécurisation de la clé API (chiffrement) — app locale, `settings.json` en clair assumé

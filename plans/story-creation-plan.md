# Plan — Création d'histoires depuis le front (draft sur disque + TTS BYOK)

> Statut : plan validé par l'utilisateur (questions répondues), à implémenter en 6 étapes indépendantes.
> Remplace l'ancien plan (single-shot `POST /stories`) : la création passe désormais par un **draft dans le dossier temp** finalisé en un seul `zip` STUdio.

## Objectif

Permettre à l'utilisateur de créer une histoire (pack STUdio) depuis le front :

- l'utilisateur **tape le nom de chaque chapitre** → le back génère le **titre audio** de la node via **TTS** ;
- l'utilisateur **fournit l'audio de chaque chapitre** (narration) et **une image par chapitre** (optionnelle) ;
- l'utilisateur fournit le **thumbnail** du pack et la **page principale** (cover, 1re image visible sur la Lunii) ;
- si **pas d'image** pour un chapitre → icône de la **bibliothèque Lucide** (SVG → PNG 320×240 blanc/noir) si choisie, sinon le back **génère une image** (chiffre du chapitre) en pur Kotlin ; upload SVG utilisateur accepté aussi ;
- le draft est **gardé dans le dossier temp** tant qu'il n'est pas complet : **aucun pack à moitié fait** n'est écrit sur disque ni en BDD ;
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
| Persistance | Draft **entièrement sur disque** (`drafts/{id}/draft.json` + binaires) jusqu'à finalisation ; rien en mémoire ; TTL ~2h |
| Format de sortie | `zip` format STUdio (writer existant) ; conversion RAW/FS ensuite via l'existant |
| Structure histoire | Linéaire : cover (`squareOne`) → ch1 → ch2 → … ; `controlSettings` par défaut |
| Image chapitre manquante | Icône Lucide (SVG → PNG 320×240 blanc/noir) si choisie, sinon chiffre du chapitre généré en pur Kotlin |

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

## Étape 3 — Draft store sur disque (dossier temp) — *indépendante (TTS stubable)* ✅

**Objectif** : le brouillon vit entièrement dans le dossier temp ; **aucune écriture bibliothèque/BDD avant la finalisation**, **rien en mémoire**.

**Code** (implémenté — choix utilisateur : draft **unique**, **tout sur disque**)
- `pack/service/StoryDraftStore.kt` : **un seul draft à la fois** ; créer un
  nouveau draft **remplace** l'existant ; état sérialisé dans `drafts/{id}/draft.json`
  (relecture/réécriture à chaque mutation) + **binaires sur disque** `storageDir/drafts/{id}/`
  (une histoire peut faire 2-3 h d'audio ≈ 60-200 Mo → hors heap), **purge du dossier drafts/
  au démarrage** + à chaque remplacement/suppression/finalisation ; pas de TTL, rien ne survit
  au redémarrage
- `StoryDraftState` (`pack/domain/model/StoryDraft.kt`) : `id`, `title?`, `description?`,
  `thumbnailFile?` (meta), `coverFile?` (squareOne), `chapters[]`
  (`id`, `name`, `titleAudioFile?`, `titleText?` (TTS à la finalisation — mutuellement exclusif
  avec l'audio), `narrationAudioFile?`, `imageFile?`, `iconId?`) — chemins **relatifs** au
  dossier du draft (`thumbnail.png`, `chapters/{cid}/narration.mp3`), extension = type
  (`audio/mpeg` → `.mp3`, `image/png` → `.png`…)
- API (`pack/web/StoryDraftController.kt`) :
  - `POST /stories/drafts` → 201 `{draftId}` (remplace l'existant)
  - `PATCH /stories/drafts/{id}` (titre, description) · `GET /stories/drafts/{id}` (état, sans bytes)
  - `PUT /stories/drafts/{id}/thumbnail` (multipart PNG/JPEG) · `…/cover` (multipart PNG/JPEG)
  - `POST /stories/drafts/{id}/chapters` `{name}` → 201 `{draftId, chapterId}`
  - `PUT …/chapters/{chapterId}/audio` (multipart audio/*, titre) · `…/title-text` `{text}` (TTS) ·
    `…/narration` (multipart audio/*, **audio du chapitre**) · `…/image` (multipart **PNG/JPEG** —
    SVG d'abord converti via `/render`) · `…/icon` `{iconId}`
  - `DELETE /stories/drafts/{id}` · `…/chapters/{chapterId}` → 204
  - 404 inexistant · 400 payload invalide (pas de 410 : pas de TTL)

**Tests** ✅
- Store : remplacement à la création (dossier purgé), CRUD chapitres, exclusivité audio/texte,
  binaires sur disque + extension typée, purge au démarrage, suppression des fichiers, 404
- Controller : validations, multipart (audio/narration/thumbnail/cover/image), codes 201/204/400/404

**📄 Documentation** ✅ — `doc/story-draft-api.md` :
- Cycle de vie d'un draft (création → remplissage → finalisation ; rien ne survit au redémarrage)
- Modèle de données + emplacement des binaires (temp dir, purge au démarrage)
- API complète : payloads, multipart, réponses, codes d'erreur
- **Cas d'usage complet en curl** : titre (uuid auto) → thumbnail + cover → chapitre avec image,
  titre audio (TTS ou upload) → narration → finalize
- Justification : pourquoi on ne persiste pas avant finalisation

## Étape 4 — Génération d'images de chapitre (pur Kotlin) — *indépendante*

**Objectif** : produire l'image d'un nœud de chapitre en **PNG 320×240, blanc sur fond noir**
(format Lunii), selon la hiérarchie : upload utilisateur → icône Lucide → chiffre généré.

**Code**
- `pack/format/utils/ChapterImageGenerator.kt` : `generate(chapterNumber, width=320, height=240): ByteArray` —
  chiffre blanc (#FFFFFF) centré sur fond noir (#000000), typographie adaptative, `ImageIO` PNG.
- `pack/format/utils/SvgIconRenderer.kt` (zéro dépendance) : parse l'attribut `d` des paths SVG
  (M/L/H/V/C/S/Q/T/A/Z) + formes de base (circle/ellipse/rect/line/polyline/polygon) → `Path2D` ;
  `render(svg, width=320, height=240)` — scaling depuis le `viewBox`, ratio préservé, centrage,
  **trait blanc sur fond noir** (`stroke-width` du SVG), ignore couleurs/fills/filtres/texte ;
  SVG invalide → erreur propre.
- **Bibliothèque Lucide : 4 icônes embarquées + fetch à la volée** : petit fallback offline
  (licence ISC) dans `resources/icons/` (star/home/heart/moon), **+ tout icône Lucide fetchable
  par son slug**
  (`https://cdn.jsdelivr.net/npm/lucide-static/icons/{slug}.svg`, cache mémoire, catalogue
  complet via l'API jsDelivr pour la recherche — `GET /stories/images/icons/search?q=…`).
- **Endpoints** (`pack/web/ChapterImageController.kt` ou `StoryController` existant) :
  - `GET /stories/images/icons` → `[{ id, name }]` (liste embarquée front)
  - `GET /stories/images/icons/search?q=…` → `[{ id, name }]` (recherche dans tout le catalogue Lucide, ≥ 2 chars)
  - `GET /stories/images/preview?iconId=…` ou `?chapterNumber=1` → PNG 320×240 (préview front ;
    icône embarquée sinon **fetchée à la volée** depuis lucide-static et cachée en mémoire)
  - `POST /stories/images/render` (multipart `.svg`) → PNG 320×240 (**conversion immédiate** : retour visuel direct au front, qui stocke le PNG converti dans le draft comme `chapter.image`)
- **Hiérarchie à la finalisation (étape 5)** — liée au nœud (`StageNode.image`) :
  1. `chapter.image` (PNG/JPEG uploadé, ou PNG issu de la conversion SVG) → tel quel
  2. sinon `chapter.iconId` → rendu Lucide
  3. sinon → `ChapterImageGenerator.generate(chapterNumber)` (chiffre)
- **Conversion SVG immédiate** : l'upload SVG est converti dès l'envoi (`POST /stories/images/render` →
  PNG 320×240), le front reçoit le PNG (retour visuel) et le draft ne stocke **que du PNG/JPEG**.
  Le SVG brut ne transite jamais vers le draft.

**Tests**
- Générateur : PNG 320×240 lisible (`ImageIO.read`), pixels blancs sur noir, chiffre centré
- SvgIconRenderer : paths simples et complexes, viewBox, scaling/centrage, fond noir, SVG invalide → 400
- Controller : liste des icônes, préview, upload SVG → PNG, 404 icône inconnue

**📄 Documentation** — `doc/chapter-image-generator.md` :
- Spécification du rendu : 320×240, blanc sur noir, chiffre, icônes
- API du générateur + endpoints (liste, préview, upload)
- Contraintes : PNG, conversion FS 4-bpp RLE 320×240 via l'existant

## Étape 5 — Finalisation : draft → zip STUdio + indexation — *dépend des étapes 3 & 4*

**Objectif** : transformer un draft complet en **zip format STUdio**, écrit et indexé **une seule fois**.

**Code**
- `pack/service/CreateStoryUseCase.kt` — `finalize(draftId)` :
  1. **Validation** : titre, thumbnail, cover, chaque chapitre avec `name` + `audio` → sinon **409 « pack incomplet »** (rien n'est sauvegardé)
  2. Titre audio : `TTS(name)` si absent (`titleAudioTts`) → **concat TTS + narration fournie** (décodage PCM → concat → `anyToMp3`) ; cover audio = `TTS(titre)`
  3. Image chapitre : upload utilisateur (PNG/JPEG, ou PNG issu de la conversion SVG immédiate) → sinon icône Lucide (`iconId`, rendu via `SvgIconRenderer`) → sinon `ChapterImageGenerator` (chiffre, étape 4)
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
  - infos (titre, description) → upload **thumbnail** + **cover** → chapitres (nom + bouton ▶ **préview TTS** via `/tts/preview`, upload audio, image optionnelle + **sélecteur d'icônes Lucide** avec préview si pas d'image) → ajout/suppression/réordonnancement
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

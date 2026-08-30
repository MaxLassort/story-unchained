package com.maxlass.studio.pack.web

import com.maxlass.studio.pack.domain.dto.ChapterCreatedResponse
import com.maxlass.studio.pack.domain.dto.CreateChapterRequest
import com.maxlass.studio.pack.domain.dto.DraftCreatedResponse
import com.maxlass.studio.pack.domain.dto.FinalizedPackResponse
import com.maxlass.studio.pack.domain.dto.PatchNodeRequest
import com.maxlass.studio.pack.domain.dto.StoryChapterDraftSummary
import com.maxlass.studio.pack.domain.dto.StoryDraftSummary
import com.maxlass.studio.pack.domain.dto.UpdateDraftRequest
import com.maxlass.studio.pack.domain.model.StoryDraftState
import com.maxlass.studio.pack.service.CreateStoryUseCase
import com.maxlass.studio.pack.service.StoryDraftStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

/**
 * Story draft API: single draft at a time, persisted entirely on disk in the temp folder
 * (`storageDir/drafts/{id}/draft.json` + binary files) until finalization (zip creation).
 * Binary payloads (chapter audio, chapter image) are uploaded with multipart; a chapter
 * title can alternatively be provided as text, synthesized by the TTS engine at finalization.
 */
@RestController
@RequestMapping("/stories/drafts")
@Tag(name = "Stories - Draft", description = "Brouillon d'histoire sur disque dans le dossier " +
    "temp (une seule à la fois) : création → remplissage (chapitres, audio ou texte TTS, " +
    "image) → finalisation en zip. Rien n'est gardé en mémoire ni persisté : le draft " +
    "disparaît à la fermeture de l'appli et est remplacé à la création d'une nouvelle " +
    "histoire.")
class StoryDraftController(
    private val store: StoryDraftStore,
    private val createStory: CreateStoryUseCase,
) {

    @Operation(
        summary = "Créer un nouveau brouillon",
        description = "Crée un nouveau brouillon vide. S'il existait déjà un brouillon, il est " +
            "**remplacé** (une seule histoire à la fois). Retourne l'id du draft.",
    )
    @ApiResponse(responseCode = "201", description = "Brouillon créé", content = [
        Content(examples = [
            ExampleObject(name = "Réponse", value = """{"draftId": "<uuid>"}""")
        ])
    ])
    @PostMapping
    suspend fun createDraft(): ResponseEntity<DraftCreatedResponse> {
        val draft = store.create()
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(DraftCreatedResponse(draftId = draft.id))
    }

    @Operation(
        summary = "Brouillon actuel",
        description = "Retourne l'état du brouillon actuellement sur disque (s'il existe), " +
            "sans les bytes. 404 si aucun brouillon n'existe.",
    )
    @ApiResponse(responseCode = "200", description = "État du brouillon actuel")
    @ApiResponse(responseCode = "404", description = "Aucun brouillon sur disque")
    @GetMapping("/current")
    suspend fun getCurrentDraft(): ResponseEntity<StoryDraftSummary> {
        val draft = store.findCurrent()
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(toSummary(draft))
    }

    @Operation(
        summary = "Finaliser le brouillon",
        description = "Transforme le brouillon complet en zip STUdio écrit dans la bibliothèque " +
            "puis indexé en BDD. Retourne le `packId`. 409 si le draft est incomplet (rien n'est " +
            "sauvegardé), 404 si le draft est inconnu. Le draft est purgé en cas de succès.",
    )
    @ApiResponse(responseCode = "200", description = "Pack créé", content = [
        Content(examples = [
            ExampleObject(name = "Réponse", value = """{"packId": "<uuid>"}""")
        ])
    ])
    @ApiResponse(responseCode = "409", description = "Draft incomplet")
    @ApiResponse(responseCode = "404", description = "Draft inconnu")
    @PostMapping("/{id}/finalize")
    suspend fun finalizeDraft(@PathVariable id: String): ResponseEntity<FinalizedPackResponse> {
        val packId = createStory.finalize(id)
        return ResponseEntity.ok(FinalizedPackResponse(packId = packId))
    }

    @Operation(
        summary = "Télécharger la thumbnail",
        description = "Retourne les bytes bruts de la thumbnail stockée dans le draft.",
    )
    @ApiResponse(responseCode = "200", description = "Image binaire")
    @ApiResponse(responseCode = "404", description = "Brouillon ou thumbnail inexistante")
    @GetMapping("/{id}/thumbnail/file")
    suspend fun downloadThumbnail(@PathVariable id: String): ResponseEntity<ByteArray> {
        val draft = store.get(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found: $id")
        val rel = draft.thumbnailFile
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No thumbnail in draft")
        return serveBinary(id, rel)
    }

    @Operation(
        summary = "Télécharger la cover",
        description = "Retourne les bytes bruts de la cover (squareOne) stockée dans le draft.",
    )
    @ApiResponse(responseCode = "200", description = "Image binaire")
    @ApiResponse(responseCode = "404", description = "Brouillon ou cover inexistante")
    @GetMapping("/{id}/cover/file")
    suspend fun downloadCover(@PathVariable id: String): ResponseEntity<ByteArray> {
        val draft = store.get(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found: $id")
        val rel = draft.coverFile
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No cover in draft")
        return serveBinary(id, rel)
    }

    @Operation(
        summary = "Télécharger l'audio du titre du pack",
        description = "Retourne les bytes bruts de l'audio du titre stocké dans le draft.",
    )
    @ApiResponse(responseCode = "200", description = "Audio binaire")
    @ApiResponse(responseCode = "404", description = "Brouillon ou audio de titre inexistant")
    @GetMapping("/{id}/title-audio/file")
    suspend fun downloadTitleAudio(@PathVariable id: String): ResponseEntity<ByteArray> {
        val draft = store.get(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found: $id")
        val rel = draft.titleAudioFile
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No title audio in draft")
        return serveBinary(id, rel)
    }

    @Operation(
        summary = "Télécharger l'audio du menu de sélection",
        description = "Retourne les bytes bruts de l'audio du menu stocké dans le draft.",
    )
    @ApiResponse(responseCode = "200", description = "Audio binaire")
    @ApiResponse(responseCode = "404", description = "Brouillon ou audio de menu inexistant")
    @GetMapping("/{id}/menu-audio/file")
    suspend fun downloadMenuAudio(@PathVariable id: String): ResponseEntity<ByteArray> {
        val draft = store.get(id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found: $id")
        val rel = draft.menuAudioFile
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No menu audio in draft")
        return serveBinary(id, rel)
    }

    @Operation(
        summary = "Télécharger l'audio du titre d'un chapitre",
        description = "Retourne les bytes bruts de l'audio du titre du chapitre stocké dans le draft.",
    )
    @ApiResponse(responseCode = "200", description = "Audio binaire")
    @ApiResponse(responseCode = "404", description = "Brouillon, chapitre ou audio inexistant")
    @GetMapping("/{id}/chapters/{chapterId}/title-audio/file")
    suspend fun downloadChapterTitleAudio(
        @PathVariable id: String,
        @PathVariable chapterId: String,
    ): ResponseEntity<ByteArray> {
        val rel = chapterOf(id, chapterId).titleAudioFile
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No chapter title audio in draft")
        return serveBinary(id, rel)
    }

    @Operation(
        summary = "Télécharger l'audio de narration d'un chapitre",
        description = "Retourne les bytes bruts de la narration du chapitre stockée dans le draft.",
    )
    @ApiResponse(responseCode = "200", description = "Audio binaire")
    @ApiResponse(responseCode = "404", description = "Brouillon, chapitre ou narration inexistante")
    @GetMapping("/{id}/chapters/{chapterId}/narration/file")
    suspend fun downloadChapterNarration(
        @PathVariable id: String,
        @PathVariable chapterId: String,
    ): ResponseEntity<ByteArray> {
        val rel = chapterOf(id, chapterId).narrationAudioFile
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No narration audio in draft")
        return serveBinary(id, rel)
    }

    @Operation(
        summary = "Télécharger l'image d'un chapitre",
        description = "Retourne les bytes bruts de l'image du chapitre stockée dans le draft.",
    )
    @ApiResponse(responseCode = "200", description = "Image binaire")
    @ApiResponse(responseCode = "404", description = "Brouillon, chapitre ou image inexistante")
    @GetMapping("/{id}/chapters/{chapterId}/image/file")
    suspend fun downloadChapterImage(
        @PathVariable id: String,
        @PathVariable chapterId: String,
    ): ResponseEntity<ByteArray> {
        val rel = chapterOf(id, chapterId).imageFile
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No chapter image in draft")
        return serveBinary(id, rel)
    }

    @Operation(
        summary = "État du brouillon",
        description = "Retourne l'état complet du brouillon (titre, description, chapitres) " +
            "**sans les bytes** : audio et image sont résumés par leur taille.",
    )
    @ApiResponse(responseCode = "200", description = "État du brouillon")
    @ApiResponse(responseCode = "404", description = "Brouillon inconnu")
    @GetMapping("/{id}")
    suspend fun getDraft(@PathVariable id: String): StoryDraftSummary =
        store.get(id)?.let(::toSummary)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found: $id")

    @Operation(
        summary = "Modifier titre et description",
        description = "Met à jour le titre et/ou la description du brouillon (champs optionnels).",
    )
    @ApiResponse(responseCode = "200", description = "Brouillon mis à jour")
    @ApiResponse(responseCode = "404", description = "Brouillon inconnu")
    @PatchMapping("/{id}")
    suspend fun updateDraft(
        @PathVariable id: String,
        @Valid @RequestBody body: UpdateDraftRequest,
    ): StoryDraftSummary =
        store.updateMetadata(id, body.title, body.description)?.let(::toSummary)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found: $id")

    @Operation(
        summary = "Supprimer le brouillon",
        description = "Supprime le brouillon (fichiers du dossier temp, équivalent à quitter " +
            "l'appli).",
    )
    @ApiResponse(responseCode = "204", description = "Brouillon supprimé")
    @ApiResponse(responseCode = "404", description = "Brouillon inconnu")
    @DeleteMapping("/{id}")
    suspend fun deleteDraft(@PathVariable id: String): ResponseEntity<Unit> {
        if (!store.clear(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found: $id")
        }
        return ResponseEntity.noContent().build()
    }

    @Operation(
        summary = "Ajouter un chapitre",
        description = "Ajoute un chapitre au brouillon. `name` est le nom du chapitre ; " +
            "l'audio du titre peut ensuite être fourni soit par upload (PUT …/files, " +
            "field=titleAudio), soit par texte (PATCH …/nodes/{chapterId}, titleText) — " +
            "la synthèse TTS se fait immédiatement et le MP3 est stocké dans le draft.",
    )
    @ApiResponse(responseCode = "201", description = "Chapitre créé", content = [
        Content(examples = [
            ExampleObject(name = "Réponse", value = """{"draftId": "<uuid>", "chapterId": "<uuid>"}""")
        ])
    ])
    @ApiResponse(responseCode = "400", description = "Nom vide")
    @ApiResponse(responseCode = "404", description = "Brouillon inconnu")
    @PostMapping("/{id}/chapters")
    suspend fun addChapter(
        @PathVariable id: String,
        @Valid @RequestBody body: CreateChapterRequest,
    ): ResponseEntity<ChapterCreatedResponse> {
        val chapter = store.addChapter(id, body.name.trim())
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found: $id")
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ChapterCreatedResponse(draftId = id, chapterId = chapter.id))
    }

    @Operation(
        summary = "Supprimer un chapitre",
        description = "Retire le chapitre du brouillon.",
    )
    @ApiResponse(responseCode = "204", description = "Chapitre supprimé")
    @ApiResponse(responseCode = "404", description = "Brouillon ou chapitre inconnu")
    @DeleteMapping("/{id}/chapters/{chapterId}")
    suspend fun deleteChapter(
        @PathVariable id: String,
        @PathVariable chapterId: String,
    ): ResponseEntity<Unit> {
        store.removeChapter(id, chapterId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft or chapter not found")
        return ResponseEntity.noContent().build()
    }



    @Operation(
        summary = "Uploader un fichier binaire dans le draft (endpoint consolidé)",
        description = "Upload multipart (`file`) avec une cible : `scope` = `pack` ou `chapter`, " +
            "`chapterId` (si scope=chapter) et `field` = `titleAudio` | `menuAudio` | `thumbnail` | " +
            "`cover` | `narration` | `image`. Valide le content-type selon le champ.",
    )
    @ApiResponse(responseCode = "200", description = "Fichier enregistré (état du draft)")
    @ApiResponse(responseCode = "400", description = "Cible ou fichier invalide")
    @ApiResponse(responseCode = "404", description = "Brouillon ou chapitre inconnu")
    @PutMapping("/{id}/files", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    suspend fun setDraftFile(
        @PathVariable id: String,
        @Parameter(description = "Scope de la cible : `pack` ou `chapter`")
        @RequestParam scope: String,
        @Parameter(description = "Id du chapitre (requis si scope=chapter)")
        @RequestParam(required = false) chapterId: String?,
        @Parameter(description = "Champ cible : titleAudio | menuAudio | thumbnail | cover | narration | image")
        @RequestParam field: String,
        @RequestPart("file") file: MultipartFile,
    ): StoryDraftSummary {
        val family = store.binaryFields[field]
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown field: $field")
        val type = when (family) {
            "audio" -> audioTypeOf(file)
            "image" -> imageTypeOf(file)
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown field: $field")
        }
        return store.setDraftFile(id, scope, chapterId, field, file.bytes, type)
            ?.let(::toSummary)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft, chapter or target not found")
    }

    @Operation(
        summary = "Éditer un nœud du draft (endpoint consolidé)",
        description = "Applique un patch partiel sur le nœud racine du pack (`nodeId` = id du " +
            "draft) ou sur un chapitre (`nodeId` = id du chapitre). Champs : `name`, `titleText` " +
            "et `menuText` (racine du pack) ou `name`, `titleText`, `iconId` (chapitre). Les " +
            "textes TTS sont synthétisés immédiatement et stockés dans le draft.",
    )
    @ApiResponse(responseCode = "200", description = "Nœud mis à jour (état du draft)")
    @ApiResponse(responseCode = "409", description = "Clé API TTS manquante pour le provider configuré")
    @ApiResponse(responseCode = "404", description = "Brouillon ou nœud inconnu")
    @PatchMapping("/{id}/nodes/{nodeId}")
    suspend fun patchNode(
        @PathVariable id: String,
        @PathVariable nodeId: String,
        @Valid @RequestBody body: PatchNodeRequest,
    ): StoryDraftSummary =
        store.patchNode(
            id,
            nodeId,
            name = body.name?.trim()?.takeIf { it.isNotEmpty() },
            titleText = body.titleText?.trim()?.takeIf { it.isNotEmpty() },
            menuText = body.menuText?.trim()?.takeIf { it.isNotEmpty() },
            iconId = body.iconId?.trim()?.takeIf { it.isNotEmpty() },
        )?.let(::toSummary)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft or node not found: $nodeId")

    /** Validates a PNG/JPEG multipart payload and returns its normalized content type. */
    private fun imageTypeOf(file: MultipartFile): String {
        val type = file.contentType?.lowercase()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "PNG or JPEG image required")
        if (file.isEmpty || type !in setOf("image/png", "image/jpeg", "image/jpg")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "PNG or JPEG image required")
        }
        return type
    }

    /** Validates an audio multipart payload and returns its normalized content type. */
    private fun audioTypeOf(file: MultipartFile): String {
        val type = file.contentType?.lowercase()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Audio file required (audio/*)")
        if (file.isEmpty || !type.startsWith("audio/")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Audio file required (audio/*)")
        }
        return type
    }

    private fun toSummary(draft: StoryDraftState): StoryDraftSummary = StoryDraftSummary(
        id = draft.id,
        title = draft.title,
        description = draft.description,
        hasThumbnail = draft.thumbnailFile != null,
        thumbnailBytes = sizeOf(draft.id, draft.thumbnailFile),
        hasCover = draft.coverFile != null,
        coverBytes = sizeOf(draft.id, draft.coverFile),
        hasTitleAudio = draft.titleAudioFile != null,
        titleAudioBytes = sizeOf(draft.id, draft.titleAudioFile),
        titleText = draft.titleText,
        hasMenuAudio = draft.menuAudioFile != null,
        menuAudioBytes = sizeOf(draft.id, draft.menuAudioFile),
        menuText = draft.menuText,
        chapters = draft.chapters.map { chapter ->
            StoryChapterDraftSummary(
                id = chapter.id,
                name = chapter.name,
                hasTitleAudio = chapter.titleAudioFile != null,
                titleAudioBytes = sizeOf(draft.id, chapter.titleAudioFile),
                titleText = chapter.titleText,
                hasNarrationAudio = chapter.narrationAudioFile != null,
                narrationAudioBytes = sizeOf(draft.id, chapter.narrationAudioFile),
                hasImage = chapter.imageFile != null,
                imageBytes = sizeOf(draft.id, chapter.imageFile),
                iconId = chapter.iconId,
            )
        },
    )

    private fun sizeOf(draftId: String, relativeFile: String?): Long =
        relativeFile?.let { store.draftDir(draftId).resolve(it).toFile().length() } ?: 0

    /** Serves a binary file from the draft dir, inferring Content-Type from the extension. */
    private fun serveBinary(draftId: String, relativePath: String): ResponseEntity<ByteArray> {
        val bytes = store.readBinary(draftId, relativePath)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "File not found: $relativePath")
        val mediaType = when {
            relativePath.endsWith(".png") -> MediaType.IMAGE_PNG
            relativePath.endsWith(".jpg") || relativePath.endsWith(".jpeg") -> MediaType.IMAGE_JPEG
            relativePath.endsWith(".mp3") -> MediaType.parseMediaType("audio/mpeg")
            relativePath.endsWith(".wav") -> MediaType.parseMediaType("audio/wav")
            relativePath.endsWith(".ogg") -> MediaType.parseMediaType("audio/ogg")
            relativePath.endsWith(".m4a") -> MediaType.parseMediaType("audio/mp4")
            else -> MediaType.APPLICATION_OCTET_STREAM
        }
        return ResponseEntity.ok().contentType(mediaType).body(bytes)
    }

    /** Looks up a chapter in the draft, or throws 404. */
    private fun chapterOf(id: String, chapterId: String) =
        store.get(id)?.chapters?.find { it.id == chapterId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter not found: $chapterId")
}
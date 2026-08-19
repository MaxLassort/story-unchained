package com.maxlass.studio.pack.web

import com.maxlass.studio.pack.domain.dto.ChapterCreatedResponse
import com.maxlass.studio.pack.domain.dto.CreateChapterRequest
import com.maxlass.studio.pack.domain.dto.DraftCreatedResponse
import com.maxlass.studio.pack.domain.dto.SetChapterIconRequest
import com.maxlass.studio.pack.domain.dto.SetTitleTextRequest
import com.maxlass.studio.pack.domain.dto.StoryChapterDraftSummary
import com.maxlass.studio.pack.domain.dto.StoryDraftSummary
import com.maxlass.studio.pack.domain.dto.UpdateDraftRequest
import com.maxlass.studio.pack.domain.model.StoryDraft
import com.maxlass.studio.pack.service.StoryDraftStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
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
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

/**
 * In-memory story draft: single draft at a time, everything in memory until finalization
 * (zip creation). Binary payloads (chapter audio, chapter image) are uploaded with
 * multipart; a chapter title can alternatively be provided as text, synthesized by the
 * TTS engine at finalization.
 */
@RestController
@RequestMapping("/stories/drafts")
@Tag(name = "Stories - Draft", description = "Brouillon d'histoire en mémoire (une seule à la " +
    "fois) : création → remplissage (chapitres, audio ou texte TTS, image) → finalisation en zip. " +
    "Rien n'est persisté : le draft disparaît à la fermeture de l'appli et est remplacé à la " +
    "création d'une nouvelle histoire.")
class StoryDraftController(
    private val store: StoryDraftStore,
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
    fun createDraft(): ResponseEntity<DraftCreatedResponse> {
        val draft = store.create()
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(DraftCreatedResponse(draftId = draft.id))
    }

    @Operation(
        summary = "État du brouillon",
        description = "Retourne l'état complet du brouillon (titre, description, chapitres) " +
            "**sans les bytes** : audio et image sont résumés par leur taille.",
    )
    @ApiResponse(responseCode = "200", description = "État du brouillon")
    @ApiResponse(responseCode = "404", description = "Brouillon inconnu")
    @GetMapping("/{id}")
    fun getDraft(@PathVariable id: String): StoryDraftSummary =
        store.get(id)?.let(::toSummary)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found: $id")

    @Operation(
        summary = "Modifier titre et description",
        description = "Met à jour le titre et/ou la description du brouillon (champs optionnels).",
    )
    @ApiResponse(responseCode = "200", description = "Brouillon mis à jour")
    @ApiResponse(responseCode = "404", description = "Brouillon inconnu")
    @PatchMapping("/{id}")
    fun updateDraft(
        @PathVariable id: String,
        @RequestBody body: UpdateDraftRequest,
    ): StoryDraftSummary =
        store.updateMetadata(id, body.title, body.description)?.let(::toSummary)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found: $id")

    @Operation(
        summary = "Uploader la thumbnail (meta/thumbnail.png)",
        description = "Upload multipart (`file`, PNG ou JPEG) = vignette du pack injectée dans " +
            "`meta/thumbnail.png` à la finalisation (affichage bibliothèque).",
    )
    @ApiResponse(responseCode = "200", description = "Thumbnail enregistrée (état du draft)")
    @ApiResponse(responseCode = "400", description = "Fichier vide ou non PNG/JPEG")
    @ApiResponse(responseCode = "404", description = "Brouillon inconnu")
    @PutMapping("/{id}/thumbnail", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun setThumbnail(
        @PathVariable id: String,
        @Parameter(description = "Fichier image (PNG ou JPEG)", schema = Schema(type = "string", format = "binary"))
        @RequestPart("file") file: MultipartFile,
    ): StoryDraftSummary {
        val type = file.contentType?.lowercase()
        if (file.isEmpty || !(type == "image/png" || type == "image/jpeg" || type == "image/jpg")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "PNG or JPEG image required")
        }
        return store.setThumbnail(id, file.bytes, type!!)?.let(::toSummary)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found: $id")
    }

    @Operation(
        summary = "Uploader la cover (thumbnail Lunii)",
        description = "Upload multipart (`file`, PNG ou JPEG) = image du nœud squareOne (cover " +
            "de l'histoire, affichée par la Lunii au démarrage du pack).",
    )
    @ApiResponse(responseCode = "200", description = "Cover enregistrée (état du draft)")
    @ApiResponse(responseCode = "400", description = "Fichier vide ou non PNG/JPEG")
    @ApiResponse(responseCode = "404", description = "Brouillon inconnu")
    @PutMapping("/{id}/cover", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun setCover(
        @PathVariable id: String,
        @Parameter(description = "Fichier image (PNG ou JPEG)", schema = Schema(type = "string", format = "binary"))
        @RequestPart("file") file: MultipartFile,
    ): StoryDraftSummary {
        val type = file.contentType?.lowercase()
        if (file.isEmpty || !(type == "image/png" || type == "image/jpeg" || type == "image/jpg")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "PNG or JPEG image required")
        }
        return store.setCover(id, file.bytes, type!!)?.let(::toSummary)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found: $id")
    }

    @Operation(
        summary = "Supprimer le brouillon",
        description = "Vide le brouillon en mémoire (équivalent à quitter l'appli).",
    )
    @ApiResponse(responseCode = "204", description = "Brouillon supprimé")
    @ApiResponse(responseCode = "404", description = "Brouillon inconnu")
    @DeleteMapping("/{id}")
    fun deleteDraft(@PathVariable id: String): ResponseEntity<Unit> {
        if (!store.clear(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft not found: $id")
        }
        return ResponseEntity.noContent().build()
    }

    @Operation(
        summary = "Ajouter un chapitre",
        description = "Ajoute un chapitre au brouillon. `name` est le nom du chapitre ; " +
            "l'audio du titre peut ensuite être fourni soit par upload (PUT …/audio), soit par " +
            "texte (PUT …/title-text) — la synthèse TTS se fait à la finalisation.",
    )
    @ApiResponse(responseCode = "201", description = "Chapitre créé", content = [
        Content(examples = [
            ExampleObject(name = "Réponse", value = """{"draftId": "<uuid>", "chapterId": "<uuid>"}""")
        ])
    ])
    @ApiResponse(responseCode = "400", description = "Nom vide")
    @ApiResponse(responseCode = "404", description = "Brouillon inconnu")
    @PostMapping("/{id}/chapters")
    fun addChapter(
        @PathVariable id: String,
        @RequestBody body: CreateChapterRequest,
    ): ResponseEntity<ChapterCreatedResponse> {
        if (body.name.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Chapter name must not be blank")
        }
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
    fun deleteChapter(
        @PathVariable id: String,
        @PathVariable chapterId: String,
    ): ResponseEntity<Unit> {
        store.removeChapter(id, chapterId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft or chapter not found")
        return ResponseEntity.noContent().build()
    }

    @Operation(
        summary = "Uploader l'audio du titre du chapitre",
        description = "Upload multipart (`file`, audio/* : MP3, WAV, OGG…) = audio du titre du " +
            "chapitre. Remplace tout texte TTS précédemment saisi pour ce chapitre.",
    )
    @ApiResponse(responseCode = "200", description = "Audio enregistré (état du draft)")
    @ApiResponse(responseCode = "400", description = "Fichier vide ou non-audio")
    @ApiResponse(responseCode = "404", description = "Brouillon ou chapitre inconnu")
    @PutMapping("/{id}/chapters/{chapterId}/audio", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun setChapterAudio(
        @PathVariable id: String,
        @PathVariable chapterId: String,
        @Parameter(description = "Fichier audio (MP3, WAV, OGG…)", schema = Schema(type = "string", format = "binary"))
        @RequestPart("file") file: MultipartFile,
    ): StoryDraftSummary {
        if (file.isEmpty || !(file.contentType?.lowercase()?.startsWith("audio/") == true)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Audio file required (audio/*)")
        }
        return store.setTitleAudio(id, chapterId, file.bytes, file.contentType!!)?.let(::toSummary)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft or chapter not found")
    }

    @Operation(
        summary = "Uploader l'audio du chapitre (narration)",
        description = "Upload multipart (`file`, audio/*) = la narration du chapitre elle-même " +
            "(peut durer des heures au total pour toute l'histoire). Stocké sur disque, jamais " +
            "en RAM.",
    )
    @ApiResponse(responseCode = "200", description = "Audio enregistré (état du draft)")
    @ApiResponse(responseCode = "400", description = "Fichier vide ou non-audio")
    @ApiResponse(responseCode = "404", description = "Brouillon ou chapitre inconnu")
    @PutMapping("/{id}/chapters/{chapterId}/narration", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun setChapterNarration(
        @PathVariable id: String,
        @PathVariable chapterId: String,
        @Parameter(description = "Fichier audio de narration (MP3, WAV, OGG…)", schema = Schema(type = "string", format = "binary"))
        @RequestPart("file") file: MultipartFile,
    ): StoryDraftSummary {
        if (file.isEmpty || !(file.contentType?.lowercase()?.startsWith("audio/") == true)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Audio file required (audio/*)")
        }
        return store.setNarrationAudio(id, chapterId, file.bytes, file.contentType!!)?.let(::toSummary)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft or chapter not found")
    }

    @Operation(
        summary = "Saisir le texte du titre (TTS)",
        description = "Texte du titre du chapitre à la place d'un fichier audio : la synthèse " +
            "TTS (provider configuré dans les settings) est faite à la finalisation. " +
            "Remplace tout audio uploadé précédemment pour ce chapitre.",
    )
    @ApiResponse(responseCode = "200", description = "Texte enregistré (état du draft)")
    @ApiResponse(responseCode = "400", description = "Texte vide")
    @ApiResponse(responseCode = "404", description = "Brouillon ou chapitre inconnu")
    @PutMapping("/{id}/chapters/{chapterId}/title-text")
    fun setChapterTitleText(
        @PathVariable id: String,
        @PathVariable chapterId: String,
        @RequestBody body: SetTitleTextRequest,
    ): StoryDraftSummary {
        if (body.text.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Title text must not be blank")
        }
        return store.setTitleText(id, chapterId, body.text.trim())?.let(::toSummary)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft or chapter not found")
    }

    @Operation(
        summary = "Uploader l'image du chapitre",
        description = "Upload multipart (`file`, PNG ou JPEG uniquement — un SVG doit d'abord " +
            "être converti via POST /stories/images/render). L'icône Lucide (`iconId`) reste " +
            "le fallback si aucune image n'est uploadée.",
    )
    @ApiResponse(responseCode = "200", description = "Image enregistrée (état du draft)")
    @ApiResponse(responseCode = "400", description = "Fichier vide ou non PNG/JPEG")
    @ApiResponse(responseCode = "404", description = "Brouillon ou chapitre inconnu")
    @PutMapping("/{id}/chapters/{chapterId}/image", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun setChapterImage(
        @PathVariable id: String,
        @PathVariable chapterId: String,
        @Parameter(description = "Fichier image (PNG ou JPEG)", schema = Schema(type = "string", format = "binary"))
        @RequestPart("file") file: MultipartFile,
    ): StoryDraftSummary {
        val type = file.contentType?.lowercase()
        if (file.isEmpty || !(type == "image/png" || type == "image/jpeg" || type == "image/jpg")) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "PNG or JPEG image required")
        }
        return store.setChapterImage(id, chapterId, file.bytes, type!!)?.let(::toSummary)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft or chapter not found")
    }

    @Operation(
        summary = "Choisir l'icône Lucide du chapitre",
        description = "Associe un icône Lucide (slug kebab-case, ex. \"star\") au chapitre : " +
            "l'image est rendue à la finalisation (blanc sur noir 320×240). Fallback si aucune " +
            "image n'est uploadée.",
    )
    @ApiResponse(responseCode = "200", description = "Icône enregistrée (état du draft)")
    @ApiResponse(responseCode = "400", description = "Slug vide")
    @ApiResponse(responseCode = "404", description = "Brouillon ou chapitre inconnu")
    @PutMapping("/{id}/chapters/{chapterId}/icon")
    fun setChapterIcon(
        @PathVariable id: String,
        @PathVariable chapterId: String,
        @RequestBody body: SetChapterIconRequest,
    ): StoryDraftSummary {
        if (body.iconId.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Icon id must not be blank")
        }
        return store.setChapterIcon(id, chapterId, body.iconId.trim())?.let(::toSummary)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Draft or chapter not found")
    }

    private fun toSummary(draft: StoryDraft): StoryDraftSummary = StoryDraftSummary(
        id = draft.id,
        title = draft.title,
        description = draft.description,
        hasThumbnail = draft.thumbnailPath != null,
        thumbnailBytes = draft.thumbnailPath?.toFile()?.length()?.toInt() ?: 0,
        hasCover = draft.coverPath != null,
        coverBytes = draft.coverPath?.toFile()?.length()?.toInt() ?: 0,
        chapters = draft.chapters.map { chapter ->
            StoryChapterDraftSummary(
                id = chapter.id,
                name = chapter.name,
                hasTitleAudio = chapter.titleAudioPath != null,
                titleAudioBytes = chapter.titleAudioPath?.toFile()?.length()?.toInt() ?: 0,
                titleText = chapter.titleText,
                hasNarrationAudio = chapter.narrationAudioPath != null,
                narrationAudioBytes = chapter.narrationAudioPath?.toFile()?.length()?.toInt() ?: 0,
                hasImage = chapter.imagePath != null,
                imageBytes = chapter.imagePath?.toFile()?.length()?.toInt() ?: 0,
                iconId = chapter.iconId,
            )
        },
    )
}
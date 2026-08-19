package com.maxlass.studio.core.web

import com.maxlass.studio.settings.service.SettingsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Tag(name = "Status", description = "État du serveur")
class RootController(
    private val settingsService: SettingsService,
) {

    @Operation(
        summary = "État du serveur",
        description = "Retourne une chaîne de texte indiquant que le serveur tourne et affiche le chemin " +
            "de la bibliothèque configurée. Utile pour vérifier rapidement que le back est accessible " +
            "(ex. http://localhost:9090/).",
    )
    @GetMapping("/")
    suspend fun status(): String =
        "StoryUnchained Server running | Library path: ${settingsService.getLibraryPath()}"
}

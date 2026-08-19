package com.maxlass.studio.settings.web

import com.maxlass.studio.pack.service.SyncPacksService
import com.maxlass.studio.settings.domain.Settings
import com.maxlass.studio.settings.service.SettingsService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/settings")
@Tag(name = "Settings", description = "Configuration utilisateur (bibliothèque, TTS BYOK…). " +
    "Stocker ici les clés API TTS personnelles (en clair, app locale).")
class SettingsController(
    private val settingsService: SettingsService,
    private val syncPacks: SyncPacksService,
) {

    @Operation(
        summary = "Lire les settings",
        description = "Retourne l'objet complet des settings : chemin de la bibliothèque, " +
            "base officielle, type de cible, et configuration TTS (provider, clés API BYOK, " +
            "voix, langue). Les clés sont renvoyées en clair (application locale).",
    )
    @ApiResponse(responseCode = "200", description = "Settings actuels")
    @GetMapping
    suspend fun getSettings(): Settings = settingsService.getSettings()

    @Operation(
        summary = "Enregistrer les settings",
        description = "Enregistre l'objet complet des settings (mêmes champs que GET /settings). " +
            "Si `libraryPath` change, la bibliothèque est re-scannée automatiquement. " +
            "Retourne les settings enregistrés.",
    )
    @ApiResponse(responseCode = "200", description = "Settings enregistrés", content = [
        Content(schema = Schema(implementation = Settings::class), examples = [
            ExampleObject(name = "Exemple", value = """
                {
                  "libraryPath": "/Users/me/Documents/luniiUnchained/Packs",
                  "unofficialDbPath": null,
                  "targetDeviceType": null,
                  "ttsProvider": "OPENAI",
                  "ttsOpenAiApiKey": "sk-...",
                  "ttsElevenLabsApiKey": "sk_...",
                  "ttsVoice": "alloy",
                  "ttsLang": "fr"
                }
            """)
        ])
    ])
    @PutMapping
    suspend fun updateSettings(@RequestBody body: Settings): Settings {
        val current = settingsService.getSettings()
        val libraryChanged = body.libraryPath != current.libraryPath

        if (libraryChanged) {
            settingsService.updateLibraryPath(body.libraryPath)
            settingsService.saveSettings(current.copy(
                libraryPath = body.libraryPath,
                unofficialDbPath = body.unofficialDbPath,
                targetDeviceType = body.targetDeviceType
            ))
        } else {
            settingsService.saveSettings(body)
        }

        if (libraryChanged) {
            syncPacks.clearPacks()
            syncPacks.invoke(body.libraryPath)
        }
        return settingsService.getSettings()
    }
}

package com.maxlass.studio.settings.web

import com.maxlass.studio.pack.service.SyncPacksService
import com.maxlass.studio.settings.domain.Settings
import com.maxlass.studio.settings.service.SettingsService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/settings")
class SettingsController(
    private val settingsService: SettingsService,
    private val syncPacks: SyncPacksService,
) {

    @GetMapping
    suspend fun getSettings(): Settings = settingsService.getSettings()

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

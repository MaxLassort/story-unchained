package com.maxlass.studio.settings.service

import com.maxlass.studio.settings.domain.Settings
import com.maxlass.studio.settings.domain.SettingsRepository
import org.springframework.stereotype.Service
import java.io.File

/** Application service for user settings (e.g. library path). */
@Service
class SettingsService(private val settingsRepository: SettingsRepository) {

    /** Returns the full settings object (for GET /settings). */
    suspend fun getSettings(): Settings = settingsRepository.getSettings()

    /** Saves the full settings object (for PUT /settings). */
    suspend fun saveSettings(settings: Settings) {
        settingsRepository.saveSettings(settings)
    }

    /** Returns the configured library path for packs (creates default on first access). */
    suspend fun getLibraryPath(): String = settingsRepository.getSettings().libraryPath

    /** Updates the library path and ensures the directory exists. */
    suspend fun updateLibraryPath(newPath: String) {
        val dir = File(newPath)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val current = settingsRepository.getSettings()
        settingsRepository.saveSettings(current.copy(libraryPath = newPath))
    }
}

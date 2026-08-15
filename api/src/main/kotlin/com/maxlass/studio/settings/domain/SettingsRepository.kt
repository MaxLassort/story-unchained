package com.maxlass.studio.settings.domain

/** Port for loading and saving user settings. */
interface SettingsRepository {
    suspend fun getSettings(): Settings
    suspend fun saveSettings(settings: Settings)
}

package com.maxlass.studio.settings.data

import com.maxlass.studio.infrastructure.config.StudioProperties
import com.maxlass.studio.settings.domain.Settings
import com.maxlass.studio.settings.domain.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Repository
import java.io.File

private val settingsJson = Json { prettyPrint = true }

/** [SettingsRepository] implementation using a JSON file (path from [StudioProperties]). */
@Repository
class SettingsRepositoryImpl(
    private val studioProperties: StudioProperties,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SettingsRepository {

    private val settingsFile: File
        get() = studioProperties.settingsFile.toFile()

    init {
        if (!studioProperties.storageDir.toFile().exists()) {
            studioProperties.storageDir.toFile().mkdirs()
        }
    }

    override suspend fun getSettings(): Settings = withContext(ioDispatcher) {
        if (!settingsFile.exists()) {
            val defaultLibrary = studioProperties.defaultLibraryPath.toFile()
            if (!defaultLibrary.exists()) defaultLibrary.mkdirs()

            val defaultSettings = Settings(
                libraryPath = defaultLibrary.absolutePath,
                unofficialDbPath = null,
                targetDeviceType = null
            )
            saveSettings(defaultSettings)
            return@withContext defaultSettings
        }

        val jsonString = settingsFile.readText()
        settingsJson.decodeFromString<Settings>(jsonString)
    }

    override suspend fun saveSettings(settings: Settings) = withContext(ioDispatcher) {
        val jsonString = settingsJson.encodeToString(Settings.serializer(), settings)
        settingsFile.writeText(jsonString)
    }
}

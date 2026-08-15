package com.maxlass.studio.config.data

import com.maxlass.studio.config.domain.AppConfig
import com.maxlass.studio.config.domain.ConfigRepository
import com.maxlass.studio.infrastructure.config.StudioProperties
import kotlinx.serialization.json.Json
import org.springframework.stereotype.Repository

/** [ConfigRepository] implementation that reads/writes a JSON file (path from [StudioProperties]). */
@Repository
class FileConfigRepository(
    private val studioProperties: StudioProperties,
) : ConfigRepository {

    private val configFile
        get() = studioProperties.configFile.toFile()

    override suspend fun getConfig(): AppConfig {
        return if (configFile.exists()) {
            val json = configFile.readText()
            try {
                Json.decodeFromString<AppConfig>(json)
            } catch (_: Exception) {
                AppConfig()
            }
        } else {
            AppConfig()
        }
    }

    override suspend fun saveConfig(config: AppConfig) {
        val json = Json.encodeToString(AppConfig.serializer(), config)
        configFile.writeText(json)
    }
}

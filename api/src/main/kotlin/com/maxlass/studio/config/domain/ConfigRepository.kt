package com.maxlass.studio.config.domain

/** Port for loading and saving application configuration. */
interface ConfigRepository {
    suspend fun getConfig(): AppConfig
    suspend fun saveConfig(config: AppConfig)
}

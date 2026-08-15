package com.maxlass.studio.config.service

import com.maxlass.studio.config.domain.AppConfig
import com.maxlass.studio.config.domain.ConfigRepository
import org.springframework.stereotype.Service

/** Application service for application configuration. */
@Service
class ConfigService(private val configRepository: ConfigRepository) {

    suspend fun getConfig(): AppConfig {
        return configRepository.getConfig()
    }

    suspend fun saveConfig(config: AppConfig) {
        configRepository.saveConfig(config)
    }
}

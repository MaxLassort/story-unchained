package com.maxlass.api.config

import com.maxlass.studio.infrastructure.config.KotlinxJsonHttpMessageConverter
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * CORS global : remplace l'intercept Ktor (echo de l'Origin + gestion OPTIONS)
 * pour parité avec le frontend Angular library-web.
 */
@Configuration
class WebConfig : WebMvcConfigurer {

    /**
     * Longer async timeout for `suspend fun` controllers. Finalizing a draft (`/finalize`)
     * synthesizes TTS for the cover/menu/chapter titles (free Google Translate fallback when no
     * provider is configured) and writes a zip: for a multi-chapter story this routinely exceeds
     * Spring MVC's default 30s timeout, causing an `AsyncRequestTimeoutException` (HTTP 500).
     */
    override fun configureAsyncSupport(configurer: AsyncSupportConfigurer) {
        configurer.setDefaultTimeout(ASYNC_REQUEST_TIMEOUT_MS)
    }

    @Bean
    fun kotlinxJson(): Json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/**")
            .allowedOriginPatterns("*")
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
            .allowedHeaders("Content-Type", "Authorization")
            .allowCredentials(true)
            .maxAge(3600)
    }

    override fun extendMessageConverters(converters: MutableList<HttpMessageConverter<*>>) {
        converters.add(0, KotlinxJsonHttpMessageConverter(kotlinxJson()))
    }

    companion object {
        /** 10 minutes — generous headroom for slow free-TTS synthesis + zip writing. */
        private const val ASYNC_REQUEST_TIMEOUT_MS = 10L * 60 * 1000
    }
}


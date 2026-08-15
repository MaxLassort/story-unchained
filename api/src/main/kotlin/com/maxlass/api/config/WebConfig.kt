package com.maxlass.api.config

import com.maxlass.studio.infrastructure.config.KotlinxJsonHttpMessageConverter
import kotlinx.serialization.json.Json
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.converter.HttpMessageConverter
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * CORS global : remplace l'intercept Ktor (echo de l'Origin + gestion OPTIONS)
 * pour parité avec le frontend Angular library-web.
 */
@Configuration
class WebConfig : WebMvcConfigurer {

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
}

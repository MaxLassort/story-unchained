package com.maxlass.api.config

import com.maxlass.studio.infrastructure.config.StudioProperties
import com.maxlass.studio.infrastructure.metadata.MetadataStore
import com.maxlass.studio.pack.adapter.ExtractThumbnailFromFsPackAdapter
import com.maxlass.studio.pack.adapter.LoadUnofficialMetadataFromFileAdapter
import com.maxlass.studio.pack.adapter.MetaDataReaderAdapter
import com.maxlass.studio.pack.adapter.MetadataRefreshAdapter
import com.maxlass.studio.pack.adapter.StudioCorePackFormatConverterAdapter
import com.maxlass.studio.pack.adapter.UpdateUnofficialMetadataAdapter
import com.maxlass.studio.pack.adapter.UpdateZipMetadataAdapter
import com.maxlass.studio.pack.cache.ThumbnailCache
import com.maxlass.studio.pack.port.external.ExtractThumbnailFromFsPackPort
import com.maxlass.studio.pack.port.external.LoadUnofficialMetadataFromFilePort
import com.maxlass.studio.pack.port.external.MetaDataReaderPort
import com.maxlass.studio.pack.port.external.MetadataRefreshPort
import com.maxlass.studio.pack.port.external.PackFormatConverterPort
import com.maxlass.studio.pack.port.external.UpdatePackFileMetadataPort
import com.maxlass.studio.pack.port.external.UpdateUnofficialMetadataPort
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

@Configuration
@EnableConfigurationProperties(StudioProperties::class)
class AppConfig {

    @Bean
    fun thumbnailCache(): ThumbnailCache = ThumbnailCache()

    @Bean
    fun restClientBuilder(): RestClient.Builder = RestClient.builder()

    @Bean
    fun metadataStore(studioProperties: StudioProperties): MetadataStore =
        MetadataStore(
            officialJsonPath = studioProperties.officialJsonPath,
            unofficialJsonPath = studioProperties.defaultUnofficialJsonPath,
        )

    @Bean
    fun metaDataReaderPort(): MetaDataReaderPort = MetaDataReaderAdapter()

    @Bean
    fun metadataRefreshPort(
        restClientBuilder: RestClient.Builder,
        studioProperties: StudioProperties,
    ): MetadataRefreshPort = MetadataRefreshAdapter(restClientBuilder, studioProperties)

    @Bean
    fun extractThumbnailFromFsPackPort(): ExtractThumbnailFromFsPackPort = ExtractThumbnailFromFsPackAdapter()

    @Bean
    fun packFormatConverterPort(): PackFormatConverterPort = StudioCorePackFormatConverterAdapter()

    @Bean
    fun updatePackFileMetadataPort(): UpdatePackFileMetadataPort = UpdateZipMetadataAdapter()

    @Bean
    fun loadUnofficialMetadataFromFilePort(): LoadUnofficialMetadataFromFilePort = LoadUnofficialMetadataFromFileAdapter()

    @Bean
    fun updateUnofficialMetadataPort(metadataStore: MetadataStore): UpdateUnofficialMetadataPort =
        UpdateUnofficialMetadataAdapter(metadataStore)
}

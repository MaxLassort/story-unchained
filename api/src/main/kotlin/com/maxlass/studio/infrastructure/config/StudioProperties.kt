package com.maxlass.studio.infrastructure.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

/**
 * Centralized storage paths (remplace StorageConfig) for the service.
 * Overridable via `studio.*` properties in application.yml.
 */
@ConfigurationProperties(prefix = "studio")
data class StudioProperties(
    val storageDir: Path = Path.of(System.getProperty("user.home"), ".luniiUnchained"),
    val settingsFileName: String = "settings.json",
    val officialJsonFileName: String = "official.json",
) {
    val metadataDbDir: Path
        get() = storageDir.resolve("db")

    /** Temporary draft binaries (audio, images), cleaned at startup and per-draft. */
    val draftsDir: Path
        get() = storageDir.resolve("drafts")

    /** Absolute path of the official.json file (Lunii metadata). */
    val officialJsonPath: Path
        get() = metadataDbDir.resolve(officialJsonFileName)

    /** Default path for Studio's unofficial.json when not set in settings (~/.studio/db/unofficial.json). */
    val defaultUnofficialJsonPath: Path
        get() = Path.of(System.getProperty("user.home"), ".studio", "db", "unofficial.json")

    /** User settings file. */
    val settingsFile: Path
        get() = storageDir.resolve(settingsFileName)

    /** H2 database file (no extension), under .luniiUnchained/db/. */
    val dbFile: Path
        get() = metadataDbDir.resolve("studio_db")

    /** Application config file (current working directory by default). */
    val configFile: Path
        get() = Path.of("config.json")

    /** Default library path for packs (Documents/luniiUnchained/Packs). */
    val defaultLibraryPath: Path
        get() = Path.of(System.getProperty("user.home"), "Documents", "luniiUnchained", "Packs")
}

package com.maxlass.studio.infrastructure.metadata

/**
 * Constants historically provided by the `studio-metadata` Java library.
 * Values must stay identical to the library (system property names, Lunii URLs, ...).
 */
object MetadataDb {

    const val OFFICIAL_DB_PROP = "studio.db.official"

    const val OFFICIAL_DB_JSON_PATH = "/.studio/db/official.json"

    const val THUMBNAILS_STORAGE_ROOT = "https://storage.googleapis.com/lunii-data-prod"

    const val LUNII_GUEST_TOKEN_URL = "https://server-auth-prod.lunii.com/guest/create"

    const val LUNII_PACKS_DATABASE_URL = "https://server-data-prod.lunii.com/v2/packs"

    const val UNOFFICIAL_DB_PROP = "studio.db.unofficial"

    const val UNOFFICIAL_DB_JSON_PATH = "/.studio/db/unofficial.json"
}

/**
 * Metadata record as stored in official.json / unofficial.json.
 * Replaces `studio.metadata.DatabasePackMetadata`.
 */
data class DatabasePackMetadata(
    val uuid: String,
    val title: String?,
    val description: String?,
    val thumbnail: String?,
    val official: Boolean,
)

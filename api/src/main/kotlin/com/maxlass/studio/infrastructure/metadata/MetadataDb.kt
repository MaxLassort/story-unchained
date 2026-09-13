package com.maxlass.studio.infrastructure.metadata

/**
 * Constants historically provided by the `studio-metadata` Java library.
 * Values must stay identical to the library (system property names, Lunii URLs, ...).
 */
object MetadataDb {

    const val OFFICIAL_DB_PROP = "studio.db.official"

    const val OFFICIAL_DB_JSON_PATH = "/.studio/db/official.json"

    const val THUMBNAILS_STORAGE_ROOT = "https://storage.googleapis.com/lunii-data-prod"

    /**
     * Public Shopify CDN mirror of the pack covers. The file name is identical to the GCS
     * one (basename of `image_url`), so a dead GCS object can be swapped for this URL.
     * The `?v=` cache-buster is optional and `?width=` is supported for lighter thumbnails.
     */
    const val SHOPIFY_IMAGES_ROOT = "https://cdn.shopify.com/s/files/1/0644/5426/2826/files/"

    const val LUNII_GUEST_TOKEN_URL = "https://server-auth-prod.lunii.com/guest/create"

    const val LUNII_PACKS_DATABASE_URL = "https://server-data-prod.lunii.com/v2/packs"

    /**
     * Resolves a raw image path into a full URL.
     * If [raw] is already an absolute URL (starts with `http`), it is returned as-is;
     * otherwise it is treated as a GCS basename and prepended with [THUMBNAILS_STORAGE_ROOT].
     */
    fun resolveImageUrl(raw: String): String =
        if (raw.startsWith("http")) raw else THUMBNAILS_STORAGE_ROOT + raw
}

/**
 * Metadata record as stored in official.json.
 * Replaces `studio.metadata.DatabasePackMetadata`.
 */
data class DatabasePackMetadata(
    val uuid: String,
    val title: String?,
    val description: String?,
    val thumbnail: String?,
    val official: Boolean,
)

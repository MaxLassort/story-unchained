package com.maxlass.studio.infrastructure.config

import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Startup initialization (parity with the old `DatabaseFactory.init()`):
 * widens legacy metadata text columns to TEXT (CLOB) and adds missing columns
 * that Hibernate `ddl-auto: update` may not apply on existing H2 files.
 */
@Component
class DatabaseInitializer(
    private val jdbcTemplate: JdbcTemplate,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    @PostConstruct
    fun init() {
        widenPackMetadataTextColumns()
        ensureUnchainedColumn()
    }

    /**
     * The description/thumbnail columns were historically created as VARCHAR(255)
     * by Hibernate, but official catalog descriptions can be much longer.
     * Idempotently widens existing databases to TEXT (CLOB).
     */
    private fun widenPackMetadataTextColumns() {
        val columns = packMetadataColumns()
        columns.forEach { row ->
            val name = row["COLUMN_NAME"] as? String ?: return@forEach
            val dataType = row["DATA_TYPE"] as? String
            val maxLength = (row["CHARACTER_MAXIMUM_LENGTH"] as? Number)?.toLong()
            val isBoundedText = (dataType?.contains("CHAR") == true) &&
                maxLength != null && maxLength < 1_000_000
            if (name in setOf("description", "thumbnail") && isBoundedText) {
                jdbcTemplate.execute("ALTER TABLE pack_metadata ALTER COLUMN $name SET DATA TYPE TEXT")
            }
        }
    }

    /**
     * Adds [pack_metadata.unchained] when missing (StoryUnchained provenance flag).
     * Safe to run repeatedly.
     */
    private fun ensureUnchainedColumn() {
        val columns = packMetadataColumns()
        if (columns.isEmpty()) return
        val hasUnchained = columns.any { (it["COLUMN_NAME"] as? String)?.equals("unchained", ignoreCase = true) == true }
        if (hasUnchained) return
        log.info("Adding missing pack_metadata.unchained column")
        jdbcTemplate.execute(
            "ALTER TABLE pack_metadata ADD COLUMN unchained BOOLEAN NOT NULL DEFAULT FALSE"
        )
    }

    private fun packMetadataColumns(): List<Map<String, Any?>> =
        runCatching {
            jdbcTemplate.queryForList(
                """
                SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH
                FROM INFORMATION_SCHEMA.COLUMNS
                WHERE UPPER(TABLE_NAME) = 'PACK_METADATA'
                """.trimIndent()
            )
        }.getOrDefault(emptyList())
}

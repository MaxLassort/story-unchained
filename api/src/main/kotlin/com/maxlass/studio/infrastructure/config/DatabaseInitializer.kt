package com.maxlass.studio.infrastructure.config

import jakarta.annotation.PostConstruct
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Startup initialization (parity with the old `DatabaseFactory.init()`):
 * widens legacy metadata text columns to TEXT (CLOB).
 */
@Component
class DatabaseInitializer(
    private val jdbcTemplate: JdbcTemplate,
) {

    @PostConstruct
    fun init() {
        widenPackMetadataTextColumns()
    }

    /**
     * The description/thumbnail columns were historically created as VARCHAR(255)
     * by Hibernate, but official catalog descriptions can be much longer.
     * Idempotently widens existing databases to TEXT (CLOB).
     */
    private fun widenPackMetadataTextColumns() {
        val columns = jdbcTemplate.queryForList(
            """
            SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE UPPER(TABLE_NAME) = 'PACK_METADATA'
            """.trimIndent()
        )
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
}

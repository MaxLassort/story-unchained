package com.maxlass.studio.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.Check

@Entity
@Table(name = "pack_metadata")
@Check(constraints = "official = FALSE OR (official = TRUE AND linkedOfficialPackId IS NULL)")
class PackMetadataEntity(
    @Id
    @Column(name = "packId", nullable = false, length = 255)
    var packId: String,

    @Column(name = "title", length = 255)
    var title: String? = null,

    @Column(name = "description", columnDefinition = "TEXT")
    var description: String? = null,

    @Column(name = "thumbnail", columnDefinition = "TEXT")
    var thumbnail: String? = null,

    @Column(name = "version", nullable = false)
    var version: Short,

    @Column(name = "factoryDisabled", nullable = false)
    var factoryDisabled: Boolean,

    @Column(name = "nightModeAvailable", nullable = false)
    var nightModeAvailable: Boolean,

    @Column(name = "official", nullable = false)
    var official: Boolean,

    /** If set, this pack is a fork of the official pack with this UUID. Must be null when official is true. */
    @Column(name = "linkedOfficialPackId", length = 255)
    var linkedOfficialPackId: String? = null,

    @Column(name = "locale", length = 10)
    var locale: String? = null,

    @Column(name = "ageMin")
    var ageMin: Int? = null,

    @Column(name = "ageMax")
    var ageMax: Int? = null,

    @Column(name = "durationMs")
    var durationMs: Int? = null,

    @Column(name = "storyCount")
    var storyCount: Int? = null,
)

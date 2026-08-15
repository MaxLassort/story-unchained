package com.maxlass.studio.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable

@Entity
@Table(name = "pack_variants")
class PackVariantEntity(
    @EmbeddedId
    var id: PackVariantId = PackVariantId(),

    @Column(name = "storagePath", nullable = false, length = 1024)
    var storagePath: String = "",
)

@Embeddable
class PackVariantId(
    @Column(name = "packId", nullable = false, length = 255)
    var packId: String = "",

    @Column(name = "format", nullable = false, length = 50)
    var format: String = "",
) : Serializable

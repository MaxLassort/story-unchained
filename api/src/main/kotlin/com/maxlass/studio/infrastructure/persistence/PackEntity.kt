package com.maxlass.studio.infrastructure.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

@Entity
@Table(name = "packs")
class PackEntity(
    @Id
    @Column(name = "id", nullable = false, length = 255)
    var id: String,
)

package com.maxlass.studio.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface PackJpaRepository : JpaRepository<PackEntity, String>

package com.maxlass.studio.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface DevicePackJpaRepository : JpaRepository<DevicePackEntity, DevicePackId>

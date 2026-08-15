package com.maxlass.studio.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository

interface InvalidPackMoveQueueJpaRepository : JpaRepository<InvalidPackMoveQueueEntity, Long>

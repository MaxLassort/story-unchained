package com.maxlass.studio.pack.domain.dto

data class PackFilter(
    val search: String? = null,
    val official: Boolean? = null,
    val locale: String? = null,
    val inLibrary: Boolean? = null,
)

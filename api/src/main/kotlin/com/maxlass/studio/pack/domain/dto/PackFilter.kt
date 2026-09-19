package com.maxlass.studio.pack.domain.dto

data class PackFilter(
    val search: String? = null,
    val official: Boolean? = null,
    val unchained: Boolean? = null,
    val locale: String? = null,
    val inLibrary: Boolean? = null,
    val ageMin: Int? = null,
    val ageMax: Int? = null,
) {
    /** Null when there is no usable search (missing or blank-only tokens). */
    fun searchTokens(): List<String>? =
        search?.split("\\s+".toRegex())
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }

    /** A pack is kept when its age range overlaps [ageMin, ageMax]; packs without age are kept by default. */
    fun matchesAge(packAgeMin: Int?, packAgeMax: Int?): Boolean {
        if (ageMin == null && ageMax == null) return true
        val min = packAgeMin ?: return true
        val max = packAgeMax ?: return true
        return (ageMin == null || max >= ageMin) &&
            (ageMax == null || min <= ageMax)
    }
}

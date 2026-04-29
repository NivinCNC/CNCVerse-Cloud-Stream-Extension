package com.horis.cncverse.entities

data class Season(
    val ep: String? = null,
    val id: String? = null,
    val s: String? = null,
    val sele: String? = null,
    // The API returns `"selected": true` for the currently active season
    val selected: Boolean? = null
)

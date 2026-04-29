package com.horis.cncverse.entities

data class Episode(
    val complate: String? = null,
    val ep_desc: String? = null,
    // top-level fields (some APIs)
    val ep: String? = null,
    val id: String? = null,
    val s: String? = null,
    val t: String? = null,
    val time: String? = null,
    // info array: ["E1", "S5", "71m"] — used by the NewTV / Netflix-mirror API
    val info: List<String>? = null
) {
    /** Episode number: prefer top-level `ep`, fall back to info[0] */
    val epNum: String? get() = ep ?: info?.getOrNull(0)
    /** Season number: prefer top-level `s`, fall back to info[1] */
    val sNum: String?  get() = s  ?: info?.getOrNull(1)
    /** Runtime: prefer top-level `time`, fall back to info[2] */
    val timeVal: String? get() = time ?: info?.getOrNull(2)
}

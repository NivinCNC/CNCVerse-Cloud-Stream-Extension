// use an integer for version numbers
version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    description = "MovieLinkBD - Largest Movie Download Site in Bangladesh"
    authors = listOf("NivinCNC")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1

    // List of video source types.
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AnimeMovie",
        "AsianDrama"
    )
    language = "bn"

    iconUrl = "https://movielinkbd.one/img/favicon-192x192.png"
}

// use an integer for version numbers
version = 16

android {
    buildFeatures {
        buildConfig = true
    }
}

android {
    namespace = "com.cncverse"
}

cloudstream {
    description = "Movie and TV Series provider"
    authors = listOf("Redowan, NivinCNC")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "Anime",
        "AnimeMovie",
        "AsianDrama"
    )
    language = "ta"

    iconUrl = "https://github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/raw/refs/heads/master/Rtally/icon.png"
}

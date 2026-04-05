version = 5

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "ta"
    // All of these properties are optional, you can safely remove them

    description = "Xon Provider for Anime and Cartoons - Tamil, Hindi, Telugu, English, Japanese"
    authors = listOf("NivinCNC")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "Anime"
    )

    iconUrl = "https://play-lh.googleusercontent.com/zVq_wAus2snoO-ggyI6IomlsCzfybAozQOGYpBrv71r1--rOYOYWsr_N7DWwVp9t7ro=s248-rw"
}

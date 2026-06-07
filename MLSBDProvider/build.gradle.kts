// use an integer for version numbers
version = 11

android {
    buildFeatures {
        buildConfig = true
    }
} 

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "The largest movie link store in Bangladesh"
    authors = listOf("NivinCNC")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 1 // will be 3 if unspecified

    // List of video source types. Users are able to filter for extensions in a given category.
    // https://recloudstream.github.io/cloudstream/html/app/com.lagradost.cloudstream3/-tv-type/index.html
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AnimeMovie",
        "AsianDrama"
    )
    language = "bn"

    iconUrl = "https://mlsbd.co/wp-content/uploads/2020/08/MLSBD-Logo.png"
}

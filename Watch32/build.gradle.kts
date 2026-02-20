// use an integer for version numbers
version = 3

android {
    buildFeatures {
        buildConfig = true
    }
}


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Watch32 is a Free Movies streaming site with over 10000 movies and TV-Series."
    language = "en"
    authors = listOf("NivinCNC")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1

    tvTypes = listOf("Movie","TvSeries")


    // random cc logo i found
    iconUrl = "https://editorialge.com/wp-content/uploads/2023/07/Watch32..jpg"
}

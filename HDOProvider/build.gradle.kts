// use an integer for version numbers
version = 5

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "ta"
    // All of these properties are optional, you can safely remove them
    description = "HDO provider"
    authors = listOf("NivinCNC")
    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 0 // will be 3 if unspecified
    tvTypes = listOf(
        "Movies",
        "TvSeries",
    )

    iconUrl = "https://images.dwncdn.net/images/t_app-icon-l/p/b0c9663c-0d61-4941-b12f-91c58c06189f/1137087733/hdo-box-logo"

    isCrossPlatform = false

}

dependencies {
    // FIXME remove this when crossplatform is fully supported
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}
// use an integer for version numbers
version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Desi Serials Tv Shows Provider"
    language = "hi"
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
    )
    iconUrl = "https://www.google.com/s2/favicons?domain=desi-serials.to&sz=%size%"
}

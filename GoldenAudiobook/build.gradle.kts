version = 2

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

    description = "Audiobooks - Use Picture in Picture or VLC as external player"
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
        "Others"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=goldenaudiobook.com/&sz=%size%"

    isCrossPlatform = true
}

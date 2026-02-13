version = 1

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "en"
    // All of these properties are optional, you can safely remove them

    description = "Audiobooks in public domain"
    authors = listOf("KillerDogeEmpire, NivinCNC")

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

    iconUrl = "https://librivox.org/wp-content/themes/librivox/images/librivox-logo.png"

    isCrossPlatform = true
}

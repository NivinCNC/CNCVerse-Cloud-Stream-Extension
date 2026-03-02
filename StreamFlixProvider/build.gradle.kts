// use an integer for version numbers
version = 4

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "ta"
    // All of these properties are optional, you can safely remove them

    description = "StreamFlix Multi Language Movies and Series Provider"
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
        "Movie",
        "TvSeries",
        "Anime"
    )

    iconUrl = "https://play-lh.googleusercontent.com/7geBQBtW_yN70BU81Oi2SoZvYsj5VoYrI4FmUNdqWQlchtExJb7XE_1BQysGzgJ6mBU"
}

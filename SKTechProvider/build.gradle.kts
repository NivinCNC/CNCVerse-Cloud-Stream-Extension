// use an integer for version numbers
version = 2

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
dependencies {
    implementation("androidx.core:core:1.16.0")
    implementation("com.google.android.material:material:1.12.0")
}

cloudstream {
    // All of these properties are optional, you can safely remove them
    language = "ta"
    description = "Watch Live sports and TV channels via SK Tech"
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
        "Live",
    )
    requiresResources = true

    iconUrl = "https://appteka.store/get/a20p17EuCKFD08AaFsfH3JSJ6OU30ccp18WrKaNDze4LqOsDArhKj13-f-SjAayiWJH6P6pv9AlzhcfzEXlmRokU-I4=/726bbb91e36da425f55efce72cccb3a82df1d265.png"

    isCrossPlatform = true
}

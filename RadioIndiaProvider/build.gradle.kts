// use an integer for version numbers
version = 3

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    language = "ta"

    description = "Radio India Provider"
    authors = listOf("NivinCNC")

    status = 1 
    tvTypes = listOf("Live")
    
    iconUrl = "https://cdn.mytuner.mobi/static/ctr/icons/App_192x192/in.png"
}

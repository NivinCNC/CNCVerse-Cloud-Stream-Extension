package com.cncverse

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class MovieLinkBDPlugin : Plugin() {
    override fun load(context: Context) {
        MovieLinkBDProvider.appContext = context
        registerMainAPI(MovieLinkBDProvider())
    }
}

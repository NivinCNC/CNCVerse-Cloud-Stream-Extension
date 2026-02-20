package com.cncverse

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class CineTvPlugin: Plugin() {
    override fun load(context: Context) {
        // Register CineTv provider
        CineTvProvider.context = context
        registerMainAPI(CineTvProvider())
    }
}

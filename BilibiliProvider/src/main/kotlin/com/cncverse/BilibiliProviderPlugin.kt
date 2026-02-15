package com.cncverse

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class BilibiliProviderPlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner
        BilibiliProvider.context = context
        registerMainAPI(BilibiliProvider())
    }
}

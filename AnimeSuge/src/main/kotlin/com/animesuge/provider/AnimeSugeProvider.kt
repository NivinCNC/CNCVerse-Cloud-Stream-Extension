// AnimeSugePlugin.kt
package com.animesuge.provider

import android.content.Context
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeSugePlugin : Plugin() {
    override fun load(context: Context) {
        AnimeSuge.context = context
        registerMainAPI(AnimeSuge())
        registerExtractorAPI(MegaPlay())
        registerExtractorAPI(Vidwish())
        registerExtractorAPI(Vidtube())
    }
}

package com.RowdyAvocado

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class LibriVoxAudiobookPlugin : Plugin() {
    override fun load(context: Context) {
        LibriVoxAudiobook.context = context
        // All providers should be added in this manner. Please don't edit the providers list
        // directly.
        registerMainAPI(LibriVoxAudiobook())
    }
}

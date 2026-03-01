package com.cncverse.radioindia

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class RadioIndiaProviderPlugin: Plugin() {
    override fun load(context: Context) {
        RadioIndiaProvider.context = context
        registerMainAPI(RadioIndiaProvider())
    }
}

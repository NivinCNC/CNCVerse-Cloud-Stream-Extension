
package com.hexated

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HDrezkaProviderPlugin: Plugin() {
    override fun load(context: Context) {
        // Initialize context for the StarPopupHelper
        HDrezkaProvider.context = context
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(HDrezkaProvider())
    }
}
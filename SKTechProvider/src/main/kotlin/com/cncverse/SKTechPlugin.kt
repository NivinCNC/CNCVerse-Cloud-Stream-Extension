package com.cncverse

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import kotlinx.coroutines.runBlocking

@CloudstreamPlugin
class SKTechPlugin: Plugin() {
    private val sharedPref = activity?.getSharedPreferences("SKTech", Context.MODE_PRIVATE)

    // This will be populated dynamically from the API or fallback to static list
    private var iptvProviders: List<Map<String, Any>> = emptyList()

    override fun load(context: Context) {
        SKTech.context = context
        LiveEventsProvider.context = context
        
        // Always register Live Events provider first (unremovable)
        registerMainAPI(LiveEventsProvider())
        
        // Fetch IPTV providers from categories.txt
        iptvProviders = runBlocking {
          ProviderManager.fetchProviders()
        }

        val providerSettings = iptvProviders.mapNotNull { provider ->
            val title = provider["title"] as? String ?: return@mapNotNull null
            title to (sharedPref?.getBoolean(title, false) ?: false)
        }.toMap()

        val selectedProviders = iptvProviders.filter {
            val title = it["title"] as? String
            title != null && providerSettings[title] == true
        }

        selectedProviders.forEach { provider ->
            val title = provider["title"] as String
            val catLink = provider["catLink"] as String
            registerMainAPI(SKTech(title, catLink))
        }

        val activity = context as AppCompatActivity
        openSettings = {
            val frag = Settings(this, sharedPref, iptvProviders.mapNotNull { it["title"] as? String })
            frag.show(activity.supportFragmentManager, "SKTechSettings")
        }
    }
}

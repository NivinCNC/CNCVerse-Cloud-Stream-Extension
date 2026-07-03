package com.cncverse

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import kotlinx.coroutines.runBlocking

@CloudstreamPlugin
class SportzxPlugin : Plugin() {
    private val sharedPref = activity?.getSharedPreferences("SportzX", Context.MODE_PRIVATE)

    // IPTV providers fetched dynamically from the API categories
    private var iptvProviders: List<Map<String, Any>> = emptyList()

    override fun load(context: Context) {
        SportzxLiveEventsProvider.context = context
        SportzxProvider.context          = context

        // Always register the Live Events provider first (unremovable)
        registerMainAPI(SportzxLiveEventsProvider())

        // Fetch IPTV providers from the Sportzx categories API
        iptvProviders = runBlocking {
            SportzxProviderManager.fetchProviders()
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
            val title   = provider["title"] as String
            val catLink = provider["catLink"] as String
            registerMainAPI(SportzxProvider(title, catLink))
        }

        val activity = context as AppCompatActivity
        openSettings = {
            val frag = SportzxSettings(
                this,
                sharedPref,
                iptvProviders.mapNotNull { it["title"] as? String }
            )
            frag.show(activity.supportFragmentManager, "SportzxSettings")
        }
    }
}

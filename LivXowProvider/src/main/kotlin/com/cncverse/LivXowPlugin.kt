package com.cncverse

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import kotlinx.coroutines.runBlocking

@CloudstreamPlugin
class LivXowPlugin : Plugin() {

    private val sharedPref = activity?.getSharedPreferences("LivXow", Context.MODE_PRIVATE)

    override fun load(context: Context) {
        // Propagate context to both providers
        LivXowProvider.context           = context
        LivXowLiveEventsProvider.context = context

        // Always register Live Events provider (unremovable)
        registerMainAPI(LivXowLiveEventsProvider())

        // Fetch IPTV channel categories from the API
        val categoryProviders: List<Map<String, Any>> = runBlocking {
            LivXowProviderManager.fetchProviders()
        }

        // Register only categories the user has enabled (default: all off until configured)
        val selectedProviders = categoryProviders.filter { provider ->
            val title = provider["title"] as? String
            title != null && (sharedPref?.getBoolean(title, false) ?: false)
        }

        selectedProviders.forEach { provider ->
            val title   = provider["title"]   as? String ?: return@forEach
            val catLink = provider["catLink"] as? String ?: return@forEach
            registerMainAPI(LivXowProvider(title,catLink))
        }


        // Settings sheet — lets the user toggle categories
        val act = context as? AppCompatActivity
        if (act != null) {
            openSettings = {
                val categoryNames = categoryProviders.mapNotNull { it["title"] as? String }
                val frag = LivXowSettings(this, sharedPref, categoryNames)
                frag.show(act.supportFragmentManager, "LivXowSettings")
            }
        }
    }
}

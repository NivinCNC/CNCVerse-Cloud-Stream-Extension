package com.cncverse

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import kotlinx.coroutines.runBlocking

/**
 * CloudStream plugin entry-point for PlayZTV.
 *
 * On load it:
 *  1. Always registers [PlayZTVLiveEventsProvider] (live sports, non-removable).
 *  2. Fetches the provider/category list from the PlayZTV API.
 *  3. Registers whichever providers the user enabled in settings.
 */
@CloudstreamPlugin
class PlayZTVPlugin : Plugin() {

    private val sharedPref = activity?.getSharedPreferences("PlayZTV", Context.MODE_PRIVATE)

    private var iptvProviders: List<Map<String, Any>> = emptyList()

    override fun load(context: Context) {
        PlayZTV.context = context
        PlayZTVLiveEventsProvider.context = context

        // Always available — Live Events (not user-configurable)
        registerMainAPI(PlayZTVLiveEventsProvider())

        // Fetch provider list from API
        iptvProviders = runBlocking { PlayZTVProviderManager.fetchProviders() }

        // Determine which are enabled in settings
        val providerSettings = iptvProviders.mapNotNull { p ->
            val title = p["title"] as? String ?: return@mapNotNull null
            title to (sharedPref?.getBoolean(title, false) ?: false)
        }.toMap()

        iptvProviders
            .filter { p ->
                val title = p["title"] as? String
                title != null && providerSettings[title] == true
            }
            .forEach { p ->
                val title = p["title"] as String
                val catLink = p["catLink"] as String
                registerMainAPI(PlayZTV(title, catLink))
            }

        // Hook up the settings screen
        val act = context as AppCompatActivity
        openSettings = {
            PlayZTVSettings(
                this,
                sharedPref,
                iptvProviders.mapNotNull { it["title"] as? String }
            ).show(act.supportFragmentManager, "PlayZTVSettings")
        }
    }
}

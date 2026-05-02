package com.horis.cncverse

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
open class CNCVersePlugin: Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        NetflixMirrorStorage.init(context.applicationContext)
        DisneyStudioProvider.context = context
        NetflixMirrorProvider.context = context
        PrimeVideoMirrorProvider.context = context
        HotStarMirrorProvider.context = context
        registerMainAPI(NetflixMirrorProvider())
        registerMainAPI(PrimeVideoMirrorProvider())
        registerMainAPI(HotStarMirrorProvider())
        val sharedPref = context.getSharedPreferences("CNCVerseStudios", Context.MODE_PRIVATE)
        val studioOptions = listOf(
            StudioOption("studio_disney", "Disney", "disney"),
            StudioOption("studio_marvel", "Marvel", "marvel"),
            StudioOption("studio_starwars", "Star Wars", "starwars"),
            StudioOption("studio_pixar", "Pixar", "pixar")
        )

        fun isStudioEnabled(option: StudioOption): Boolean {
            return if (sharedPref.contains(option.key)) {
                sharedPref.getBoolean(option.key, false)
            } else {
                true
            }
        }

        studioOptions.filter { isStudioEnabled(it) }.forEach { option ->
            registerMainAPI(DisneyStudioProvider(option.cookieValue, option.label))
        }

        val activity = context as AppCompatActivity
        openSettings = {
            val frag = CNCVerseSettings(this, sharedPref, studioOptions)
            frag.show(activity.supportFragmentManager, "CNCVerseSettings")
        }
    }

}

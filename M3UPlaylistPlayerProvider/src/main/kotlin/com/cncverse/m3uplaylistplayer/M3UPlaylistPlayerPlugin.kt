package com.cncverse.M3UPlaylistPlayer

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

@CloudstreamPlugin
class M3UPlaylistPlayerPlugin: Plugin() {
    private val sharedPref = activity?.getSharedPreferences("M3UPlaylistPlayerPrefs", Context.MODE_PRIVATE)

    override fun load(context: Context) {
        M3UPlaylistPlayer.context = context

        // Fetch user registered M3U8 links from SharedPreferences
        val playlistsJson = sharedPref?.getString("playlists", "[]") ?: "[]"
        val playlists: List<PlaylistEntry> = try {
            parseJson<List<PlaylistEntry>>(playlistsJson)
        } catch (e: Exception) {
            emptyList()
        }

        // Register a provider for each playlist
        playlists.forEach { playlist ->
            if (playlist.name.isNotBlank() && playlist.url.isNotBlank()) {
                registerMainAPI(M3UPlaylistPlayer(playlist.name, playlist.url))
            }
        }

        val activity = context as? AppCompatActivity
        openSettings = {
            if (activity != null) {
                val frag = Settings(this, sharedPref, playlists)
                frag.show(activity.supportFragmentManager, "M3UPlaylistPlayerSettings")
            }
        }
    }
}

data class PlaylistEntry(
    val name: String,
    val url: String
)

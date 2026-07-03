package com.cncverse.M3UPlaylistPlayer

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.CommonActivity.showToast

class Settings(
    private val plugin: M3UPlaylistPlayerPlugin,
    private val sharedPref: SharedPreferences?,
    private val initialPlaylists: List<PlaylistEntry>
) : BottomSheetDialogFragment() {

    private val currentPlaylists = initialPlaylists.toMutableList()
    private lateinit var playlistsContainer: LinearLayout

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val context = requireContext()
        val dp = context.resources.displayMetrics.density

        // Root layout
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt(), (16 * dp).toInt())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // Header
        val headerText = TextView(context).apply {
            text = "M3U PlayList Player Settings"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16 * dp).toInt() }
        }
        rootLayout.addView(headerText)

        // Add new playlist section
        val nameInput = EditText(context).apply {
            hint = "Playlist Name"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() }
        }
        rootLayout.addView(nameInput)

        val urlInput = EditText(context).apply {
            hint = "M3U8 URL"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() }
        }
        rootLayout.addView(urlInput)

        val addButton = Button(context).apply {
            text = "Add Playlist"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (16 * dp).toInt() }
        }
        rootLayout.addView(addButton)

        // Title for list
        val listTitle = TextView(context).apply {
            text = "Registered Playlists"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (8 * dp).toInt() }
        }
        rootLayout.addView(listTitle)

        // Container for the list of playlists
        val scrollView = ScrollView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        playlistsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView.addView(playlistsContainer)
        rootLayout.addView(scrollView)

        // Save button
        val saveButton = Button(context).apply {
            text = "Save & Restart App"
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (16 * dp).toInt() }
        }
        rootLayout.addView(saveButton)

        // Setup logic
        refreshPlaylistsList()

        addButton.setOnClickListener {
            val name = nameInput.text.toString().trim()
            val url = urlInput.text.toString().trim()
            if (name.isNotEmpty() && url.isNotEmpty()) {
                currentPlaylists.add(PlaylistEntry(name, url))
                nameInput.text.clear()
                urlInput.text.clear()
                refreshPlaylistsList()
                showToast("Playlist added.")
            } else {
                showToast("Please enter both name and URL.")
            }
        }

        saveButton.setOnClickListener {
            saveAndRestart()
        }

        return rootLayout
    }

    @SuppressLint("SetTextI18n")
    private fun refreshPlaylistsList() {
        val context = requireContext()
        val dp = context.resources.displayMetrics.density
        playlistsContainer.removeAllViews()

        if (currentPlaylists.isEmpty()) {
            val emptyText = TextView(context).apply {
                text = "No playlists added yet."
                setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
            }
            playlistsContainer.addView(emptyText)
            return
        }

        currentPlaylists.forEachIndexed { index, playlist ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * dp).toInt() }
                setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
                setBackgroundColor(Color.parseColor("#1Affffff")) // Slightly visible background
            }

            val textLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            }

            val nameText = TextView(context).apply {
                text = playlist.name
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
            }
            val urlText = TextView(context).apply {
                text = playlist.url
                textSize = 12f
                maxLines = 1
            }

            textLayout.addView(nameText)
            textLayout.addView(urlText)

            val removeButton = Button(context).apply {
                text = "Remove"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    currentPlaylists.removeAt(index)
                    refreshPlaylistsList()
                }
            }

            row.addView(textLayout)
            row.addView(removeButton)

            playlistsContainer.addView(row)
        }
    }

    private fun saveAndRestart() {
        val json = currentPlaylists.toJson()
        sharedPref?.edit()?.putString("playlists", json)?.apply()

        AlertDialog.Builder(requireContext())
            .setTitle("Restart Required")
            .setMessage("Settings saved. The app must be restarted to apply changes.")
            .setPositiveButton("Restart Now") { _, _ ->
                dismiss()
                restartApp()
            }
            .setNegativeButton("Later") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun restartApp() {
        val context = requireContext().applicationContext
        val packageManager = context.packageManager
        val intent = packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component

        if (componentName != null) {
            val restartIntent = Intent.makeRestartActivityTask(componentName)
            context.startActivity(restartIntent)
            Runtime.getRuntime().exit(0)
        }
    }
}

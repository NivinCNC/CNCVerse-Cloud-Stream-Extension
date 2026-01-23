package com.cncverse

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.lagradost.cloudstream3.CommonActivity.showToast

class Settings(
    private val plugin: SKTechPlugin,
    private val sharedPref: SharedPreferences?,
    private val playlistNames: List<String>
) : BottomSheetDialogFragment() {

    private val enabledPlaylists = playlistNames.filter {
        sharedPref?.getBoolean(it, false) ?: false
    }.toMutableList()

    private fun View.makeTvCompatible() {
        this.setPadding(
            this.paddingLeft + 10,
            this.paddingTop + 10,
            this.paddingRight + 10,
            this.paddingBottom + 10
        )
        this.background = getDrawable("outline")
    }
    
    // Helper function to get a drawable resource by name
    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getDrawable(name: String): Drawable? {
        val id = plugin.resources?.getIdentifier(name, "drawable", "com.cncverse")
        return id?.let { ResourcesCompat.getDrawable(plugin.resources ?: return null, it, null) }
    }

    // Helper function to get a string resource by name
    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getString(name: String): String? {
        val id = plugin.resources?.getIdentifier(name, "string", "com.cncverse")
        return id?.let { plugin.resources?.getString(it) }
    }

    // Generic findView function to find views by name
    @SuppressLint("DiscouragedApi")
    private fun <T : View> View.findViewByName(name: String): T? {
        val id = plugin.resources?.getIdentifier(name, "id", "com.cncverse")
        return findViewById(id ?: return null)
    }

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val layoutId =
            plugin.resources?.getIdentifier("settings", "layout", "com.cncverse")
        return layoutId?.let {
            inflater.inflate(plugin.resources?.getLayout(it), container, false)
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize views
        val headerTw: TextView? = view.findViewByName("header_tw")
        headerTw?.text = getString("header_tw")
        val header2Tw: TextView? = view.findViewByName("header2_tw")
        header2Tw?.text = getString("header2_tw")

        val saveBtn: ImageButton? = view.findViewByName("save_btn")
        saveBtn?.makeTvCompatible()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))

        val scrollView: LinearLayout? = view.findViewByName("list")
        playlistNames.forEach {
            scrollView?.addView(getPlaylistRow(it))
        }

        saveBtn?.setOnClickListener {
            with(sharedPref?.edit()) {
                this?.clear()
                enabledPlaylists.forEach {
                    this?.putBoolean(it, true)
                }
                this?.apply()
            }
            
            AlertDialog.Builder(requireContext())
                .setTitle("Restart Required")
                .setMessage("Changes have been saved. Do you want to restart the app to apply them?")
                .setPositiveButton("Yes") { _, _ ->
                    dismiss()
                    restartApp()
                }
                .setNegativeButton("No") { dialog, _ ->
                    dialog.dismiss()
                    showToast("Settings saved. Restart app to apply changes.")
                }
                .show()
        }
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

    private fun getPlaylistRow(playlistName: String): RelativeLayout {
        // Create RelativeLayout
        val relativeLayout = RelativeLayout(this@Settings.requireContext()).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(0, 0, 0, 8)
        }

        // Create CheckBox
        val checkBox = CheckBox(this@Settings.requireContext()).apply {
            id = View.generateViewId()
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }

        // Create TextView
        val textView = TextView(this@Settings.requireContext()).apply {
            id = View.generateViewId()
            text = playlistName.substringAfter("playlist_")
            textSize = 16f
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.END_OF, checkBox.id)
                addRule(RelativeLayout.CENTER_VERTICAL)
                marginStart = 16 // Add some spacing between checkbox and text
            }
        }

        checkBox.isChecked = enabledPlaylists.contains(playlistName)

        checkBox.setOnCheckedChangeListener { _, b ->
            if (b) {
                enabledPlaylists.add(playlistName)
            } else{
                enabledPlaylists.remove(playlistName)
            }
        }

        textView.setOnClickListener {
            checkBox.isChecked = !checkBox.isChecked
        }

        // Add views to RelativeLayout
        relativeLayout.addView(checkBox)
        relativeLayout.addView(textView)

        // Set the RelativeLayout as the content view
        return relativeLayout
    }
}

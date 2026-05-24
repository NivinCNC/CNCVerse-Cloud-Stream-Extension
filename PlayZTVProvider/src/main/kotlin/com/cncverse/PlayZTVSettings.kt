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

/**
 * Settings bottom sheet for PlayZTV provider selection.
 *
 * Reuses the same layout resources (settings.xml, etc.) that are shared across
 * the CNCVerse plugin suite via the `com.cncverse` namespace.
 */
class PlayZTVSettings(
    private val plugin: PlayZTVPlugin,
    private val sharedPref: SharedPreferences?,
    private val playlistNames: List<String>
) : BottomSheetDialogFragment() {

    private val enabledPlaylists = playlistNames
        .filter { sharedPref?.getBoolean(it, false) ?: false }
        .toMutableList()

    // ── Resource helpers ──────────────────────────────────────────────────────

    @SuppressLint("DiscouragedApi")
    private fun getDrawable(name: String): Drawable? {
        val id = plugin.resources?.getIdentifier(name, "drawable", "com.cncverse")
        return id?.let { ResourcesCompat.getDrawable(plugin.resources ?: return null, it, null) }
    }

    @SuppressLint("DiscouragedApi")
    private fun getString(name: String): String? {
        val id = plugin.resources?.getIdentifier(name, "string", "com.cncverse")
        return id?.let { plugin.resources?.getString(it) }
    }

    @SuppressLint("DiscouragedApi")
    private fun <T : View> View.findViewByName(name: String): T? {
        val id = plugin.resources?.getIdentifier(name, "id", "com.cncverse")
        return findViewById(id ?: return null)
    }

    private fun View.makeTvCompatible() {
        setPadding(paddingLeft + 10, paddingTop + 10, paddingRight + 10, paddingBottom + 10)
        background = getDrawable("outline")
    }

    // ── Fragment lifecycle ────────────────────────────────────────────────────

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutId = plugin.resources?.getIdentifier("settings", "layout", "com.cncverse")
        return layoutId?.let { inflater.inflate(plugin.resources?.getLayout(it), container, false) }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewByName<TextView>("header_tw")?.text = getString("header_tw")
        view.findViewByName<TextView>("header2_tw")?.text = getString("header2_tw")

        val saveBtn = view.findViewByName<ImageButton>("save_btn")
        saveBtn?.makeTvCompatible()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))

        val list = view.findViewByName<LinearLayout>("list")
        playlistNames.forEach { list?.addView(buildRow(it)) }

        saveBtn?.setOnClickListener {
            sharedPref?.edit()?.apply {
                clear()
                enabledPlaylists.forEach { putBoolean(it, true) }
                apply()
            }
            AlertDialog.Builder(requireContext())
                .setTitle("Restart Required")
                .setMessage("Changes saved. Restart the app to apply them?")
                .setPositiveButton("Yes") { _, _ -> dismiss(); restartApp() }
                .setNegativeButton("No") { dlg, _ ->
                    dlg.dismiss()
                    showToast("Settings saved. Restart to apply changes.")
                }
                .show()
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun restartApp() {
        val ctx = requireContext().applicationContext
        val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        val component = intent?.component ?: return
        ctx.startActivity(Intent.makeRestartActivityTask(component))
        Runtime.getRuntime().exit(0)
    }

    private fun buildRow(name: String): RelativeLayout {
        val root = RelativeLayout(requireContext()).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(0, 0, 0, 8)
        }

        val checkBox = CheckBox(requireContext()).apply {
            id = View.generateViewId()
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            isChecked = enabledPlaylists.contains(name)
            setOnCheckedChangeListener { _, checked ->
                if (checked) enabledPlaylists.add(name) else enabledPlaylists.remove(name)
            }
        }

        val textView = TextView(requireContext()).apply {
            id = View.generateViewId()
            text = name.substringAfter("playlist_")
            textSize = 16f
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.END_OF, checkBox.id)
                addRule(RelativeLayout.CENTER_VERTICAL)
                marginStart = 16
            }
            setOnClickListener { checkBox.isChecked = !checkBox.isChecked }
        }

        root.addView(checkBox)
        root.addView(textView)
        return root
    }
}

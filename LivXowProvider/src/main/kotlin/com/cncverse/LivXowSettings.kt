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
 * LivXow Settings
 *
 * Bottom-sheet settings fragment for the LivXow plugin.
 * Mirrors the SKTech Settings.kt structure — uses the same shared-pref
 * approach and resource loading pattern (requiresResources = true in build.gradle.kts).
 *
 * Allows the user to toggle which channel category playlists are visible.
 */
class LivXowSettings(
    private val plugin: LivXowPlugin,
    private val sharedPref: SharedPreferences?,
    private val categoryNames: List<String>
) : BottomSheetDialogFragment() {

    private val enabledCategories = categoryNames.filter {
        sharedPref?.getBoolean(it, false) ?: false   // default: all categories disabled until user enables them

    }.toMutableList()

    // ── Resource helpers ──────────────────────────────────────────────────────

    private fun View.makeTvCompatible() {
        this.setPadding(
            this.paddingLeft  + 10,
            this.paddingTop   + 10,
            this.paddingRight + 10,
            this.paddingBottom + 10
        )
        this.background = getDrawable("outline")
    }

    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getDrawable(name: String): Drawable? {
        val id = plugin.resources?.getIdentifier(name, "drawable", "com.cncverse")
        return id?.let { ResourcesCompat.getDrawable(plugin.resources ?: return null, it, null) }
    }

    @SuppressLint("DiscouragedApi")
    @Suppress("SameParameterValue")
    private fun getString(name: String): String? {
        val id = plugin.resources?.getIdentifier(name, "string", "com.cncverse")
        return id?.let { plugin.resources?.getString(it) }
    }

    @SuppressLint("DiscouragedApi")
    private fun <T : View> View.findViewByName(name: String): T? {
        val id = plugin.resources?.getIdentifier(name, "id", "com.cncverse")
        return findViewById(id ?: return null)
    }

    // ── Fragment lifecycle ────────────────────────────────────────────────────

    @SuppressLint("DiscouragedApi")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val layoutId = plugin.resources?.getIdentifier("settings", "layout", "com.cncverse")
        return layoutId?.let {
            inflater.inflate(plugin.resources?.getLayout(it), container, false)
        }
    }

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    @RequiresApi(Build.VERSION_CODES.M)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val headerTw: TextView? = view.findViewByName("header_tw")
        headerTw?.text = getString("header_tw") ?: "LivXow"

        val header2Tw: TextView? = view.findViewByName("header2_tw")
        header2Tw?.text = getString("header2_tw") ?: "Select Categories"

        val saveBtn: ImageButton? = view.findViewByName("save_btn")
        saveBtn?.makeTvCompatible()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))

        val scrollView: LinearLayout? = view.findViewByName("list")
        categoryNames.forEach { cat ->
            scrollView?.addView(getCategoryRow(cat))
        }

        saveBtn?.setOnClickListener {
            with(sharedPref?.edit()) {
                this?.clear()
                enabledCategories.forEach { this?.putBoolean(it, true) }
                this?.apply()
            }
            // Invalidate cache so next getMainPage re-fetches with updated prefs
            LivXowProviderManager.invalidateCache()

            AlertDialog.Builder(requireContext())
                .setTitle("Restart Required")
                .setMessage("Settings saved. Restart the app to apply changes?")
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun restartApp() {
        val context       = requireContext().applicationContext
        val intent        = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val componentName = intent?.component
        if (componentName != null) {
            context.startActivity(Intent.makeRestartActivityTask(componentName))
            Runtime.getRuntime().exit(0)
        }
    }

    private fun getCategoryRow(categoryName: String): RelativeLayout {
        val relativeLayout = RelativeLayout(requireContext()).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
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
        }

        val textView = TextView(requireContext()).apply {
            id = View.generateViewId()
            text     = categoryName
            textSize = 16f
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.END_OF, checkBox.id)
                addRule(RelativeLayout.CENTER_VERTICAL)
                marginStart = 16
            }
        }

        checkBox.isChecked = enabledCategories.contains(categoryName)
        checkBox.setOnCheckedChangeListener { _, checked ->
            if (checked) enabledCategories.add(categoryName)
            else         enabledCategories.remove(categoryName)
        }
        textView.setOnClickListener { checkBox.isChecked = !checkBox.isChecked }

        relativeLayout.addView(checkBox)
        relativeLayout.addView(textView)
        return relativeLayout
    }
}

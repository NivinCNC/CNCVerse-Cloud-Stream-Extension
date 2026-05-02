package com.horis.cncverse

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

// Studio toggle entry for CNCVerse
data class StudioOption(val key: String, val label: String, val cookieValue: String)

class CNCVerseSettings(
    private val plugin: CNCVersePlugin,
    private val sharedPref: SharedPreferences?,
    private val studios: List<StudioOption>
) : BottomSheetDialogFragment() {

    private val enabledStudios = studios.filter { isStudioEnabled(it) }
        .map { it.key }
        .toMutableSet()

    private fun isStudioEnabled(option: StudioOption): Boolean {
        val prefs = sharedPref ?: return true
        return if (!prefs.contains(option.key)) {
            true
        } else {
            prefs.getBoolean(option.key, false)
        }
    }

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
        val layoutId = plugin.resources?.getIdentifier("settings", "layout", "com.cncverse")
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

        val headerTw: TextView? = view.findViewByName("header_tw")
        headerTw?.text = getString("header_tw")
        val header2Tw: TextView? = view.findViewByName("header2_tw")
        header2Tw?.text = getString("header2_tw")

        val saveBtn: ImageButton? = view.findViewByName("save_btn")
        saveBtn?.makeTvCompatible()
        saveBtn?.setImageDrawable(getDrawable("save_icon"))

        val scrollView: LinearLayout? = view.findViewByName("list")
        studios.forEach { option ->
            scrollView?.addView(getStudioRow(option))
        }

        saveBtn?.setOnClickListener {
            with(sharedPref?.edit()) {
                this?.clear()
                studios.forEach { option ->
                    this?.putBoolean(option.key, enabledStudios.contains(option.key))
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

    private fun getStudioRow(option: StudioOption): RelativeLayout {
        val relativeLayout = RelativeLayout(this@CNCVerseSettings.requireContext()).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(0, 0, 0, 8)
        }

        val checkBox = CheckBox(this@CNCVerseSettings.requireContext()).apply {
            id = View.generateViewId()
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }

        val textView = TextView(this@CNCVerseSettings.requireContext()).apply {
            id = View.generateViewId()
            text = option.label
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

        checkBox.isChecked = enabledStudios.contains(option.key)

        checkBox.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                enabledStudios.add(option.key)
            } else {
                enabledStudios.remove(option.key)
            }
        }

        textView.setOnClickListener {
            checkBox.isChecked = !checkBox.isChecked
        }

        relativeLayout.addView(checkBox)
        relativeLayout.addView(textView)

        return relativeLayout
    }
}

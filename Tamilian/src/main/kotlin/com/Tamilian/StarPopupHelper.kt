package com.Tamilian

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.app.AlertDialog

object StarPopupHelper {
    private const val TAG = "StarPopupHelper"
    private const val PREFS_NAME = "CNCVerseGlobalPrefs"
    private const val KEY_SHOWN_STAR_POPUP = "shown_star_popup_global_pay"
    private const val GITHUB_REPO_URL = "https://github.com/NivinCNC/CNCVerse-Cloud-Stream-Extension"
    private const val SPONSOR_URL = "https://www.paywithchai.in/nivincnc"
    
    fun showStarPopupIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        if (prefs.getBoolean(KEY_SHOWN_STAR_POPUP, false)) {
            return
        }
        
        prefs.edit().putBoolean(KEY_SHOWN_STAR_POPUP, true).apply()
        
        Handler(Looper.getMainLooper()).post {
            try {
                val activity = context as? Activity ?: return@post
                showStyledDialog(activity)
            } catch (e: Exception) {
                Log.e(TAG, "Error showing star popup: ${e.message}")
            }
        }
    }
    
    private fun showStyledDialog(activity: Activity) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24, activity), dp(20, activity), dp(24, activity), dp(20, activity))
            setBackgroundColor(Color.parseColor("#1a1a2e"))
        }
        
        val titleView = TextView(activity).apply {
            text = "⭐ Support CNCVerse!"
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16, activity))
        }
        layout.addView(titleView)
        
        val messageView = TextView(activity).apply {
            text = "If you enjoy this extension, please consider starring my GitHub repository.\n\nYour support helps me to continue development and keep the repo maintained! \uD83D\uDE80"
            setTextColor(Color.parseColor("#b0b0b0"))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24, activity))
            setLineSpacing(dp(4, activity).toFloat(), 1f)
        }
        layout.addView(messageView)
        
        val buttonContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        
        val starButton = Button(activity).apply {
            text = "⭐ Star on GitHub"
            setTextColor(Color.WHITE)
            textSize = 14f
            isAllCaps = false
            background = createRoundedBackground(Color.parseColor("#6c5ce7"))
            setPadding(dp(20, activity), dp(12, activity), dp(20, activity), dp(12, activity))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(12, activity)
            }
        }
        buttonContainer.addView(starButton)
        
        val sponsorButton = Button(activity).apply {
            text = "❤️ Sponsor (UPI)"
            setTextColor(Color.WHITE)
            textSize = 14f
            isAllCaps = false
            background = createRoundedBackground(Color.parseColor("#6c5ce7"))
            setPadding(dp(20, activity), dp(12, activity), dp(20, activity), dp(12, activity))
        }
        buttonContainer.addView(sponsorButton)
        
        layout.addView(buttonContainer)
        
        val dialog = AlertDialog.Builder(activity)
            .setView(layout)
            .setCancelable(true)
            .create()
        
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        
        starButton.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_REPO_URL))
                activity.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error opening GitHub: ${e.message}")
            }
            dialog.dismiss()
        }
        
        sponsorButton.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(SPONSOR_URL))
                activity.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error opening Sponsor: ${e.message}")
            }
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    private fun dp(value: Int, context: Context): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
    
    private fun createRoundedBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = 24f
        }
    }
}

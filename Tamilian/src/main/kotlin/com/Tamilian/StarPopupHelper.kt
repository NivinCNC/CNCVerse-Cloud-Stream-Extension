package com.Tamilian

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

object StarPopupHelper {
    private const val TAG = "StarPopupHelper"
    private const val PREFS_NAME = "CNCVerseGlobalPrefs"

    // New key — ensures ALL users (including those who saw the old popup) see this poll once
    private const val KEY_SHOWN_POLL = "shown_monetization_poll_v1"

    // ── Replace this with your deployed Cloudflare Worker URL ──────────────
    private const val POLL_WORKER_URL = "https://cncverse-poll.cncverse.workers.dev/vote"

    // Extension tag sent with every vote
    private const val EXT_TAG = "Tamilian"

    fun showStarPopupIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        if (prefs.getBoolean(KEY_SHOWN_POLL, false)) return

        prefs.edit().putBoolean(KEY_SHOWN_POLL, true).apply()

        Handler(Looper.getMainLooper()).post {
            try {
                val activity = context as? Activity ?: return@post
                showPollDialog(activity)
            } catch (e: Exception) {
                Log.e(TAG, "Error showing poll popup: ${e.message}")
            }
        }
    }

    private fun showPollDialog(activity: Activity) {
        // ── Root container ────────────────────────────────────────────────────
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20, activity), dp(24, activity), dp(20, activity), dp(20, activity))
            background = createRoundedBackground(Color.parseColor("#0f0f1a"), 20f)
        }

        // ── Title ─────────────────────────────────────────────────────────────
        root.addView(TextView(activity).apply {
            text = "📊 Help Shape CNCVerse's Future!"
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(6, activity))
        })

        // ── Subtitle ──────────────────────────────────────────────────────────
        root.addView(TextView(activity).apply {
            text = "I'm considering changing the model for these extensions.\nWhich do you prefer?"
            setTextColor(Color.parseColor("#9b9bb5"))
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(20, activity))
            setLineSpacing(dp(3, activity).toFloat(), 1f)
        })

        // ── Option 1 — Subscription ───────────────────────────────────────────
        val subCard = buildOptionCard(
            activity = activity,
            emoji = "💎",
            title = "Subscription Model",
            accentColor = Color.parseColor("#7c6fe0"),
            lines = listOf(
                "₹20 / month  →  Access ALL extensions + lifetime maintenance",
                "₹200 / month  →  Above + guaranteed priority extension requests"
            )
        )
        root.addView(subCard)
        root.addView(spacer(activity, 10))

        // ── Option 2 — Ads ────────────────────────────────────────────────────
        val adsCard = buildOptionCard(
            activity = activity,
            emoji = "📺",
            title = "Ad-Supported (Free)",
            accentColor = Color.parseColor("#c49b2e"),
            lines = listOf(
                "One ad per video — completely free to use",
                "⚠️ May affect viewing experience"
            )
        )
        root.addView(adsCard)
        root.addView(spacer(activity, 10))

        // ── Option 3 — Keep Free ──────────────────────────────────────────────
        val freeCard = buildOptionCard(
            activity = activity,
            emoji = "🆓",
            title = "Stay Free (Current)",
            accentColor = Color.parseColor("#4a4a6a"),
            lines = listOf(
                "No cost, no ads — exactly as it is now",
                "⚠️ No maintenance. Will be discontinued after I join a job."
            )
        )
        root.addView(freeCard)
        root.addView(spacer(activity, 20))



        // ── Build dialog ──────────────────────────────────────────────────────
        val dialog = AlertDialog.Builder(activity)
            .setView(root)
            .setCancelable(false)
            .create()

        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        // ── Click handlers ────────────────────────────────────────────────────
        subCard.setOnClickListener {
            submitVote("subscription")
            dialog.dismiss()
        }
        adsCard.setOnClickListener {
            submitVote("ads")
            dialog.dismiss()
        }
        freeCard.setOnClickListener {
            submitVote("free")
            dialog.dismiss()
        }


        dialog.show()
    }

    // ── Build a tappable option card ─────────────────────────────────────────
    private fun buildOptionCard(
        activity: Activity,
        emoji: String,
        title: String,
        accentColor: Int,
        lines: List<String>
    ): LinearLayout {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = createBorderedBackground(
                fill = adjustAlpha(accentColor, 0.15f),
                stroke = accentColor,
                radius = 14f
            )
            setPadding(dp(14, activity), dp(12, activity), dp(14, activity), dp(12, activity))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
            foreground = activity.obtainStyledAttributes(
                intArrayOf(android.R.attr.selectableItemBackground)
            ).getDrawable(0)
        }

        // Title row
        val titleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6, activity))
        }
        titleRow.addView(TextView(activity).apply {
            text = emoji
            textSize = 18f
            setPadding(0, 0, dp(8, activity), 0)
        })
        titleRow.addView(TextView(activity).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        card.addView(titleRow)

        // Detail lines
        lines.forEach { line ->
            card.addView(TextView(activity).apply {
                text = line
                setTextColor(Color.parseColor("#c0c0d8"))
                textSize = 12.5f
                setLineSpacing(dp(2, activity).toFloat(), 1f)
                setPadding(dp(26, activity), dp(1, activity), 0, dp(1, activity))
            })
        }

        return card
    }

    // ── Fire-and-forget vote POST to Cloudflare Worker ───────────────────────
    private fun submitVote(option: String) {
        Thread {
            try {
                val conn = URL(POLL_WORKER_URL).openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    doOutput = true
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                val payload = """{"option":"$option","ext":"$EXT_TAG"}"""
                conn.outputStream.use { os: OutputStream -> os.write(payload.toByteArray()) }
                val code = conn.responseCode
                Log.d(TAG, "Poll vote '$option' submitted — HTTP $code")
                conn.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Poll vote submission failed (non-critical): ${e.message}")
            }
        }.start()
    }

    // ── Utility helpers ───────────────────────────────────────────────────────
    private fun dp(value: Int, context: Context): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    private fun spacer(context: Context, heightDp: Int): View =
        View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(heightDp, context)
            )
        }

    private fun createRoundedBackground(color: Int, radiusDp: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusDp * 4
        }

    private fun createBorderedBackground(fill: Int, stroke: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            setStroke(3, stroke)
            cornerRadius = radius * 4
        }

    private fun adjustAlpha(color: Int, factor: Float): Int {
        val alpha = Math.round(Color.alpha(color) * factor)
        return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}


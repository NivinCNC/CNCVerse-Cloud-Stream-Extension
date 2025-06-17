package com.horis.cloudstreamplugins

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager

object NetflixMirrorStorage {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private var mainUrl: String = "https://netfree2.cc"

    fun init(context: Context) {
        this.context = context.applicationContext
        this.prefs = context.getSharedPreferences("NetflixMirrorPrefs", Context.MODE_PRIVATE)
    }

    fun saveCookie(cookie: String) {
        val editor = prefs.edit()
        editor.putString("nf_cookie", cookie)
        editor.putLong("nf_cookie_timestamp", System.currentTimeMillis())
        editor.apply()
    }

    fun getCookie(): Pair<String?, Long> {
        return Pair(
            prefs.getString("nf_cookie", null),
            prefs.getLong("nf_cookie_timestamp", 0L)
        )
    }

    fun clearCookie() {
        val editor = prefs.edit()
        editor.remove("nf_cookie")
        editor.remove("nf_cookie_timestamp")
        editor.apply()
    }
}

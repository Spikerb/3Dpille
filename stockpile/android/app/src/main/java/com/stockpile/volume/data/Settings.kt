package com.stockpile.volume.data

import android.content.Context
import com.stockpile.volume.chain.Xrpl
import com.stockpile.volume.net.ApiClient

class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun str(key: String, def: String = "") = prefs.getString(key, def) ?: def
    private fun put(key: String, v: String) = prefs.edit().putString(key, v).apply()

    // ---- Blockchain (XRP Ledger via Xaman)
    /** Public API key of your app from https://apps.xumm.dev (the secret is NOT needed). */
    var xamanApiKey: String
        get() = str("xaman_api_key")
        set(v) = put("xaman_api_key", v.trim())

    var network: String
        get() = str("xrpl_network", Xrpl.MAINNET)
        set(v) = put("xrpl_network", v)

    var xamanJwt: String
        get() = str("xaman_jwt")
        set(v) = put("xaman_jwt", v)

    var xamanJwtExp: Long
        get() = prefs.getLong("xaman_jwt_exp", 0)
        set(v) = prefs.edit().putLong("xaman_jwt_exp", v).apply()

    var xamanAccount: String
        get() = str("xaman_account")
        set(v) = put("xaman_account", v)

    var pkceVerifier: String
        get() = str("pkce_verifier")
        set(v) = put("pkce_verifier", v)

    var pkceState: String
        get() = str("pkce_state")
        set(v) = put("pkce_state", v)

    /** Project to anchor automatically once sign-in completes. */
    var pendingAnchorProject: String
        get() = str("pending_anchor")
        set(v) = put("pending_anchor", v)

    val xamanSignedIn: Boolean
        get() = xamanJwt.isNotBlank() && xamanJwtExp > System.currentTimeMillis() / 1000 + 60

    fun signOutXaman() {
        xamanJwt = ""; xamanJwtExp = 0; xamanAccount = ""
    }

    // ---- Optional processing server (photo mode, for later)
    var serverUrl: String
        get() = str("server_url")
        set(v) = put("server_url", v.trim().trimEnd('/'))

    var apiKey: String
        get() = str("api_key")
        set(v) = put("api_key", v.trim())

    /** Seconds between automatic shots in photo auto-capture mode. */
    var autoIntervalSec: Float
        get() = prefs.getFloat("auto_interval", 2f)
        set(v) = prefs.edit().putFloat("auto_interval", v).apply()

    val isConfigured: Boolean get() = serverUrl.isNotBlank()

    fun client(): ApiClient? = if (isConfigured) ApiClient(serverUrl, apiKey) else null
}

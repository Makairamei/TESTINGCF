package com.juraganfilm

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import okhttp3.Interceptor
import okhttp3.Response
import java.io.File
import java.util.UUID

object LicenseClient {
    private const val TAG = "LicenseClient"
    private const val PREFS_NAME = "client_license_prefs"
    private const val KEY_LICENSE_KEY = "license_key"
    private const val KEY_DEVICE_ID = "device_id"

    private const val BASE_URL = "https://zoxxy.eu.org"

    private var appCtx: Context? = null
    private var pluginSessionToken: String? = null
    private var pluginSessionExpiry: Long = 0
    private var pluginSessionPlugin: String? = null

    fun init(context: Context, pluginName: String) {
        appCtx = context.applicationContext
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_DEVICE_ID)) {
            val rawId = getSystemDeviceId(context) ?: UUID.randomUUID().toString()
            val cleanId = rawId.replace(Regex("[^a-zA-Z0-9]"), "").lowercase()
            val finalId = if (cleanId.length > 32) cleanId.substring(0, 32) else cleanId.padEnd(32, '0')
            prefs.edit().putString(KEY_DEVICE_ID, finalId).apply()
        }
    }

    private fun getSystemDeviceId(context: Context): String? {
        return runCatching {
            android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
        }.getOrNull()
    }

    fun getDeviceId(): String {
        val prefs = appCtx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs?.getString(KEY_DEVICE_ID, "") ?: ""
    }

    fun getLicenseKey(): String {
        val prefs = appCtx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs?.getString(KEY_LICENSE_KEY, "") ?: ""
    }

    fun saveLicenseKey(key: String) {
        val prefs = appCtx?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs?.edit()?.putString(KEY_LICENSE_KEY, key.trim())?.apply()
    }

    suspend fun verifyLicense(key: String, pluginName: String): LicenseResponse {
        val devId = getDeviceId()
        val url = "$BASE_URL/api/client/license/verify"
        val payload = mapOf(
            "license_key" to key,
            "device_id" to devId,
            "plugin" to pluginName
        )
        return try {
            val res = app.post(
                url,
                json = payload,
                headers = mapOf("Content-Type" to "application/json")
            )
            AppUtils.parseJson(res.text)
        } catch (e: Exception) {
            Log.e(TAG, "Verify license network error: ${e.message}")
            LicenseResponse(status = "error", message = "Network error: ${e.message}")
        }
    }

    suspend fun checkLicense(pluginName: String, action: String, query: String? = null) {
        val key = getLicenseKey()
        if (key.isBlank()) {
            throw Exception("License key is empty. Please enter a valid license key.")
        }
        val devId = getDeviceId()
        val sessionToken = getPluginSessionToken(pluginName) ?: ""
        
        val url = "$BASE_URL/api/client/license/check"
        val payload = mutableMapOf(
            "license_key" to key,
            "device_id" to devId,
            "plugin" to pluginName,
            "action" to action,
            "session_token" to sessionToken
        )
        if (query != null) {
            payload["query"] = query
        }

        try {
            val res = app.post(
                url,
                json = payload,
                headers = mapOf("Content-Type" to "application/json")
            )
            val resp: LicenseResponse = AppUtils.parseJson(res.text)
            if (resp.status != "success") {
                throw Exception(resp.message ?: "License verification failed.")
            }
        } catch (e: Exception) {
            if (e is java.lang.IllegalStateException || e.message?.contains("License") == true) {
                throw e
            }
            Log.e(TAG, "checkLicense network warning: ${e.message}")
        }
    }

    suspend fun requireLicense(pluginName: String, action: String) {
        checkLicense(pluginName, action)
    }

    suspend fun trackActivity(pluginName: String, action: String, query: String? = null) {
        runCatching {
            checkLicense(pluginName, action, query)
        }
    }

    data class LicenseResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("message") val message: String? = null,
        @JsonProperty("reason") val reason: String? = null
    )

    data class SelectorConfig(
        val playerSelector: String = ".mobius option",
        val playerAttr: String = "value",
        val useBase64: Boolean = true
    )

    data class SelectorResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("selectors") val selectors: RawSelector? = null,
        @JsonProperty("message") val message: String? = null
    )

    data class RawSelector(
        @JsonProperty("server_selector") val serverSelector: String? = null,
        @JsonProperty("value_attr") val valueAttr: String? = null,
        @JsonProperty("encoding") val encoding: String? = null
    )

    private val selectorCache = mutableMapOf<String, Pair<SelectorConfig, Long>>()
    private val CACHE_TTL = 0L

    private suspend fun getPluginSessionToken(pluginName: String): String? {
        val now = System.currentTimeMillis()
        if (pluginSessionPlugin == pluginName && !pluginSessionToken.isNullOrEmpty() && now < pluginSessionExpiry - 15_000L) {
            return pluginSessionToken
        }
        var key = getLicenseKey()
        if (key.isBlank()) return null
        val devId = getDeviceId()
        val url = "$BASE_URL/api/client/license/session"
        val payload = mapOf(
            "license_key" to key,
            "device_id" to devId,
            "plugin" to pluginName
        )
        return try {
            val res = app.post(
                url,
                json = payload,
                headers = mapOf("Content-Type" to "application/json")
            )
            val resp: SessionResponse = AppUtils.parseJson(res.text)
            if (resp.status == "success" && !resp.sessionToken.isNullOrEmpty()) {
                pluginSessionToken = resp.sessionToken
                pluginSessionPlugin = pluginName
                pluginSessionExpiry = now + ((resp.expiresIn ?: 3600).toLong() * 1000L)
                pluginSessionToken
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getPluginSessionToken error: ${e.message}")
            null
        }
    }

    data class SessionResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("session_token") val sessionToken: String? = null,
        @JsonProperty("expires_in") val expiresIn: Int? = null,
        @JsonProperty("message") val message: String? = null
    )

    suspend fun getSelectors(pluginName: String): SelectorConfig? {
        val now = System.currentTimeMillis()
        val cached = selectorCache[pluginName]
        if (cached != null && now < cached.second) {
            return cached.first
        }

        val url = "$BASE_URL/api/client/license/selectors?plugin_name=${URLEncoder.encode(pluginName, "UTF-8")}"
        return try {
            val res = app.get(url)
            val resp: SelectorResponse = AppUtils.parseJson(res.text)
            if (resp.status == "success" && resp.selectors != null) {
                val raw = resp.selectors
                val config = SelectorConfig(
                    playerSelector = raw.serverSelector?.takeIf { it.isNotBlank() } ?: ".mobius option",
                    playerAttr = raw.valueAttr?.takeIf { it.isNotBlank() } ?: "value",
                    useBase64 = raw.encoding?.contains("base64", ignoreCase = true) ?: true
                )
                selectorCache[pluginName] = Pair(config, now + CACHE_TTL)
                config
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "getSelectors network error: ${e.message}")
            null
        }
    }
}

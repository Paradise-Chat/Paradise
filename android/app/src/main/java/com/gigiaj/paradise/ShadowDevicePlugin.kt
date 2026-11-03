package com.gigiaj.paradise

import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@CapacitorPlugin(name = "ShadowDevice")
class ShadowDevicePlugin : Plugin() {

    private suspend fun buildMatrixClient(homeserverUrl: String, ssStr: String?, userId: String): Client {
        val safeUserId = userId.replace(Regex("[^a-zA-Z0-9]"), "_")
        val dataPath = context.filesDir.absolutePath + "/matrix-shadow/$safeUserId/data"
        val cachePath = context.filesDir.absolutePath + "/matrix-shadow/$safeUserId/cache"

        val ssBuilder = when (ssStr) {
            "NATIVE" -> SlidingSyncVersionBuilder.NATIVE
            "NONE" -> SlidingSyncVersionBuilder.NONE
            else -> SlidingSyncVersionBuilder.DISCOVER_NATIVE
        }

        return ClientBuilder()
            .homeserverUrl(homeserverUrl)
            .sessionPaths(dataPath, cachePath)
            .systemIsMemoryConstrained()
            .slidingSyncVersionBuilder(ssBuilder)
            .build()
    }

    private fun getShadowSession(sharedPrefs: SharedPreferences, userId: String): JSONObject? {
        val sessionsStr = sharedPrefs.getString("shadow_sessions", "{}")
        val sessions = JSONObject(sessionsStr)
        return if (sessions.has(userId)) sessions.getJSONObject(userId) else null
    }

    private suspend fun restoreShadowSession(client: Client, sessionData: JSONObject) {
        val accessToken = sessionData.optString("access_token", null)
        val userId = sessionData.optString("user_id", null)
        val deviceId = sessionData.optString("device_id", null)
        val homeserverUrl = sessionData.optString("homeserver_url", null)
        val slidingSyncStr = sessionData.optString("sliding_sync_version", "NONE")

        if (!accessToken.isNullOrEmpty() && !userId.isNullOrEmpty() && !deviceId.isNullOrEmpty() && !homeserverUrl.isNullOrEmpty()) {
            val ssVersion = when (slidingSyncStr) {
                "NATIVE" -> SlidingSyncVersion.NATIVE
                else -> SlidingSyncVersion.NONE
            }

            val session = Session(
                accessToken = accessToken,
                refreshToken = null,
                userId = userId,
                deviceId = deviceId,
                homeserverUrl = homeserverUrl,
                oidcData = null,
                slidingSyncVersion = ssVersion
            )
            client.restoreSession(session)
        } else {
            throw Exception("Missing session credentials in JSON object.")
        }
    }

    @PluginMethod
    fun createSleepyShadow(call: PluginCall) {
        val homeserver = call.getString("homeserverUrl") ?: return call.reject("Missing homeserverUrl")
        val username = call.getString("username") ?: return call.reject("Missing username")
        val password = call.getString("password") ?: return call.reject("Missing password")
        val userId = call.getString("userId") ?: return call.reject("Missing userId")

        val sharedPrefs = context.getSharedPreferences("ParadiseShadow", Context.MODE_PRIVATE)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = buildMatrixClient(homeserver, "DISCOVER", userId)
                client.login(username, password, "Paradise Background Sync", null)
                val session = client.session()
                if (session != null) {
                    val ssName = if (session.slidingSyncVersion == SlidingSyncVersion.NATIVE) "NATIVE" else "NONE"
                    val sessionData = JSONObject().apply {
                        put("access_token", session.accessToken)
                        put("user_id", session.userId)
                        put("device_id", session.deviceId)
                        put("homeserver_url", homeserver)
                        put("sliding_sync_version", ssName)
                    }

                    val sessionsStr = sharedPrefs.getString("shadow_sessions", "{}")
                    val sessions = JSONObject(sessionsStr)
                    sessions.put(session.userId, sessionData)

                    sharedPrefs.edit().putString("shadow_sessions", sessions.toString()).apply()
                }
                val ret = JSObject()
                ret.put("status", "sleepy_shadow_created")
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Failed to create Sleepy Shadow", e)
            }
        }
    }

    @PluginMethod
    fun activateShadow(call: PluginCall) {
        val fcmToken = call.getString("pushToken") ?: return call.reject("Missing pushToken")
        val pushUrl = call.getString("pushUrl") ?: return call.reject("Missing pushUrl")
        val userId = call.getString("userId") ?: return call.reject("Missing userId")
        val sharedPrefs = context.getSharedPreferences("ParadiseShadow", Context.MODE_PRIVATE)
        val sessionData = getShadowSession(sharedPrefs, userId) ?: return call.reject("No shadow session found for user: $userId")

        val homeserver = sessionData.getString("homeserver_url")
        val ssStr = sessionData.optString("sliding_sync_version", "NONE")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = buildMatrixClient(homeserver, ssStr, userId)
                restoreShadowSession(client, sessionData)
                val identifiers = PusherIdentifiers(
                    pushkey = fcmToken,
                    appId = "moi.paradise.android"
                )

                val httpData = HttpPusherData(
                    url = pushUrl,
                    format = PushFormat.EVENT_ID_ONLY,
                    defaultPayload = null
                )

                client.setPusher(
                    identifiers = identifiers,
                    kind = PusherKind.Http(httpData),
                    appDisplayName = "Paradise",
                    deviceDisplayName = "Android Device",
                    profileTag = "primary",
                    lang = "en"
                )

                sharedPrefs.edit().putString("active_push_user", userId).apply()

                val ret = JSObject()
                ret.put("status", "shadow_activated")
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Failed to activate Shadow Device", e)
            }
        }
    }

    @PluginMethod
    fun deactivateShadow(call: PluginCall) {
        val fcmToken = call.getString("pushToken") ?: return call.reject("Missing pushToken")
        val userId = call.getString("userId") ?: return call.reject("Missing userId")
        val sharedPrefs = context.getSharedPreferences("ParadiseShadow", Context.MODE_PRIVATE)
        val sessionData = getShadowSession(sharedPrefs, userId) ?: return call.reject("No shadow session found for user: $userId")

        val homeserver = sessionData.getString("homeserver_url")
        val ssStr = sessionData.optString("sliding_sync_version", "NONE")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = buildMatrixClient(homeserver, ssStr, userId)
                restoreShadowSession(client, sessionData)
                val identifiers = PusherIdentifiers(
                    pushkey = fcmToken,
                    appId = "moi.paradise.android"
                )
                client.deletePusher(identifiers)
                sharedPrefs.edit().remove("active_push_user").apply()

                val ret = JSObject()
                ret.put("status", "shadow_deactivated")
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Failed to deactivate Shadow Device", e)
            }
        }
    }

    @PluginMethod
    fun verifyShadow(call: PluginCall) {
        val userId = call.getString("userId") ?: return call.reject("Missing userId")
        val recoveryKey = call.getString("recoveryKey") ?: return call.reject("Missing recoveryKey")

        val sharedPrefs = context.getSharedPreferences("ParadiseShadow", Context.MODE_PRIVATE)
        val sessionData = getShadowSession(sharedPrefs, userId) ?: return call.reject("No shadow session found for user: $userId")

        val homeserver = sessionData.getString("homeserver_url")
        val ssStr = sessionData.optString("sliding_sync_version", "NONE")

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = buildMatrixClient(homeserver, ssStr, userId)
                restoreShadowSession(client, sessionData)
                val encryption = client.encryption()
                encryption.recover(recoveryKey)

                sessionData.put("is_verified", "true")
                val sessionsStr = sharedPrefs.getString("shadow_sessions", "{}")
                val sessions = JSONObject(sessionsStr)
                sessions.put(userId, sessionData)
                sharedPrefs.edit().putString("shadow_sessions", sessions.toString()).apply()

                val ret = JSObject()
                ret.put("status", "shadow_verified")
                call.resolve(ret)
            } catch (e: Exception) {
                call.reject("Failed to verify Shadow Device", e)
            }
        }
    }

    @PluginMethod
    fun getShadowStatus(call: PluginCall) {
        val userId = call.getString("userId") ?: return call.reject("Missing userId")
        val sharedPrefs = context.getSharedPreferences("ParadiseShadow", Context.MODE_PRIVATE)
        val sessionData = getShadowSession(sharedPrefs, userId)
        val ret = JSObject()
        if (sessionData != null && sessionData.optString("is_verified") == "true") {
            ret.put("isVerified", true)
        } else {
            ret.put("isVerified", false)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun getActivePushUser(call: PluginCall) {
        val sharedPrefs = context.getSharedPreferences("ParadiseShadow", Context.MODE_PRIVATE)
        val activeUser = sharedPrefs.getString("active_push_user", null)
        val ret = JSObject()
        if (activeUser != null) {
            ret.put("activeUser", activeUser)
        }
        call.resolve(ret)
    }

    @PluginMethod
    fun setPreviewPreferences(call: PluginCall) {
        val isEnabled = call.getBoolean("enabled", true)
        val sharedPrefs = context.getSharedPreferences("ParadiseShadow", Context.MODE_PRIVATE)
        sharedPrefs.edit().putBoolean("show_previews", isEnabled ?: true).apply()
        call.resolve()
    }
}
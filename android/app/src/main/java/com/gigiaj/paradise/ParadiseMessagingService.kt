package com.gigiaj.paradise

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.capacitorjs.plugins.pushnotifications.PushNotificationsPlugin
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

class ParadiseMessagingService : FirebaseMessagingService() {

    companion object {
        private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val workerMutex = Mutex()
        private val seenEventIds = object : LinkedHashSet<String>() {
            override fun add(element: String): Boolean {
                val added = super.add(element)
                if (size > 100) {
                    val it = iterator()
                    if (it.hasNext()) {
                        it.next()
                        it.remove()
                    }
                }
                return added
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("ParadisePush", "FCM onNewToken received")
        PushNotificationsPlugin.onNewToken(token)
    }

    private fun JSONObject.optCleanString(key: String): String? {
        if (isNull(key)) return null
        val v = optString(key, "").trim()
        return if (v.isEmpty() || v == "null") null else v
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        Log.d("ParadisePush", "FCM onMessageReceived keys=${data.keys}")

        val roomId = data["room_id"]?.trim()
        val eventId = data["event_id"]?.trim()

        if (roomId.isNullOrEmpty() || eventId.isNullOrEmpty()) {
            Log.d("ParadisePush", "Ignoring non-event push payload keys=${data.keys}")
            return
        }

        synchronized(seenEventIds) {
            if (!seenEventIds.add(eventId)) {
                Log.d("ParadisePush", "Duplicate event_id=$eventId ignored")
                return
            }
        }

        val payloadJson = JSONObject()
        for ((k, v) in data) {
            payloadJson.put(k, v)
        }
        val defaultTag = roomId

        if (!ShadowPushRuntime.getShowPreviews(applicationContext)) {
            Log.d("ParadisePush", "Previews disabled, showing generic banner")
            showNotification(defaultTag, "New Message", null, "Open Paradise to view.", data)
            return
        }

        val activeUserId = ShadowPushRuntime.getActiveUser(applicationContext)
        if (activeUserId == null) {
            Log.e("ParadisePush", "No active_push_user in shadow_state.json")
            return
        }

        val sessionData = ShadowPushRuntime.getShadowSession(applicationContext, activeUserId)
        if (sessionData == null) {
            Log.e("ParadisePush", "No shadow_session found for $activeUserId")
            return
        }

        val scriptName = sessionData.optString("notification_script", "push-matrix.js")

        serviceScope.launch {
            workerMutex.withLock {
                try {
                    Log.d("ParadisePush", "Invoking QuickJS ($scriptName) for event=$eventId")
                    val args = JSONArray().apply {
                        put(payloadJson)
                        put(sessionData)
                    }

                    val res = ShadowPushRuntime.invokeWorkerFunction(
                        context = applicationContext,
                        userId = activeUserId,
                        scriptName = scriptName,
                        functionName = "resolvePushNotification",
                        argsJsonArray = args
                    )

                    Log.d("ParadisePush", "QuickJS result: $res")

                    if (res.optBoolean("ignore", false)) {
                        return@withLock
                    }

                    val tag = res.optCleanString("tag") ?: defaultTag
                    val title = res.optCleanString("title") ?: "New Message"
                    val subtitle = res.optCleanString("subtitle")
                    val body = res.optCleanString("body") ?: "Open Paradise to view."

                    if (res.optBoolean("is_call", false)) {
                        ParadiseCallPlugin.triggerIncomingCall(applicationContext, tag, title)
                    }

                    showNotification(tag, title, subtitle, body, data)
                } catch (t: Throwable) {
                    Log.e("ParadisePush", "Push worker evaluation failed", t)
                    showNotification(defaultTag, "New Message", null, "Open Paradise to view.", data)
                }
            }
        }
    }

    private fun showNotification(
        tag: String,
        title: String,
        subtitle: String?,
        body: String,
        rawExtras: Map<String, String>
    ) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "paradise_messages"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("push_tag", tag)
            for ((k, v) in rawExtras) {
                putExtra(k, v)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            tag.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val largeIcon = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setColor(0xCCDD51.toInt())
            .setLargeIcon(largeIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (!subtitle.isNullOrBlank()) {
            builder.setSubText(subtitle)
        }

        notificationManager.notify(tag, 0, builder.build())
    }
}


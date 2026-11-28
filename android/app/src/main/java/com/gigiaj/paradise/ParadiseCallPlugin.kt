package com.gigiaj.paradise

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.getcapacitor.JSObject
import com.getcapacitor.PermissionState
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import com.getcapacitor.annotation.PermissionCallback

@CapacitorPlugin(
    name = "ParadiseCall",
    permissions = [
        Permission(strings = [Manifest.permission.RECORD_AUDIO], alias = "microphone"),
        Permission(strings = [Manifest.permission.CAMERA], alias = "camera"),
        Permission(strings = [Manifest.permission.BLUETOOTH_CONNECT], alias = "bluetooth"),
        Permission(strings = [Manifest.permission.POST_NOTIFICATIONS], alias = "notifications")
    ]
)
class ParadiseCallPlugin : Plugin() {

    data class PendingCallAction(
        val action: String,
        val roomId: String,
        val engine: String? = null,
        val hasVideo: Boolean = false
    )

    companion object {
        @Volatile
        var instance: ParadiseCallPlugin? = null

        private val pendingLock = Any()
        private var pendingAction: PendingCallAction? = null

        fun deliverOrQueueAnswer(roomId: String, engine: String? = null, hasVideo: Boolean = false) {
            val live = instance
            if (live != null && live.hasListeners("callAnswered")) {
                live.notifyAppAnswered(roomId, engine, hasVideo)
            } else {
                synchronized(pendingLock) {
                    pendingAction = PendingCallAction("answer", roomId, engine, hasVideo)
                }
            }
        }

        fun deliverOrQueueDecline(roomId: String) {
            val live = instance
            if (live != null && live.hasListeners("callDeclined")) {
                live.notifyAppDeclined(roomId)
            } else {
                synchronized(pendingLock) {
                    pendingAction = PendingCallAction("decline", roomId)
                }
            }
        }

        fun consumePendingAction(): PendingCallAction? {
            synchronized(pendingLock) {
                val out = pendingAction
                pendingAction = null
                return out
            }
        }

        fun triggerIncomingCall(
            context: Context,
            roomId: String,
            callerName: String,
            engine: String? = null,
            subtitle: String? = null,
            hasVideo: Boolean = false
        ) {
            ParadiseForegroundService.showIncomingCallNotification(
                context = context,
                roomId = roomId,
                engine = engine,
                callerName = callerName,
                subtitle = subtitle,
                hasVideo = hasVideo
            )
        }
    }

    override fun load() {
        instance = this
        try {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            if (telecomManager != null) {
                val componentName = ComponentName(context, ParadiseConnectionService::class.java)
                val handle = PhoneAccountHandle(componentName, "ParadiseCalls")
                val account = PhoneAccount.builder(handle, "Paradise Calls")
                    .setCapabilities(
                        PhoneAccount.CAPABILITY_SELF_MANAGED or PhoneAccount.CAPABILITY_VIDEO_CALLING
                    )
                    .build()
                telecomManager.registerPhoneAccount(account)
            }
        } catch (_: Throwable) {
        }
    }

    override fun handleOnDestroy() {
        if (instance === this) {
            instance = null
        }
        super.handleOnDestroy()
    }

    @PluginMethod
    fun requestMediaPermissions(call: PluginCall) {
        val wantVideo = call.getBoolean("video", false) ?: false
        val neededAliases = mutableListOf<String>()
        if (getPermissionState("microphone") != PermissionState.GRANTED) {
            neededAliases.add("microphone")
        }
        if (wantVideo && getPermissionState("camera") != PermissionState.GRANTED) {
            neededAliases.add("camera")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            getPermissionState("bluetooth") != PermissionState.GRANTED
        ) {
            neededAliases.add("bluetooth")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            getPermissionState("notifications") != PermissionState.GRANTED
        ) {
            neededAliases.add("notifications")
        }

        if (neededAliases.isEmpty()) {
            call.resolve(JSObject().put("granted", true))
        } else {
            requestPermissionForAliases(
                neededAliases.toTypedArray(),
                call,
                "mediaPermissionsCallback"
            )
        }
    }

    @PermissionCallback
    private fun mediaPermissionsCallback(call: PluginCall) {
        val micGranted = getPermissionState("microphone") == PermissionState.GRANTED
        call.resolve(
            JSObject()
                .put("granted", micGranted)
                .put("cameraGranted", getPermissionState("camera") == PermissionState.GRANTED)
                .put("notificationsGranted", getPermissionState("notifications") == PermissionState.GRANTED)
        )
    }

    @PluginMethod
    fun startCall(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val name = call.getString("name") ?: "Paradise Call"
        val hasVideo = call.getBoolean("hasVideo", false) ?: false
        val engine = call.getString("engine")

        ParadiseForegroundService.startOrUpdate(
            context = context,
            roomId = roomId,
            callerName = name,
            hasVideo = hasVideo,
            engine = engine
        )
        call.resolve(JSObject().put("ok", true))
    }

    @PluginMethod
    fun reportConnected(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        ParadiseConnectionService.activeConnections[roomId]?.setActive()

        if (ParadiseForegroundService.isCallActive) {
            val intent = Intent(context, ParadiseForegroundService::class.java).apply {
                action = ParadiseForegroundService.ACTION_MARK_CONNECTED
                putExtra("roomId", roomId)
            }
            context.startService(intent)
        }
        call.resolve(JSObject().put("ok", true))
    }

    @PluginMethod
    fun setMuted(call: PluginCall) {
        val muted = call.getBoolean("muted", false) ?: false
        if (ParadiseForegroundService.isCallActive) {
            val intent = Intent(context, ParadiseForegroundService::class.java).apply {
                action = ParadiseForegroundService.ACTION_SET_MUTED
                putExtra("muted", muted)
            }
            context.startService(intent)
        }
        call.resolve(JSObject().put("ok", true))
    }

    @PluginMethod
    fun setVideoEnabled(call: PluginCall) {
        val enabled = call.getBoolean("enabled", false) ?: false
        if (ParadiseForegroundService.isCallActive) {
            val intent = Intent(context, ParadiseForegroundService::class.java).apply {
                action = ParadiseForegroundService.ACTION_SET_VIDEO
                putExtra("enabled", enabled)
            }
            context.startService(intent)
        }
        call.resolve(JSObject().put("ok", true))
    }

    @PluginMethod
    fun reportIncomingCall(call: PluginCall) {
        val roomId = call.getString("roomId") ?: return call.reject("Missing roomId")
        val name = call.getString("name") ?: "Incoming Call"
        val hasVideo = call.getBoolean("hasVideo", false) ?: false
        val engine = call.getString("engine")
        triggerIncomingCall(context, roomId, name, engine, null, hasVideo)
        call.resolve(JSObject().put("ok", true))
    }

    @PluginMethod
    fun endCall(call: PluginCall) {
        val roomId = call.getString("roomId")
        if (!roomId.isNullOrBlank()) {
            ParadiseConnectionService.activeConnections[roomId]?.onDisconnect()
        }
        ParadiseForegroundService.stopCallService(context, roomId)
        call.resolve(JSObject().put("ok", true))
    }

    @PluginMethod
    fun getPendingCallAction(call: PluginCall) {
        val pending = consumePendingAction()
        if (pending == null) {
            call.resolve(JSObject().put("action", JSObject.NULL))
        } else {
            val out = JSObject()
                .put("action", pending.action)
                .put("roomId", pending.roomId)
                .put("hasVideo", pending.hasVideo)
            if (!pending.engine.isNullOrBlank()) {
                out.put("engine", pending.engine)
            }
            call.resolve(out)
        }
    }

    @PluginMethod
    fun getVoipToken(call: PluginCall) {
        call.resolve(JSObject().put("token", JSObject.NULL))
    }

    fun notifyAppAnswered(roomId: String, engine: String? = null, hasVideo: Boolean = false) {
        val payload = JSObject()
            .put("roomId", roomId)
            .put("hasVideo", hasVideo)
        if (!engine.isNullOrBlank()) {
            payload.put("engine", engine)
        }
        notifyListeners("callAnswered", payload, true)
    }

    fun notifyAppDeclined(roomId: String) {
        notifyListeners(
            "callDeclined",
            JSObject().put("roomId", roomId),
            true
        )
    }

    fun notifyAppMuted(roomId: String, muted: Boolean) {
        notifyListeners(
            "callMuted",
            JSObject()
                .put("roomId", roomId)
                .put("muted", muted),
            true
        )
    }
}

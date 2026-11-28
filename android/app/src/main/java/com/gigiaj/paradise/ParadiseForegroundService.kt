package com.gigiaj.paradise

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.RingtoneManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat

class ParadiseForegroundService : Service() {

    companion object {
        private const val TAG = "ParadiseCallService"

        const val ONGOING_CHANNEL_ID = "paradise_active_call_v2"
        const val INCOMING_CHANNEL_ID = "paradise_incoming_call"
        const val ONGOING_CALL_NOTIFICATION_ID = 199
        const val INCOMING_CALL_NOTIFICATION_ID = 198

        const val ACTION_START_OR_UPDATE = "com.gigiaj.paradise.CALL_START_OR_UPDATE"
        const val ACTION_MARK_CONNECTED = "com.gigiaj.paradise.CALL_MARK_CONNECTED"
        const val ACTION_SET_MUTED = "com.gigiaj.paradise.CALL_SET_MUTED"
        const val ACTION_SET_VIDEO = "com.gigiaj.paradise.CALL_SET_VIDEO"
        const val ACTION_TOGGLE_MUTE = "com.gigiaj.paradise.CALL_TOGGLE_MUTE"
        const val ACTION_HANGUP = "com.gigiaj.paradise.CALL_HANGUP"
        const val ACTION_DECLINE_INCOMING = "com.gigiaj.paradise.CALL_DECLINE_INCOMING"
        const val ACTION_ANSWER_CALL = "com.gigiaj.paradise.ACTION_ANSWER_CALL"

        @Volatile
        var isCallActive: Boolean = false
            private set

        @Volatile
        var activeRoomId: String? = null
            private set

        @Volatile
        var activeCallerName: String = "Paradise Call"
            private set

        @Volatile
        var activeEngine: String? = null
            private set

        @Volatile
        var isMuted: Boolean = false
            private set

        @Volatile
        var hasVideo: Boolean = false
            private set

        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        var connectedAtEpochMs: Long = 0L
            private set

        fun startOrUpdate(
            context: Context,
            roomId: String,
            callerName: String,
            hasVideo: Boolean,
            engine: String? = null
        ) {
            val appCtx = context.applicationContext
            cancelIncomingCallNotification(appCtx, roomId)
            isCallActive = true
            MainActivity.notifyCallVisibilityChanged()

            val intent = Intent(appCtx, ParadiseForegroundService::class.java).apply {
                action = ACTION_START_OR_UPDATE
                putExtra("roomId", roomId)
                putExtra("callerName", callerName)
                putExtra("hasVideo", hasVideo)
                if (!engine.isNullOrBlank()) putExtra("engine", engine)
            }
            try {
                appCtx.startForegroundService(intent)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start ParadiseForegroundService", t)
            }
        }

        fun stopCallService(context: Context, roomId: String? = null) {
            val appCtx = context.applicationContext
            cancelIncomingCallNotification(appCtx, roomId)
            isCallActive = false
            MainActivity.notifyCallVisibilityChanged()
            try {
                appCtx.stopService(Intent(appCtx, ParadiseForegroundService::class.java))
            } catch (_: Throwable) {}
        }

        private fun ensureIncomingChannel(nm: NotificationManager) {
            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val channel = NotificationChannel(
                INCOMING_CHANNEL_ID,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Incoming voice and video calls"
                enableVibration(true)
                setSound(ringtoneUri, attrs)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(channel)
        }

        fun showIncomingCallNotification(
            context: Context,
            roomId: String,
            engine: String?,
            callerName: String,
            subtitle: String? = null,
            hasVideo: Boolean = false
        ) {
            val appCtx = context.applicationContext
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            ensureIncomingChannel(nm)

            val answerIntent = Intent(appCtx, MainActivity::class.java).apply {
                action = ACTION_ANSWER_CALL
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("roomId", roomId)
                putExtra("callerName", callerName)
                putExtra("hasVideo", hasVideo)
                if (!engine.isNullOrBlank()) putExtra("engine", engine)
            }
            val answerPi = PendingIntent.getActivity(
                appCtx,
                roomId.hashCode() xor 0x11,
                answerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val fullScreenIntent = Intent(appCtx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("incomingCallRoomId", roomId)
                putExtra("callerName", callerName)
                putExtra("hasVideo", hasVideo)
                if (!engine.isNullOrBlank()) putExtra("engine", engine)
            }
            val fullScreenPi = PendingIntent.getActivity(
                appCtx,
                roomId.hashCode() xor 0x22,
                fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val declineIntent = Intent(appCtx, ParadiseForegroundService::class.java).apply {
                action = ACTION_DECLINE_INCOMING
                putExtra("roomId", roomId)
                if (!engine.isNullOrBlank()) putExtra("engine", engine)
            }
            val declinePi = PendingIntent.getService(
                appCtx,
                roomId.hashCode() xor 0x33,
                declineIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val caller = Person.Builder()
                .setName(callerName.ifBlank { "Incoming Call" })
                .setImportant(true)
                .build()

            val contentLabel = buildString {
                append(if (hasVideo) "Incoming video call" else "Incoming voice call")
                if (!subtitle.isNullOrBlank()) {
                    append(" · ")
                    append(subtitle)
                }
            }

            val builder = NotificationCompat.Builder(appCtx, INCOMING_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setContentTitle(callerName.ifBlank { "Incoming Call" })
                .setContentText(contentLabel)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(fullScreenPi)
                .setFullScreenIntent(fullScreenPi, true)
                .setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, declinePi, answerPi))

            nm.notify(roomId, INCOMING_CALL_NOTIFICATION_ID, builder.build())
        }

        fun cancelIncomingCallNotification(context: Context, roomId: String? = null) {
            val nm = context.applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!roomId.isNullOrBlank()) {
                nm.cancel(roomId, INCOMING_CALL_NOTIFICATION_ID)
            } else {
                nm.activeNotifications
                    .filter { it.id == INCOMING_CALL_NOTIFICATION_ID }
                    .forEach { nm.cancel(it.tag, it.id) }
            }
        }
    }

    private var audioManager: AudioManager? = null
    private var previousAudioMode: Int = AudioManager.MODE_NORMAL
    private var previousSpeakerState: Boolean = false
    private var audioFocusRequest: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var audioConfigured: Boolean = false

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_OR_UPDATE

        when (action) {
            ACTION_DECLINE_INCOMING -> {
                val roomId = intent?.getStringExtra("roomId")
                cancelIncomingCallNotification(this, roomId)
                if (!roomId.isNullOrBlank()) {
                    ParadiseConnectionService.activeConnections[roomId]?.onReject()
                    ParadiseCallPlugin.deliverOrQueueDecline(roomId)
                }
                if (!isCallActive) {
                    stopSelf()
                }
                return START_NOT_STICKY
            }

            ACTION_HANGUP -> {
                val roomId = activeRoomId ?: intent?.getStringExtra("roomId")
                isCallActive = false
                MainActivity.notifyCallVisibilityChanged()
                if (!roomId.isNullOrBlank()) {
                    ParadiseConnectionService.activeConnections[roomId]?.onDisconnect()
                    ParadiseCallPlugin.instance?.notifyAppDeclined(roomId)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_MUTE -> {
                if (isCallActive) {
                    isMuted = !isMuted
                    val roomId = activeRoomId
                    if (!roomId.isNullOrBlank()) {
                        ParadiseCallPlugin.instance?.notifyAppMuted(roomId, isMuted)
                    }
                    promoteForegroundNotification()
                }
                return START_STICKY
            }

            ACTION_MARK_CONNECTED -> {
                val roomId = intent?.getStringExtra("roomId")
                if (isCallActive && (roomId == null || roomId == activeRoomId)) {
                    if (!isConnected) {
                        isConnected = true
                        connectedAtEpochMs = System.currentTimeMillis()
                    }
                    configureCallAudioSession()
                    promoteForegroundNotification()
                }
                return START_STICKY
            }

            ACTION_SET_MUTED -> {
                if (isCallActive) {
                    isMuted = intent?.getBooleanExtra("muted", false) ?: false
                    promoteForegroundNotification()
                }
                return START_STICKY
            }

            ACTION_SET_VIDEO -> {
                if (isCallActive) {
                    val nextVideo = intent?.getBooleanExtra("enabled", false) ?: false
                    if (nextVideo != hasVideo) {
                        hasVideo = nextVideo
                        routeCallAudio()
                        promoteForegroundNotification()
                    }
                }
                return START_STICKY
            }

            else -> {
                val roomId = intent?.getStringExtra("roomId") ?: activeRoomId ?: "call"
                val caller = intent?.getStringExtra("callerName") ?: activeCallerName
                val video = intent?.getBooleanExtra("hasVideo", hasVideo) ?: false
                val engine = intent?.getStringExtra("engine") ?: activeEngine

                val isNewRoom = roomId != activeRoomId || !isCallActive
                activeRoomId = roomId
                activeCallerName = caller
                hasVideo = video
                activeEngine = engine
                if (isNewRoom) {
                    isMuted = false
                    isConnected = false
                    connectedAtEpochMs = System.currentTimeMillis()
                }
                isCallActive = true
                MainActivity.notifyCallVisibilityChanged()

                acquireLocks()
                configureCallAudioSession()
                promoteForegroundNotification()
                return START_STICKY
            }
        }
    }

    private fun computeForegroundServiceType(): Int {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val micGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (micGranted) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            val camGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            if (hasVideo && camGranted) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
        }
        return type
    }

    private fun promoteForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            ONGOING_CHANNEL_ID,
            "Active Calls",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Ongoing voice and video call status and controls"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(ch)

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            activeRoomId?.let { putExtra("roomId", it) }
        }
        val contentPi = PendingIntent.getActivity(
            this,
            1001,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hangupIntent = Intent(this, ParadiseForegroundService::class.java).apply {
            action = ACTION_HANGUP
            activeRoomId?.let { putExtra("roomId", it) }
        }
        val hangupPi = PendingIntent.getService(
            this,
            1002,
            hangupIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val muteIntent = Intent(this, ParadiseForegroundService::class.java).apply {
            action = ACTION_TOGGLE_MUTE
        }
        val mutePi = PendingIntent.getService(
            this,
            1003,
            muteIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = when {
            !isConnected -> "Connecting call..."
            isMuted -> "Microphone muted · Tap to return"
            hasVideo -> "Video call in progress · Tap to return"
            else -> "Voice call in progress · Tap to return"
        }

        val muteAction = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_notify,
            if (isMuted) "Unmute" else "Mute",
            mutePi
        ).build()

        val hangupAction = NotificationCompat.Action.Builder(
            R.drawable.ic_stat_notify,
            "Hang Up",
            hangupPi
        ).build()

        val notification = NotificationCompat.Builder(this, ONGOING_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(activeCallerName.ifBlank { "Paradise Call" })
            .setContentText(statusText)
            .setSubText(if (isConnected) "Active Call" else "Dialing")
            .setContentIntent(contentPi)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFF23A55A.toInt())
            .setColorized(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(isConnected)
            .setWhen(if (connectedAtEpochMs > 0L) connectedAtEpochMs else System.currentTimeMillis())
            .addAction(muteAction)
            .addAction(hangupAction)
            .build()

        try {
            val fgsType = computeForegroundServiceType()
            startForeground(ONGOING_CALL_NOTIFICATION_ID, notification, fgsType)
        } catch (secErr: SecurityException) {
            Log.w(TAG, "Falling back to MEDIA_PLAYBACK foreground service type", secErr)
            startForeground(
                ONGOING_CALL_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        }
    }

    private fun acquireLocks() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Paradise::CallWakeLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire(4 * 60 * 60 * 1000L)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "WakeLock acquire warning", t)
        }

        try {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "Paradise::CallWifiLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "WifiLock acquire warning", t)
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Throwable) {}
        wakeLock = null

        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Throwable) {}
        wifiLock = null
    }

    private fun configureCallAudioSession() {
        val am = audioManager ?: return
        if (!audioConfigured) {
            previousAudioMode = am.mode
            @Suppress("DEPRECATION")
            previousSpeakerState = am.isSpeakerphoneOn
            audioConfigured = true

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { focusChange ->
                    if (focusChange == AudioManager.AUDIOFOCUS_GAIN && isCallActive) {
                        am.mode = AudioManager.MODE_IN_COMMUNICATION
                        routeCallAudio()
                    }
                }
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req)
            am.mode = AudioManager.MODE_IN_COMMUNICATION

            val cb = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    if (isCallActive) routeCallAudio()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    if (isCallActive) routeCallAudio()
                }
            }
            audioDeviceCallback = cb
            am.registerAudioDeviceCallback(cb, null)
        } else if (am.mode != AudioManager.MODE_IN_COMMUNICATION) {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
        }
        routeCallAudio()
    }

    private fun routeCallAudio() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = am.availableCommunicationDevices
                val btDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
                }
                val wiredDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                val speakerDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
                val earpieceDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                }
                val preferred = btDevice
                    ?: wiredDevice
                    ?: (if (hasVideo) speakerDevice else (speakerDevice ?: earpieceDevice))
                if (preferred != null) {
                    am.setCommunicationDevice(preferred)
                }
            } else {
                @Suppress("DEPRECATION")
                if (am.isBluetoothScoAvailableOffCall) {
                    am.startBluetoothSco()
                    am.isBluetoothScoOn = true
                } else {
                    am.isSpeakerphoneOn = true
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Audio routing warning", t)
        }
    }

    private fun restoreAudioSession() {
        val am = audioManager ?: return
        if (!audioConfigured) return
        audioConfigured = false
        try {
            audioDeviceCallback?.let { am.unregisterAudioDeviceCallback(it) }
            audioDeviceCallback = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.stopBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = previousSpeakerState
            }
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
            am.mode = previousAudioMode
        } catch (t: Throwable) {
            Log.w(TAG, "Audio session restore warning", t)
        }
    }

    override fun onDestroy() {
        isCallActive = false
        activeRoomId = null
        isConnected = false
        isMuted = false
        hasVideo = false
        MainActivity.notifyCallVisibilityChanged()
        restoreAudioSession()
        releaseLocks()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

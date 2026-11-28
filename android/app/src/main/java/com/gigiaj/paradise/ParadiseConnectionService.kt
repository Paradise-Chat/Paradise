package com.gigiaj.paradise

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

class ParadiseConnectionService : ConnectionService() {
    companion object {
        val activeConnections = mutableMapOf<String, ParadiseConnection>()
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val bundle = request?.extras
        val roomId = bundle?.getString("roomId") ?: "unknown"
        val callerName = bundle?.getString("callerName") ?: "Incoming Call"
        val engine = bundle?.getString("engine")
        val hasVideo = bundle?.getBoolean("hasVideo", false) ?: false

        val connection = ParadiseConnection(roomId, callerName, engine, hasVideo, applicationContext)
        connection.setAddress(Uri.fromParts("sip", roomId, null), TelecomManager.PRESENTATION_ALLOWED)
        connection.setCallerDisplayName(callerName, TelecomManager.PRESENTATION_ALLOWED)
        connection.connectionProperties = Connection.PROPERTY_SELF_MANAGED
        connection.audioModeIsVoip = true
        connection.setInitializing()
        connection.setRinging()

        activeConnections[roomId] = connection
        return connection
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        val bundle = request?.extras
        val roomId = bundle?.getString("roomId") ?: "unknown"
        val callerName = bundle?.getString("callerName") ?: "Paradise Call"
        val engine = bundle?.getString("engine")
        val hasVideo = bundle?.getBoolean("hasVideo", false) ?: false

        val connection = ParadiseConnection(roomId, callerName, engine, hasVideo, applicationContext)
        connection.setAddress(Uri.fromParts("sip", roomId, null), TelecomManager.PRESENTATION_ALLOWED)
        connection.setCallerDisplayName(callerName, TelecomManager.PRESENTATION_ALLOWED)
        connection.connectionProperties = Connection.PROPERTY_SELF_MANAGED
        connection.audioModeIsVoip = true
        connection.setInitializing()
        connection.setDialing()

        activeConnections[roomId] = connection
        return connection
    }
}

class ParadiseConnection(
    val roomId: String,
    private val callerName: String,
    private val engine: String?,
    private val hasVideo: Boolean,
    val context: Context
) : Connection() {

    override fun onAnswer() {
        super.onAnswer()
        setActive()
        ParadiseForegroundService.startOrUpdate(context, roomId, callerName, hasVideo, engine)
        ParadiseCallPlugin.deliverOrQueueAnswer(roomId, engine, hasVideo)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            putExtra("roomId", roomId)
        }
        context.startActivity(launchIntent)
    }

    override fun onDisconnect() {
        super.onDisconnect()
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        destroy()
        cleanup()
        ParadiseCallPlugin.deliverOrQueueDecline(roomId)
    }

    override fun onReject() {
        super.onReject()
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        destroy()
        cleanup()
        ParadiseCallPlugin.deliverOrQueueDecline(roomId)
    }

    private fun cleanup() {
        ParadiseConnectionService.activeConnections.remove(roomId)
        ParadiseForegroundService.stopCallService(context, roomId)
    }
}

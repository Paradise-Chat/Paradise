package com.gigiaj.paradise

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    public override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(ShadowDevicePlugin::class.java)
        registerPlugin(ParadiseCallPlugin::class.java)
        super.onCreate(savedInstanceState)
        
        handleCallIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallIntent(intent)
    }

    private fun handleCallIntent(incomingIntent: Intent?) {
        val action = incomingIntent?.getStringExtra("action")
        val roomId = incomingIntent?.getStringExtra("roomId")
        
        if (action == "answerCall" && roomId != null) {
            Handler(Looper.getMainLooper()).postDelayed({
                ParadiseConnectionService.pluginInterface?.notifyAppAnswered(roomId)
            }, 1000)
            
            incomingIntent.removeExtra("action")
            incomingIntent.removeExtra("roomId")
        }
    }
}

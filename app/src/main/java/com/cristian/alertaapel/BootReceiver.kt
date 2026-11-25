package com.cristian.alertaapel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Repornește serviciul după restartul telefonului sau după actualizarea aplicației. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.enabled(context)) return
        try {
            ServiceControl.start(context)
        } catch (_: Exception) {
            // de ex. permisiuni retrase – utilizatorul trebuie să deschidă aplicația
        }
    }
}

object ServiceControl {
    fun start(c: Context) {
        when (Prefs.mode(c)) {
            Prefs.MODE_SENDER -> c.startForegroundService(Intent(c, SenderService::class.java))
            Prefs.MODE_RECEIVER -> c.startForegroundService(Intent(c, ReceiverService::class.java))
        }
    }

    fun stopAll(c: Context) {
        c.stopService(Intent(c, SenderService::class.java))
        c.stopService(Intent(c, ReceiverService::class.java))
    }
}

package com.cristian.alertaapel

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.Executors

/**
 * Rulează pe telefonul care PRIMEȘTE apelurile.
 * Ascultă starea apelurilor și trimite un mesaj prin Bluetooth către telefonul receptor.
 */
class SenderService : Service() {

    companion object {
        private const val TAG = "AlertaApel/Sender"
        const val ACTION_TEST = "com.cristian.alertaapel.TEST"
        @Volatile var running = false
        @Volatile var lastStatus: String = "—"
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    // stare apel
    private var lastState = TelephonyManager.EXTRA_STATE_IDLE
    private var ringingSent = false
    private var wasAnswered = false
    private var currentNumber: String? = null
    private var currentName: String? = null

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION")
            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            onPhoneState(state, number)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Notifs.createChannels(this)
        val notif = Notifs.serviceNotification(this, "Trimit apelurile către ${Prefs.targetName(this) ?: "receptor"}")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifs.ID_SERVICE_SENDER, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(Notifs.ID_SERVICE_SENDER, notif)
        }
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(phoneReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(phoneReceiver, filter)
        }
        running = true
        Log.i(TAG, "pornit")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST) send(Msg.TEST, null, null)
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        try { unregisterReceiver(phoneReceiver) } catch (_: Exception) {}
        main.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------------------------------------------------------------------------

    /**
     * Android trimite PHONE_STATE de două ori când sună: o dată fără număr și o dată cu număr
     * (al doilea doar dacă avem READ_CALL_LOG). Așteptăm puțin numărul înainte să trimitem.
     */
    private fun onPhoneState(state: String, number: String?) {
        Log.d(TAG, "stare=$state numar=$number")
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (lastState != TelephonyManager.EXTRA_STATE_RINGING) {
                    // apel nou
                    ringingSent = false
                    wasAnswered = false
                    currentNumber = null
                    currentName = null
                }
                if (!number.isNullOrBlank()) currentNumber = number
                if (ringingSent) return
                if (currentNumber != null) {
                    sendRinging()
                } else {
                    main.postDelayed({ if (!ringingSent && lastState == TelephonyManager.EXTRA_STATE_RINGING) sendRinging() }, 900)
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                if (lastState == TelephonyManager.EXTRA_STATE_RINGING) {
                    wasAnswered = true
                    send(Msg.ANSWERED, currentNumber, currentName)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                if (lastState == TelephonyManager.EXTRA_STATE_RINGING && !wasAnswered) {
                    // a sunat și nu s-a răspuns (poate fi și respins de tine – îl tratăm ca pierdut)
                    if (!ringingSent) sendRinging()
                    send(Msg.MISSED, currentNumber, currentName)
                } else if (lastState == TelephonyManager.EXTRA_STATE_OFFHOOK && ringingSent) {
                    send(Msg.ENDED, currentNumber, currentName)
                }
                ringingSent = false
            }
        }
        lastState = state
    }

    private fun sendRinging() {
        ringingSent = true
        currentName = lookupName(currentNumber)
        send(Msg.CALL, currentNumber, currentName)
    }

    private fun lookupName(number: String?): String? {
        if (number.isNullOrBlank()) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null // fără permisiune la contacte – trimitem doar numărul
        }
    }

    private fun send(type: String, number: String?, name: String?) {
        val line = Msg.encode(type, number, name)
        io.execute { sendWithRetry(line, type) }
    }

    private fun sendWithRetry(line: String, type: String) {
        val addr = Prefs.targetAddress(this)
        if (addr == null) {
            lastStatus = "Nu ai ales telefonul receptor"
            return
        }
        for (attempt in 1..3) {
            try {
                sendOnce(addr, line)
                lastStatus = "OK: $type trimis (${java.text.DateFormat.getTimeInstance().format(java.util.Date())})"
                Log.i(TAG, lastStatus)
                return
            } catch (e: Exception) {
                lastStatus = "Eroare la trimitere ($type): ${e.message}"
                Log.w(TAG, "încercarea $attempt a eșuat", e)
                try { Thread.sleep(1200L * attempt) } catch (_: InterruptedException) { return }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendOnce(addr: String, line: String) {
        val adapter = getSystemService(BluetoothManager::class.java)!!.adapter
            ?: throw IllegalStateException("Bluetooth indisponibil")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth oprit")
        val device = adapter.getRemoteDevice(addr)
        adapter.cancelDiscovery()
        val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
        // dacă ceva se blochează, închidem forțat socket-ul după 8 secunde
        val watchdog = Runnable { try { socket.close() } catch (_: Exception) {} }
        main.postDelayed(watchdog, 8000)
        try {
            socket.connect()
            val out = socket.outputStream
            out.write(line.toByteArray(Charsets.UTF_8))
            out.flush()
            // așteptăm confirmarea "OK" de la receptor
            val buf = ByteArray(8)
            val n = socket.inputStream.read(buf)
            if (n <= 0) throw IllegalStateException("fără confirmare de la receptor")
        } finally {
            main.removeCallbacks(watchdog)
            try { socket.close() } catch (_: Exception) {}
        }
    }
}

package com.cristian.alertaapel

import android.annotation.SuppressLint
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Rulează pe telefonul care TE ANUNȚĂ.
 * Ține deschis un server Bluetooth (RFCOMM) și afișează notificări când primește mesaje.
 */
class ReceiverService : Service() {

    companion object {
        private const val TAG = "AlertaApel/Receiver"
        @Volatile var running = false
        @Volatile var lastStatus: String = "—"
    }

    @Volatile private var stop = false
    @Volatile private var server: BluetoothServerSocket? = null
    private var thread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        Notifs.createChannels(this)
        val notif = Notifs.serviceNotification(this, "Aștept apelurile de pe celălalt telefon")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(Notifs.ID_SERVICE_RECEIVER, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(Notifs.ID_SERVICE_RECEIVER, notif)
        }
        running = true
        thread = Thread({ acceptLoop() }, "bt-accept").also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stop = true
        running = false
        try { server?.close() } catch (_: Exception) {}
        thread?.interrupt()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    private fun acceptLoop() {
        while (!stop) {
            try {
                val adapter = getSystemService(BluetoothManager::class.java)!!.adapter
                if (adapter == null || !adapter.isEnabled) {
                    lastStatus = "Bluetooth oprit – aștept"
                    Thread.sleep(5000)
                    continue
                }
                val s = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                server = s
                lastStatus = "Ascult conexiuni…"
                while (!stop) {
                    val socket = s.accept() // blochează până se conectează emițătorul
                    Thread({ handle(socket) }, "bt-client").start()
                }
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                if (stop) return
                lastStatus = "Server repornit după eroare: ${e.message}"
                Log.w(TAG, "eroare server", e)
                try { server?.close() } catch (_: Exception) {}
                try { Thread.sleep(3000) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun handle(socket: BluetoothSocket) {
        try {
            val line = socket.inputStream.bufferedReader(Charsets.UTF_8).readLine() ?: return
            socket.outputStream.write("OK\n".toByteArray())
            socket.outputStream.flush()
            process(line)
        } catch (e: Exception) {
            Log.w(TAG, "eroare client", e)
        } finally {
            // lăsăm emițătorul să citească "OK" înainte de închidere
            try { Thread.sleep(300) } catch (_: InterruptedException) {}
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun process(line: String) {
        val parts = line.split("\t")
        val type = parts.getOrNull(0) ?: return
        val number = parts.getOrNull(1)?.ifBlank { null }
        val name = parts.getOrNull(2)?.ifBlank { null }
        val text = parts.getOrNull(3)?.ifBlank { null }
        lastStatus = "Primit: $type ${Notifs.label(number, name)}"
        Log.i(TAG, lastStatus)
        when (type) {
            Msg.CALL -> Notifs.showIncoming(this, number, name)
            Msg.TEST -> Notifs.showIncoming(this, null, null, test = true)
            Msg.ANSWERED, Msg.ENDED -> Notifs.cancelIncoming(this)
            Msg.MISSED -> Notifs.showMissed(this, number, name)
            Msg.MESSAGE -> Notifs.showMessage(this, number, name, text)
        }
    }
}

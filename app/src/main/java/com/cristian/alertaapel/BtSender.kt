package com.cristian.alertaapel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executors

/** Trimite mesaje prin Bluetooth către telefonul receptor (folosit de apeluri GSM și de apeluri WhatsApp etc.). */
object BtSender {
    private const val TAG = "AlertaApel/BtSender"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun send(c: Context, type: String, number: String?, name: String?, text: String? = null) {
        val ctx = c.applicationContext
        val line = Msg.encode(type, number, name, text)
        io.execute { sendWithRetry(ctx, line, type) }
    }

    private fun sendWithRetry(c: Context, line: String, type: String) {
        val addr = Prefs.targetAddress(c)
        if (addr == null) {
            SenderService.lastStatus = "Nu ai ales telefonul receptor"
            return
        }
        for (attempt in 1..3) {
            try {
                sendOnce(c, addr, line)
                SenderService.lastStatus = "OK: $type trimis (${java.text.DateFormat.getTimeInstance().format(java.util.Date())})"
                Log.i(TAG, SenderService.lastStatus)
                return
            } catch (e: Exception) {
                SenderService.lastStatus = "Eroare la trimitere ($type): ${e.message}"
                Log.w(TAG, "încercarea $attempt a eșuat", e)
                try { Thread.sleep(1200L * attempt) } catch (_: InterruptedException) { return }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendOnce(c: Context, addr: String, line: String) {
        val adapter = c.getSystemService(BluetoothManager::class.java)!!.adapter
            ?: throw IllegalStateException("Bluetooth indisponibil")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth oprit")
        val device = adapter.getRemoteDevice(addr)
        val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
        // dacă ceva se blochează, închidem forțat socket-ul după 8 secunde
        val watchdog = Runnable { try { socket.close() } catch (_: Exception) {} }
        main.postDelayed(watchdog, 8000)
        try {
            socket.connect()
            val out = socket.outputStream
            out.write(line.toByteArray(Charsets.UTF_8))
            out.flush()
            val buf = ByteArray(8)
            val n = socket.inputStream.read(buf)
            if (n <= 0) throw IllegalStateException("fără confirmare de la receptor")
        } finally {
            main.removeCallbacks(watchdog)
            try { socket.close() } catch (_: Exception) {}
        }
    }
}

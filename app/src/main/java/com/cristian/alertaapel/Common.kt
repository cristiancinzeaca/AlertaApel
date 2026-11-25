package com.cristian.alertaapel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import java.util.UUID

/** UUID-ul serviciului RFCOMM. Trebuie să fie identic pe ambele telefoane. */
val SERVICE_UUID: UUID = UUID.fromString("7b1d5c2e-3f4a-4c8e-9a61-2e5f0c9d4b17")
const val SERVICE_NAME = "AlertaApel"

/** Tipuri de mesaje trimise prin Bluetooth. Format linie: TIP \t numar \t nume \n */
object Msg {
    const val CALL = "CALL"        // sună acum
    const val ANSWERED = "ANSWERED" // s-a răspuns pe telefonul emițător
    const val MISSED = "MISSED"     // apel pierdut
    const val ENDED = "ENDED"       // apel încheiat / respins
    const val TEST = "TEST"
    const val MESSAGE = "MSG"       // mesaj nou: number = aplicația, name = expeditor, text = conținut

    fun encode(type: String, number: String?, name: String?, text: String? = null): String =
        listOf(type, clean(number), clean(name), clean(text)).joinToString("\t") + "\n"

    private fun clean(s: String?) = (s ?: "").replace('\t', ' ').replace('\n', ' ')
}

object Prefs {
    private const val FILE = "alerta_apel"
    const val MODE_SENDER = "sender"
    const val MODE_RECEIVER = "receiver"

    private fun p(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun mode(c: Context): String? = p(c).getString("mode", null)
    fun setMode(c: Context, m: String) = p(c).edit().putString("mode", m).apply()

    fun targetAddress(c: Context): String? = p(c).getString("target_addr", null)
    fun targetName(c: Context): String? = p(c).getString("target_name", null)
    fun setTarget(c: Context, addr: String, name: String) =
        p(c).edit().putString("target_addr", addr).putString("target_name", name).apply()

    fun enabled(c: Context): Boolean = p(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, e: Boolean) = p(c).edit().putBoolean("enabled", e).apply()
}

object Notifs {
    const val CH_SERVICE = "serviciu"
    const val CH_CALL = "apel_intrare_v1"
    const val CH_MISSED = "apel_pierdut"
    const val CH_MSG = "mesaje"

    const val ID_SERVICE_SENDER = 1
    const val ID_SERVICE_RECEIVER = 2
    const val ID_CALL = 10
    private var missedCounter = 100

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)!!

        val service = NotificationChannel(CH_SERVICE, "Serviciu activ", NotificationManager.IMPORTANCE_MIN)
        service.description = "Notificarea permanentă care ține aplicația pornită"
        service.setShowBadge(false)

        val call = NotificationChannel(CH_CALL, "Apel pe celălalt telefon", NotificationManager.IMPORTANCE_HIGH)
        call.description = "Te anunță când celălalt telefon sună"
        call.enableVibration(true)
        call.vibrationPattern = longArrayOf(0, 600, 400, 600, 400, 600)
        call.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        call.lockscreenVisibility = Notification.VISIBILITY_PUBLIC

        val missed = NotificationChannel(CH_MISSED, "Apeluri pierdute", NotificationManager.IMPORTANCE_DEFAULT)
        missed.description = "Apeluri pierdute pe celălalt telefon"

        val msg = NotificationChannel(CH_MSG, "Mesaje de pe celălalt telefon", NotificationManager.IMPORTANCE_HIGH)
        msg.description = "Mesaje WhatsApp, SMS etc. primite pe celălalt telefon"
        msg.enableVibration(true)

        nm.createNotificationChannels(listOf(service, call, missed, msg))
    }

    private fun openApp(c: Context): PendingIntent =
        PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun serviceNotification(c: Context, text: String): Notification =
        Notification.Builder(c, CH_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Alertă Apel")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(c))
            .build()

    fun label(number: String?, name: String?): String = when {
        !name.isNullOrBlank() && !number.isNullOrBlank() -> "$name ($number)"
        !name.isNullOrBlank() -> name
        !number.isNullOrBlank() -> number
        else -> "Număr necunoscut"
    }

    fun showIncoming(c: Context, number: String?, name: String?, test: Boolean = false) {
        val nm = c.getSystemService(NotificationManager::class.java)!!
        val n = Notification.Builder(c, CH_CALL)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle(if (test) "Test reușit ✔" else "📞 Sună celălalt telefon")
            .setContentText(if (test) "Conexiunea Bluetooth funcționează" else label(number, name))
            .setCategory(Notification.CATEGORY_CALL)
            .setAutoCancel(true)
            .setContentIntent(openApp(c))
            .build()
        nm.notify(ID_CALL, n)
    }

    fun cancelIncoming(c: Context) {
        c.getSystemService(NotificationManager::class.java)!!.cancel(ID_CALL)
    }

    fun showMissed(c: Context, number: String?, name: String?) {
        val nm = c.getSystemService(NotificationManager::class.java)!!
        nm.cancel(ID_CALL)
        val n = Notification.Builder(c, CH_MISSED)
            .setSmallIcon(android.R.drawable.sym_call_missed)
            .setContentTitle("Apel pierdut pe celălalt telefon")
            .setContentText(label(number, name))
            .setAutoCancel(true)
            .setShowWhen(true)
            .setContentIntent(openApp(c))
            .build()
        nm.notify(missedCounter++, n)
    }

    private val msgIds = HashMap<String, Int>()
    private var nextMsgId = 1000

    fun showMessage(c: Context, app: String?, sender: String?, text: String?) {
        val nm = c.getSystemService(NotificationManager::class.java)!!
        val key = "${app}|${sender}"
        val id = synchronized(msgIds) { msgIds.getOrPut(key) { nextMsgId++ } }
        val n = Notification.Builder(c, CH_MSG)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle(listOfNotNull(sender, app?.let { "($it)" }).joinToString(" ").ifBlank { "Mesaj nou" })
            .setContentText(text ?: "")
            .setStyle(Notification.BigTextStyle().bigText(text ?: ""))
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setShowWhen(true)
            .setContentIntent(openApp(c))
            .build()
        nm.notify(id, n)
    }
}

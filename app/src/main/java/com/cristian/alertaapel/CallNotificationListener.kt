package com.cristian.alertaapel

import android.app.Notification
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Detectează apelurile din aplicații de internet (WhatsApp, Telegram, Messenger, Signal…)
 * după notificarea de tip "apel" pe care o afișează acestea.
 */
class CallNotificationListener : NotificationListenerService() {

    companion object {
        /** Aplicații de telefonie ale sistemului – apelurile lor sunt deja detectate de SenderService. */
        private val SYSTEM_DIALERS = setOf(
            "com.samsung.android.incallui", "com.samsung.android.dialer",
            "com.android.server.telecom", "com.android.dialer",
            "com.google.android.dialer", "com.android.phone",
            "com.android.incallui", "com.miui.voip"
        )
        private val APP_NAMES = mapOf(
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "org.telegram.messenger" to "Telegram",
            "com.facebook.orca" to "Messenger",
            "org.thoughtcrime.securesms" to "Signal",
            "com.viber.voip" to "Viber",
            "com.instagram.android" to "Instagram",
            "com.skype.raider" to "Skype",
            "com.microsoft.teams" to "Teams",
            "com.google.android.apps.tachyon" to "Google Meet"
        )
        private val MISSED_WORDS = listOf("missed", "perdida", "pierdut", "nepreluat", "verpasst", "manqué", "persa")
        @Volatile var connected = false
        private val log = ArrayDeque<String>()

        fun addLog(line: String) {
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            synchronized(log) {
                log.addFirst("$t $line")
                while (log.size > 12) log.removeLast()
            }
        }

        fun logText(): String = synchronized(log) { log.joinToString("\n") }

        private val INCOMING_WORDS = listOf("incoming", "entrante", "de intrare", "ringing", "in arrivo", "eingehend", "entrant")
        private val CALL_WORDS = listOf("call", "llamada", "apel", "appel", "anruf", "chiamata", "voz", "video", "voice", "vocal")
        private val ONGOING_WORDS = listOf("ongoing", "en curso", "în curs", "in curs", "in corso", "laufend", "en cours")
    }

    /** cheie notificare -> (aplicație, nume apelant) pentru apelurile care sună acum */
    private val ringing = HashMap<String, Pair<String, String?>>()

    private fun active(): Boolean =
        Prefs.enabled(this) && Prefs.mode(this) == Prefs.MODE_SENDER

    override fun onListenerConnected() {
        connected = true
        addLog("ascultare notificări CONECTATĂ")
    }

    override fun onListenerDisconnected() {
        connected = false
        addLog("ascultare notificări deconectată – reconectez")
        try { requestRebind(android.content.ComponentName(this, CallNotificationListener::class.java)) } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (pkg == packageName || pkg in SYSTEM_DIALERS) return
        val n = sbn.notification ?: return
        val known = pkg in APP_NAMES
        // ignorăm complet notificările aplicațiilor care nu sunt de apeluri
        if (!known && n.category != Notification.CATEGORY_CALL) return

        val extras = n.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val rawText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val text = rawText.lowercase()
        val app = APP_NAMES[pkg] ?: appLabel(pkg)
        val hasFullScreen = n.fullScreenIntent != null
        val ongoingFlag = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0

        addLog("$app | cat=${n.category} | fs=$hasFullScreen | ongoing=$ongoingFlag | \"${title ?: ""}\" / \"${rawText.take(40)}\"")

        if (!active()) { addLog("  → ignorat: serviciul e oprit"); return }

        val isMissedCategory = Build.VERSION.SDK_INT >= 30 && n.category == Notification.CATEGORY_MISSED_CALL
        val looksMissed = MISSED_WORDS.any { text.contains(it) } &&
            (n.category == Notification.CATEGORY_CALL ||
                (known && text.length < 60 && CALL_WORDS.any { text.contains(it) }))
        if (isMissedCategory || looksMissed) {
            ringing.remove(sbn.key)
            addLog("  → APEL PIERDUT trimis")
            BtSender.send(this, Msg.MISSED, app, title)
            return
        }

        val looksIncoming = INCOMING_WORDS.any { text.contains(it) }
        val isCall = n.category == Notification.CATEGORY_CALL ||
            (known && (hasFullScreen || looksIncoming) && CALL_WORDS.any { text.contains(it) })
        if (!isCall) return

        // apel în curs (ai răspuns) -> ștergem alerta de pe receptor
        val ongoing = ONGOING_WORDS.any { text.contains(it) } ||
            (!hasFullScreen && !looksIncoming && ringing.containsKey(sbn.key))
        if (ongoing) {
            if (ringing.remove(sbn.key) != null) {
                addLog("  → răspuns, șterg alerta")
                BtSender.send(this, Msg.ANSWERED, app, title)
            }
            return
        }
        if (ringing.containsKey(sbn.key)) return // actualizare a aceleiași notificări
        ringing[sbn.key] = app to title
        addLog("  → APEL trimis către receptor")
        BtSender.send(this, Msg.CALL, app, title)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val r = ringing.remove(sbn.key) ?: return
        if (!active()) return
        addLog("  → notificarea de apel a dispărut, închid alerta")
        BtSender.send(this, Msg.ENDED, r.first, r.second)
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }
}

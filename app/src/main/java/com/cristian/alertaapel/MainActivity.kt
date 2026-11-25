package com.cristian.alertaapel

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var rbSender: RadioButton
    private lateinit var rbReceiver: RadioButton
    private lateinit var btnTarget: Button
    private lateinit var btnToggle: Button
    private lateinit var btnTest: Button
    private lateinit var btnTestMsg: Button
    private lateinit var btnBattery: Button
    private lateinit var btnWhatsApp: Button
    private lateinit var tvStatus: TextView

    private val ui = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() { refresh(); ui.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifs.createChannels(this)
        setContentView(buildUi())
        when (Prefs.mode(this)) {
            Prefs.MODE_SENDER -> rbSender.isChecked = true
            Prefs.MODE_RECEIVER -> rbReceiver.isChecked = true
        }
        refresh()
    }

    override fun onResume() { super.onResume(); ui.post(refresher) }
    override fun onPause() { ui.removeCallbacks(refresher); super.onPause() }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        fun text(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
            text = s; textSize = size
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(6))
        }
        fun button(s: String, onClick: () -> Unit) = Button(this).apply {
            text = s; isAllCaps = false; setOnClickListener { onClick() }
        }

        root.addView(text("Alertă Apel  v" + (try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "?" }), 24f, true))
        root.addView(text("Telefonul care primește apelurile trimite prin Bluetooth o alertă către celălalt telefon. Împerechează întâi telefoanele din Setări → Bluetooth."))

        root.addView(text("1. Ce face acest telefon?", 17f, true))
        val group = RadioGroup(this)
        rbSender = RadioButton(this).apply { id = View.generateViewId(); text = "Primește apelurile (trimite alertele)" }
        rbReceiver = RadioButton(this).apply { id = View.generateViewId(); text = "Mă anunță (primește alertele)" }
        group.addView(rbSender); group.addView(rbReceiver)
        group.setOnCheckedChangeListener { _, checked ->
            val newMode = if (checked == rbSender.id) Prefs.MODE_SENDER else Prefs.MODE_RECEIVER
            if (newMode != Prefs.mode(this)) {
                ServiceControl.stopAll(this)
                Prefs.setEnabled(this, false)
                Prefs.setMode(this, newMode)
            }
            refresh()
        }
        root.addView(group)

        root.addView(text("2. Configurare", 17f, true))
        btnTarget = button("Alege telefonul care te anunță") { chooseTarget() }
        root.addView(btnTarget)
        btnWhatsApp = button("Activează mesajele și apelurile WhatsApp") { openNotificationAccess() }
        root.addView(btnWhatsApp)
        btnBattery = button("Scoate aplicația din optimizarea bateriei") { requestBatteryExemption() }
        root.addView(btnBattery)

        root.addView(text("3. Pornire", 17f, true))
        btnToggle = button("Pornește") { toggle() }
        root.addView(btnToggle)
        btnTest = button("Trimite o alertă de test") { sendTest() }
        root.addView(btnTest)
        btnTestMsg = button("Trimite un mesaj de test") {
            BtSender.send(this, Msg.MESSAGE, "Test", "Alertă Apel", "Acesta este un mesaj de test")
            Toast.makeText(this, "Trimit mesajul de test…", Toast.LENGTH_SHORT).show()
        }
        root.addView(btnTestMsg)

        tvStatus = text("", 13f).apply { setPadding(0, dp(16), 0, 0) }
        root.addView(tvStatus)

        root.addView(text("Sfat Samsung: Setări → Aplicații → Alertă Apel → Baterie → „Fără restricții”, pe ambele telefoane.", 12f).apply {
            gravity = Gravity.START; setPadding(0, dp(16), 0, 0)
        })

        return ScrollView(this).apply { addView(root) }
    }

    private fun refresh() {
        val mode = Prefs.mode(this)
        val isSender = mode == Prefs.MODE_SENDER
        val running = if (isSender) SenderService.running else ReceiverService.running

        btnTarget.visibility = if (isSender) View.VISIBLE else View.GONE
        btnTest.visibility = if (isSender) View.VISIBLE else View.GONE
        btnWhatsApp.visibility = if (isSender) View.VISIBLE else View.GONE
        btnWhatsApp.text = if (hasNotificationAccess())
            "Mesaje + apeluri WhatsApp, SMS etc.: ACTIVE ✔"
        else
            "Activează mesajele și apelurile WhatsApp"
        btnTarget.text = "Alege telefonul care te anunță" +
            (Prefs.targetName(this)?.let { "\nAles: $it" } ?: "")
        btnToggle.isEnabled = mode != null
        btnToggle.text = if (running) "Oprește" else "Pornește"
        btnTest.isEnabled = running
        btnTestMsg.visibility = btnTest.visibility
        btnTestMsg.isEnabled = running
        btnBattery.visibility = if (isIgnoringBattery()) View.GONE else View.VISIBLE

        tvStatus.text = when (mode) {
            null -> "Alege mai întâi rolul telefonului."
            Prefs.MODE_SENDER -> "Stare: ${if (running) "PORNIT" else "oprit"}\nUltima acțiune: ${SenderService.lastStatus}" +
                "\n\nAscultare notificări (WhatsApp): ${if (CallNotificationListener.connected) "CONECTATĂ" else "NECONECTATĂ"}" +
                "\nJurnal WhatsApp/apeluri internet:\n${CallNotificationListener.logText().ifBlank { "(nimic încă)" }}"
            else -> "Stare: ${if (running) "PORNIT" else "oprit"}\nUltima acțiune: ${ReceiverService.lastStatus}" +
                "\n\nJurnal recepție:\n${ReceiverService.logText().ifBlank { "(nimic încă)" }}"
        }
    }

    // ---------------------------------------------------------------- acțiuni

    private fun neededPermissions(): List<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) list += Manifest.permission.BLUETOOTH_CONNECT
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        if (Prefs.mode(this) == Prefs.MODE_SENDER) {
            list += Manifest.permission.READ_PHONE_STATE
            list += Manifest.permission.READ_CALL_LOG   // pentru numărul apelantului
            list += Manifest.permission.READ_CONTACTS   // pentru numele din agendă (opțional)
        }
        return list.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    /** Permisiuni fără de care aplicația nu poate funcționa deloc. */
    private fun missingCritical(): List<String> {
        val crit = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 31) crit += Manifest.permission.BLUETOOTH_CONNECT
        if (Prefs.mode(this) == Prefs.MODE_SENDER) crit += Manifest.permission.READ_PHONE_STATE
        return crit.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    private var pendingStart = false
    private var pendingChoose = false

    private fun toggle() {
        val running = SenderService.running || ReceiverService.running
        if (running) {
            Prefs.setEnabled(this, false)
            ServiceControl.stopAll(this)
            ui.postDelayed({ refresh() }, 300)
            return
        }
        val missing = neededPermissions()
        if (missing.isNotEmpty()) {
            pendingStart = true
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        startNow()
    }

    private fun startNow() {
        if (missingCritical().isNotEmpty()) {
            Toast.makeText(this, "Lipsesc permisiuni necesare (Bluetooth / telefon).", Toast.LENGTH_LONG).show()
            return
        }
        if (Prefs.mode(this) == Prefs.MODE_SENDER && Prefs.targetAddress(this) == null) {
            Toast.makeText(this, "Alege întâi telefonul care te anunță.", Toast.LENGTH_LONG).show()
            return
        }
        Prefs.setEnabled(this, true)
        ServiceControl.start(this)
        ui.postDelayed({ refresh() }, 500)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (pendingStart) { pendingStart = false; startNow() }
        if (pendingChoose) { pendingChoose = false; chooseTarget() }
    }

    @SuppressLint("MissingPermission")
    private fun chooseTarget() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            pendingChoose = true
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 2)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)!!.adapter
        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(this, "Pornește Bluetooth-ul.", Toast.LENGTH_LONG).show()
            return
        }
        val devices = adapter.bondedDevices.toList()
        if (devices.isEmpty()) {
            Toast.makeText(this, "Niciun dispozitiv împerecheat. Împerechează telefoanele din Setări → Bluetooth.", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            return
        }
        val names = devices.map { "${it.name ?: "Fără nume"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Telefonul care te anunță")
            .setItems(names) { _, i ->
                val d = devices[i]
                Prefs.setTarget(this, d.address, d.name ?: d.address)
                refresh()
            }
            .show()
    }

    private fun sendTest() {
        startService(Intent(this, SenderService::class.java).setAction(SenderService.ACTION_TEST))
        Toast.makeText(this, "Trimit testul…", Toast.LENGTH_SHORT).show()
    }

    private fun hasNotificationAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }

    private fun openNotificationAccess() {
        AlertDialog.Builder(this)
            .setTitle("Apeluri WhatsApp")
            .setMessage("Pe pagina următoare activează „Alertă Apel”.\n\nDacă opțiunea e gri sau apare „Setare restricționată”: Setări → Aplicații → Alertă Apel → ⋮ (sus dreapta) → „Permiteți setări restricționate”, apoi revino aici.")
            .setPositiveButton("Continuă") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                } catch (_: Exception) {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                }
            }
            .setNeutralButton("Info aplicație") { _, _ ->
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
            .show()
    }

    private fun isIgnoringBattery(): Boolean =
        getSystemService(PowerManager::class.java)!!.isIgnoringBatteryOptimizations(packageName)

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}

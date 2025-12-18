package br.tv.hallo.jptvmais.launcher

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {

    private val TARGET_PACKAGE = "br.tv.hallo.jptvmais"
    private lateinit var updateManager: UpdateManager
    private val handler = Handler(Looper.getMainLooper())

    private var blockedDialog: AlertDialog? = null
    private var isBlocked = false

    private val authRunnable = object : Runnable {
        override fun run() {
            updateManager.checkAuthorization()
            handler.postDelayed(this, 50_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(LinearLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
            addView(TextView(this@MainActivity).apply {
                text = "Inicializando…"
                setTextColor(Color.WHITE)
                textSize = 22f
            })
        })

        updateManager = UpdateManager(this)
        handler.post(authRunnable)
    }

    /* =====================
       FOREGROUND
       ===================== */
    fun bringToFront() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        startActivity(intent)
    }

    /* =====================
       APP CONTROL
       ===================== */
    fun launchTargetApp() {
        if (isBlocked) return
        val intent = packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    fun killTargetApp() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.killBackgroundProcesses(TARGET_PACKAGE)
    }

    /* =====================
       BLOCK / UNBLOCK
       ===================== */
    fun blockDevice(mac: String) {

        if (isBlocked) return
        isBlocked = true

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)

        if (!dpm.isAdminActive(admin)) {
            Log.e("DEVICE_ADMIN", "Admin NÃO ativo")
            showBlockedDialog(mac)
            return
        }

        try {
            dpm.setLockTaskPackages(admin, arrayOf(packageName))
            startLockTask()
            showBlockedDialog(mac)
            Log.d("DEVICE_ADMIN", "LOCKTASK ATIVO")
        } catch (e: Exception) {
            Log.e("DEVICE_ADMIN", "Erro LockTask", e)
        }
    }

    fun unblockDevice() {

        isBlocked = false
        blockedDialog?.dismiss()
        blockedDialog = null

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)

        if (!dpm.isAdminActive(admin)) return

        try {
            stopLockTask()
            dpm.setLockTaskPackages(admin, emptyArray())
        } catch (_: Exception) {}
    }

    /* =====================
       UI BLOCK
       ===================== */
    private fun showBlockedDialog(mac: String) {

        if (blockedDialog?.isShowing == true) return

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 60, 60, 60)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#222222"))

            addView(TextView(this@MainActivity).apply {
                text = "DISPOSITIVO BLOQUEADO"
                textSize = 26f
                setTextColor(Color.WHITE)
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
            })

            addView(TextView(this@MainActivity).apply {
                text = "\nEste equipamento não está liberado.\n\nMAC:\n$mac"
                textSize = 18f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
            })
        }

        blockedDialog = AlertDialog.Builder(this)
            .setView(layout)
            .setCancelable(false)
            .create()

        blockedDialog?.show()
    }

    /* =====================
       MAC ETH0
       ===================== */
    fun getEth0MacAddress(): String {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            interfaces.firstOrNull { it.name.equals("eth0", true) }
                ?.hardwareAddress
                ?.joinToString(":") { "%02X".format(it) }
                ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}

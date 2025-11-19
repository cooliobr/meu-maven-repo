package br.tv.hallo.jptvmais.launcher

import android.R.attr.orientation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.IOException
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : AppCompatActivity() {
    private val TAG = "LauncherActivity"
    private val TARGET_PACKAGE = "br.tv.hallo.jptvmais"
    private lateinit var updateManager: UpdateManager
    private val handler = Handler(Looper.getMainLooper())
    private var notAllowedDialog: AlertDialog? = null
    private val CHECK_AUTH_INTERVAL = 5000L // 5 segundos
    private var isAuthorized = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val rootLayout = LinearLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        setContentView(rootLayout)

        updateManager = UpdateManager(this)
        updateManager.startPeriodicUpdateCheck() // Inicia checagens periódicas futuras
        // Verificação inicial
        lifecycleScope.launch {
            performInitialUpdateCheck()
        }
        // Iniciar checagem periódica de autorização
        startPeriodicAuthCheck()
    }

    private suspend fun performInitialUpdateCheck() {
        return withContext(Dispatchers.IO) {
            val currentVersion = updateManager.getCurrentVersion() ?: return@withContext launchTargetAppOnMain() // Prosseguir se erro
            val (storeVersion, apkName, liberado) = updateManager.getStoreVersionAndApk()
            withContext(Dispatchers.Main) {
                if (!liberado) {
                    forceStopTargetApp()
                    showNotAllowedMessage()
                } else if (storeVersion != null && apkName != null && updateManager.isStoreVersionNewer(currentVersion, storeVersion)) {
                    val skipCount = updateManager.getSkipCount()
                    if (skipCount >= updateManager.MAX_SKIPS) {
                        updateManager.forceUpdate(storeVersion, apkName)
                    } else {
                        updateManager.showUpdateDialog(storeVersion, apkName, skipCount)
                    }
                } else {
                    launchTargetApp()
                }
                isAuthorized = liberado
            }
        }
    }

    private fun startPeriodicAuthCheck() {
        handler.postDelayed({
            checkAuthorization()
        }, CHECK_AUTH_INTERVAL)
    }

    private fun checkAuthorization() {
        Thread {
            val (storeVersion, apkName, liberado) = updateManager.getStoreVersionAndApk()
            handler.post {
                if (liberado) {
                    if (!isAuthorized) {
                        // Transição para liberado
                        notAllowedDialog?.dismiss()
                        notAllowedDialog = null
                        val currentVersion = updateManager.getCurrentVersion() ?: ""
                        if (storeVersion != null && apkName != null && updateManager.isStoreVersionNewer(currentVersion, storeVersion)) {
                            val skipCount = updateManager.getSkipCount()
                            if (skipCount >= updateManager.MAX_SKIPS) {
                                updateManager.autoUpdate(storeVersion, apkName)
                            } else {
                                updateManager.showUpdateDialog(storeVersion, apkName, skipCount)
                            }
                        } else {
                            launchTargetApp()
                        }
                    }
                } else {
                    forceStopTargetApp()
                    showNotAllowedMessage()
                }
                isAuthorized = liberado
                startPeriodicAuthCheck() // Continuar checando
            }
        }.start()
    }

    fun launchTargetApp() {
        if (isAppInstalled(TARGET_PACKAGE)) {
            val launchIntent = packageManager.getLaunchIntentForPackage(TARGET_PACKAGE)
            if (launchIntent != null) {
                startActivity(launchIntent)
                Log.d(TAG, "Lançando app: $TARGET_PACKAGE")
            } else {
                Log.e(TAG, "Intent de lançamento não encontrado para $TARGET_PACKAGE")
            }
        } else {
            Log.e(TAG, "App $TARGET_PACKAGE não instalado")
            val installIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$TARGET_PACKAGE"))
            startActivity(installIntent)
        }
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun forceStopTargetApp() {
        try {
            val process = Runtime.getRuntime().exec("su")
            val output = DataOutputStream(process.outputStream)
            output.writeBytes("am force-stop $TARGET_PACKAGE\n")
            output.writeBytes("exit\n")
            output.flush()
            process.waitFor()
            Log.d(TAG, "Forçando parada do app: $TARGET_PACKAGE")
        } catch (e: IOException) {
            Log.e(TAG, "Erro ao forçar parada do app", e)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupção ao forçar parada do app", e)
        }
    }

    fun showNotAllowedMessage() {
        if (notAllowedDialog != null && notAllowedDialog!!.isShowing) {
            return // Já mostrando
        }
        val mac = getMacAddress()
        val customView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.parseColor("#333333")) // Fundo cinza escuro para o diálogo
            gravity = Gravity.CENTER

            addView(TextView(this@MainActivity).apply {
                text = "Dispositivo Não Liberado"
                textSize = 24f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 16)
            })

            addView(TextView(this@MainActivity).apply {
                text = "O dispositivo se encontra não liberado.\nMAC: $mac"
                textSize = 18f
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
            })
        }

        notAllowedDialog = AlertDialog.Builder(this)
            .setView(customView)
            .setCancelable(false)
            .create().apply {
                window?.setBackgroundDrawableResource(android.R.color.transparent) // Fundo transparente para o diálogo
            }
        notAllowedDialog?.show()
    }

    private fun getMacAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.name.equals("eth0", ignoreCase = true)) {
                    val mac = intf.hardwareAddress ?: return "unknown"
                    return mac.joinToString(":") { "%02x".format(it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao obter MAC address", e)
        }
        return "unknown"
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        notAllowedDialog?.dismiss()
    }

    // Helper para chamar launch em Main thread se necessário
    private suspend fun launchTargetAppOnMain() {
        withContext(Dispatchers.Main) {
            launchTargetApp()
        }
    }
}
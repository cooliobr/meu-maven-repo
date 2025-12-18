package br.tv.hallo.jptvmais.launcher

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.TimeUnit

class UpdateManager(private val ctx: MainActivity) {

    private val TAG = "UpdateManager"

    private val API_URL = "https://tv-cdn.jpinternet.com.br:65443/api_apk.php"
    private val APK_BASE_URL = "https://tv-cdn.jpinternet.com.br:65443/apks/"
    private val TARGET_PACKAGE = "br.tv.hallo.jptvmais"

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var isInstalling = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun checkAuthorization() {

        Thread {
            try {
                val mac = getEth0MacAddress()
                val request = Request.Builder()
                    .url("$API_URL?mac=$mac")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) return@Thread

                val json = JSONObject(response.body!!.string())

                val liberado = json.optBoolean("liberado", false)
                val apkName = json.optString("apk", null)

                val installed = getInstalledVersionCode()
                val store = apkName?.let { extractVersion(it) } ?: 0

                Log.d(TAG, "Installed=$installed Store=$store Liberado=$liberado")

                handler.post {

                    /* 🔴 BLOQUEIO SEMPRE PRIMEIRO */
                    if (!liberado) {
                        isInstalling = false
                        ctx.bringToFront()

                        handler.postDelayed({
                            ctx.killTargetApp()
                            ctx.blockDevice(mac)
                        }, 500)

                        return@post
                    }

                    /* ✅ LIBERADO */
                    ctx.unblockDevice()

                    if (apkName.isNullOrEmpty()) {
                        ctx.launchTargetApp()
                        return@post
                    }

                    if (store > installed && !isInstalling) {
                        isInstalling = true
                        downloadAndInstall(apkName)
                    } else {
                        ctx.launchTargetApp()
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Erro checkAuthorization", e)
            }
        }.start()
    }

    /* =====================
       VERSION
       ===================== */
    private fun getInstalledVersionCode(): Int {
        return try {
            val pi = ctx.packageManager.getPackageInfo(TARGET_PACKAGE, 0)
            pi.longVersionCode.toInt()
        } catch (e: PackageManager.NameNotFoundException) {
            0
        }
    }

    private fun extractVersion(apk: String): Int {
        val m = Regex("""(\d+)\.(\d+)""").find(apk) ?: return 0
        return m.groupValues[1].toInt() * 10 + m.groupValues[2].toInt()
    }

    /* =====================
       DOWNLOAD / INSTALL
       ===================== */
    private fun downloadAndInstall(apkName: String) {
        Thread {
            try {
                val response = client.newCall(
                    Request.Builder().url(APK_BASE_URL + apkName).build()
                ).execute()

                if (!response.isSuccessful) return@Thread

                val apkFile = File(ctx.filesDir, apkName)
                FileOutputStream(apkFile).use {
                    it.write(response.body!!.bytes())
                }

                handler.post { silentInstall(apkFile) }

            } catch (e: Exception) {
                isInstalling = false
            }
        }.start()
    }

    private fun silentInstall(apkFile: File) {
        try {
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL
            )
            params.setAppPackageName(TARGET_PACKAGE)

            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)

            apkFile.inputStream().use { input ->
                session.openWrite("base.apk", 0, apkFile.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }

            val pi = PendingIntent.getBroadcast(
                ctx, sessionId,
                Intent(ctx, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT
            )

            session.commit(pi.intentSender)
            session.close()

        } catch (e: Exception) {
            isInstalling = false
        }
    }

    /* =====================
       MAC ETH0
       ===================== */
    private fun getEth0MacAddress(): String {
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

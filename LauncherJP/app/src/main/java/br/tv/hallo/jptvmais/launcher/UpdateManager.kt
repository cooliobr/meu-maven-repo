// UpdateManager.kt (com delete do APK após instalação e verificação de MAC)
package br.tv.hallo.jptvmais.launcher

import android.app.ProgressDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.NetworkInterface
import java.util.Calendar
import java.util.Collections
import java.util.concurrent.TimeUnit

class UpdateManager(private val context: Context) {
    private val TAG = "UpdateManager"
    private val API_URL = "https://tv-cdn.jpinternet.com.br:65443/api_apk.php"
    private val APK_BASE_URL = "https://tv-cdn.jpinternet.com.br:65443/apks/"
    private val PACKAGE_NAME = "br.tv.hallo.jptvmais"
    private val SHARED_PREFS_NAME = "UpdatePrefs"
    val SKIP_COUNT_KEY = "skip_count" // Público
    val MAX_SKIPS = 3 // Público
    private val CHECK_INTERVAL_MS = 2 * 60 * 60 * 1000L // 2 horas
    private val handler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS) // Aumentado para downloads
        .build()
    private val sharedPrefs by lazy { context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE) }

    fun getSkipCount(): Int {
        return sharedPrefs.getInt(SKIP_COUNT_KEY, 0)
    }

    fun startPeriodicUpdateCheck() {
        // Não chame checkForUpdate() aqui para evitar duplicados; inicial é na activity
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val nextCheckHour = if (currentHour % 2 == 0) currentHour + 2 else currentHour + 1
        calendar.set(Calendar.HOUR_OF_DAY, nextCheckHour)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val delay = calendar.timeInMillis - System.currentTimeMillis()
        handler.postDelayed({ startPeriodicUpdateCheck() }, if (delay > 0) delay else CHECK_INTERVAL_MS)
    }

    fun checkForUpdate() {
        val currentVersion = getCurrentVersion() ?: return
        Log.d(TAG, "Verificando atualização, versão atual: $currentVersion")
        Thread {
            val (storeVersion, apkName, liberado) = getStoreVersionAndApk()
            handler.post {
                if (!liberado) {
                    Log.d(TAG, "Dispositivo não liberado")
                    (context as MainActivity).forceStopTargetApp()
                    (context as MainActivity).showNotAllowedMessage()
                } else if (storeVersion != null && apkName != null && isStoreVersionNewer(currentVersion, storeVersion)) {
                    val skipCount = getSkipCount()
                    if (skipCount >= MAX_SKIPS) {
                        autoUpdate(storeVersion, apkName) // Silencioso para forced
                    } else {
                        showUpdateDialog(storeVersion, apkName, skipCount)
                    }
                } else {
                    Log.d(TAG, "Nenhuma atualização disponível ou erro ao obter versão")
                    (context as MainActivity).launchTargetApp()
                }
            }
        }.start()
    }

    fun getCurrentVersion(): String? {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(PACKAGE_NAME, 0)
            packageInfo.versionName
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Erro ao obter versão atual", e)
            null
        }
    }

    fun getStoreVersionAndApk(): Triple<String?, String?, Boolean> {
        return try {
            val mac = getMacAddress()
            val url = "$API_URL?mac=$mac"
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return Triple(null, null, false)
                val jsonString = response.body?.string() ?: return Triple(null, null, false)
                val json = JSONObject(jsonString)
                if (json.has("package") && json.getString("package") == PACKAGE_NAME) {
                    val version = json.optString("version", null)
                    val apk = json.optString("apk", null)
                    val liberado = json.optBoolean("liberado", false)
                    Triple(version, apk, liberado)
                } else Triple(null, null, false)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Erro ao obter versão da API", e)
            Triple(null, null, false)
        } catch (e: Exception) {
            Log.e(TAG, "Erro inesperado ao processar resposta da API", e)
            Triple(null, null, false)
        }
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

    fun isStoreVersionNewer(currentVersion: String, storeVersion: String): Boolean {
        try {
            val currentParts = currentVersion.split(".").take(2).joinToString(".").split(".").map { it.toIntOrNull() ?: 0 }
            val storeParts = storeVersion.split(".").take(2).joinToString(".").split(".").map { it.toIntOrNull() ?: 0 }
            if (storeParts[0] > currentParts[0]) return true
            if (storeParts[0] < currentParts[0]) return false
            return storeParts.getOrNull(1) ?: 0 > currentParts.getOrNull(1) ?: 0
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao comparar versões", e)
            return false
        }
    }

    fun showUpdateDialog(storeVersion: String, apkName: String, skipCount: Int) {
        AlertDialog.Builder(context)
            .setTitle("Nova Versão Disponível")
            .setMessage("A versão $storeVersion está disponível. Deseja atualizar agora?")
            .setPositiveButton("Atualizar") { _, _ ->
                Log.d(TAG, "Usuário escolheu atualizar.")
                downloadAndInstallApk(apkName)
            }
            .setNegativeButton("Pular") { _, _ ->
                Log.d(TAG, "Usuário escolheu pular a atualização.")
                val newSkipCount = skipCount + 1
                sharedPrefs.edit().putInt(SKIP_COUNT_KEY, newSkipCount).apply()
                (context as MainActivity).launchTargetApp()
            }
            .setCancelable(false)
            .show()
    }

    fun forceUpdate(storeVersion: String, apkName: String) {
        AlertDialog.Builder(context)
            .setTitle("Atualização Obrigatória")
            .setMessage("A versão $storeVersion é obrigatória. Atualize agora.")
            .setPositiveButton("Atualizar") { _, _ ->
                Log.d(TAG, "Iniciando atualização forçada.")
                downloadAndInstallApk(apkName)
            }
            .setCancelable(false)
            .show()
    }

    fun autoUpdate(storeVersion: String, apkName: String) {
        downloadAndInstallApk(apkName) // Auto-inicia sem diálogo para forced
    }

    private fun downloadAndInstallApk(apkName: String) {
        val progressDialog = ProgressDialog(context).apply {
            setTitle("Atualizando")
            setMessage("Baixando atualização...")
            setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
            setCancelable(false)
            max = 100
            show()
        }
        Thread {
            try {
                val apkUrl = APK_BASE_URL + apkName
                val request = Request.Builder().url(apkUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Falha ao baixar APK: ${response.code}")
                    handler.post { progressDialog.dismiss() }
                    return@Thread
                }
                val body = response.body ?: throw IOException("Response body null")
                val contentLength = body.contentLength()
                val apksDir = File(context.filesDir, "apks")
                if (!apksDir.exists()) apksDir.mkdirs()
                val apkFile = File(apksDir, apkName)
                body.byteStream().use { input ->
                    FileOutputStream(apkFile).use { output ->
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        var totalBytes = 0L
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                            if (contentLength > 0) {
                                val progress = (totalBytes * 50 / contentLength).toInt() // 50% para download
                                handler.post { progressDialog.progress = progress }
                            }
                        }
                        output.flush()
                    }
                }
                Log.d(TAG, "APK baixado: ${apkFile.absolutePath}")
                handler.post {
                    progressDialog.setMessage("Instalando atualização...")
                    silentInstallApk(apkFile, progressDialog)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao baixar APK", e)
                handler.post { progressDialog.dismiss() }
            }
        }.start()
    }

    private fun silentInstallApk(apkFile: File, progressDialog: ProgressDialog) {
        Thread {
            try {
                // Instalação via root com pm install
                val process = Runtime.getRuntime().exec("su")
                val output = DataOutputStream(process.outputStream)
                output.writeBytes("pm install -r -d ${apkFile.absolutePath}\n") // -r: replace, -d: allow downgrade
                output.writeBytes("exit\n")
                output.flush()
                val exitValue = process.waitFor()
                if (exitValue == 0) {
                    Log.d(TAG, "Instalação silenciosa via pm install (root) concluída com sucesso")
                    // Apagar APK após sucesso
                    if (apkFile.exists() && apkFile.delete()) {
                        Log.d(TAG, "APK apagado com sucesso: ${apkFile.absolutePath}")
                    } else {
                        Log.w(TAG, "Falha ao apagar APK: ${apkFile.absolutePath}")
                    }
                    handler.post {
                        progressDialog.progress = 100
                        progressDialog.dismiss()
                        // Lançar app atualizado após instalação
                        (context as MainActivity).launchTargetApp()
                    }
                } else {
                    Log.e(TAG, "Falha na instalação via root: exitValue=$exitValue")
                    handler.post {
                        progressDialog.dismiss()
                        installApk(apkFile) // Fallback
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao instalar via root", e)
                handler.post {
                    progressDialog.dismiss()
                    installApk(apkFile) // Fallback
                }
            }
        }.start()
    }

    private fun installApk(apkFile: File) {
        val authority = "${context.packageName}.provider"
        val apkUri = FileProvider.getUriForFile(context, authority, apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        try {
            context.startActivity(intent)
            // Apagar APK após iniciar intent (fallback, assume sucesso)
            if (apkFile.exists() && apkFile.delete()) {
                Log.d(TAG, "APK apagado com sucesso (fallback): ${apkFile.absolutePath}")
            } else {
                Log.w(TAG, "Falha ao apagar APK (fallback): ${apkFile.absolutePath}")
            }
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "Nenhum instalador encontrado", e)
        }
    }
    // ... (o resto do código igual, como autoUpdate, etc.)
}
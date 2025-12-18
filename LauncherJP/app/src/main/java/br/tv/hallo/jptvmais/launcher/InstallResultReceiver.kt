package br.tv.hallo.jptvmais.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE
        )

        when (status) {

            PackageInstaller.STATUS_SUCCESS -> {
                Log.d("INSTALL", "Instalação OK")
            }

            PackageInstaller.STATUS_FAILURE,
            PackageInstaller.STATUS_FAILURE_ABORTED,
            PackageInstaller.STATUS_FAILURE_BLOCKED,
            PackageInstaller.STATUS_FAILURE_CONFLICT,
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
            PackageInstaller.STATUS_FAILURE_INVALID,
            PackageInstaller.STATUS_FAILURE_STORAGE -> {

                val msg = intent.getStringExtra(
                    PackageInstaller.EXTRA_STATUS_MESSAGE
                )

                Log.e("INSTALL", "Falha na instalação: $msg")
            }
        }

        /**
         * IMPORTANTE:
         * Não chamar UpdateManager aqui.
         * O próximo ciclo de checkAuthorization()
         * irá detectar a nova versão automaticamente.
         */
    }
}

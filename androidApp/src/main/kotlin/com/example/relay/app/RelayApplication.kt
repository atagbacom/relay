package com.relay.app

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enregistre la cause de tout plantage dans un fichier, pour l'afficher à la
 * réouverture : sur le terrain, on n'a ni câble ni adb pour lire les journaux.
 */
class RelayApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val precedent = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { fil, erreur ->
            runCatching { rapport(this).writeText(decrire(fil, erreur)) }
            precedent?.uncaughtException(fil, erreur)
        }
    }

    private fun decrire(fil: Thread, erreur: Throwable): String {
        val pile = StringWriter().also { erreur.printStackTrace(PrintWriter(it)) }
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull() ?: "?"
        return buildString {
            appendLine("Relay $version — plantage du ${SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date())}")
            appendLine("Appareil : ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Fil : ${fil.name}")
            appendLine()
            append(pile)
        }
    }

    companion object {
        fun rapport(app: Application): File = File(app.filesDir, "dernier-plantage.txt")
    }
}

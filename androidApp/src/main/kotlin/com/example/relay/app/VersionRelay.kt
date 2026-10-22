package com.relay.app

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Version installée, et partage de Relay de téléphone à téléphone.
 *
 * Hors Play Store, une nouvelle version passe de main en main : on envoie
 * l'APK installé par Xender, Quick Share ou Bluetooth. Android n'accepte la
 * mise à jour que si elle porte la même signature : un APK modifié en route
 * est refusé à l'installation.
 */
object VersionRelay {

    fun code(context: Context): Long =
        // longVersionCode n'existe qu'à partir d'Android 9 : passer par AndroidX
        androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(
            context.packageManager.getPackageInfo(context.packageName, 0)
        )

    fun nom(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"

    /** Copie l'APK installé et ouvre le menu de partage du téléphone. */
    fun partager(context: Context) {
        val source = File(context.applicationInfo.sourceDir)
        val dossier = File(context.cacheDir, "partage").apply { mkdirs() }
        dossier.listFiles()?.forEach { it.delete() }
        val copie = File(dossier, "relay-${nom(context)}.apk")
        source.copyTo(copie, overwrite = true)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.partage", copie)
        val envoi = Intent(Intent.ACTION_SEND)
            .setType("application/vnd.android.package-archive")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(
            Intent.createChooser(envoi, "Partager Relay").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

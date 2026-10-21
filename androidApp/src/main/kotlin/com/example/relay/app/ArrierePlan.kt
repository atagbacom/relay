package com.relay.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Le relais doit tourner écran éteint. Beaucoup de téléphones (Infinix,
 * Tecno, Xiaomi…) coupent les applications en arrière-plan pour économiser
 * la batterie : le maillage s'arrêtait sans prévenir.
 */
object ArrierePlan {

    fun estLibre(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Demande au téléphone de ne plus couper Relay pour économiser la batterie. */
    @SuppressLint("BatteryLife") // relayer en arrière-plan est la fonction même de l'application
    fun demanderLiberte(context: Context) {
        val demande = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        runCatching { context.startActivity(demande) }
            .recoverCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    /**
     * Certaines marques ajoutent leur propre « démarrage automatique », qu'aucune
     * application ne peut activer seule : on ouvre la fiche de Relay.
     */
    fun ouvrirFicheApplication(context: Context) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }
    }
}

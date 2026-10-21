package com.relay.app.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.relay.app.autorisationsReseau

/**
 * Relance le relais après un redémarrage du téléphone ou une mise à jour de
 * l'application. Avant, il fallait rouvrir Relay pour que le maillage reprenne.
 * Sans les autorisations Bluetooth, on ne fait rien : l'accueil les demandera.
 */
class Demarrage : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val autorise = autorisationsReseau().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (!autorise) return
        runCatching { ContextCompat.startForegroundService(context, Intent(context, MeshService::class.java)) }
    }
}

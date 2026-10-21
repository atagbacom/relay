package com.relay.app.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.relay.app.model.RelayPacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Surveille la batterie et ajuste le comportement mesh.
 *
 * Trois modes :
 * FULL    — mesh complet, advertising + scan + relais actifs
 * LOW     — scan moins fréquent, relais limités
 * CRITICAL — relais silencieux uniquement, interface suspendue
 *
 * C'est la solution au problème identifié dans la recherche :
 * un outil d'urgence qui vide la batterie est inutilisable
 * exactement quand on en a le plus besoin.
 */
class BatteryMonitor(private val context: Context) {

    enum class BatteryMode { FULL, LOW, CRITICAL }

    /** Choix de l'utilisateur : selon la batterie, ou économie imposée. */
    enum class Choix { AUTO, ECONOMIE, ECONOMIE_MAX }

    companion object {
        const val THRESHOLD_LOW = 30       // % — passe en mode LOW
        const val THRESHOLD_CRITICAL = 15  // % — passe en mode CRITICAL
        private const val TAG = "BatteryMonitor"
    }

    private val _mode = MutableStateFlow(BatteryMode.FULL)
    val mode: StateFlow<BatteryMode> = _mode

    private val _level = MutableStateFlow(100)
    val level: StateFlow<Int> = _level

    private var choix = Choix.AUTO

    fun choisir(c: Choix) {
        choix = c
        recalculer()
    }

    /** Mode appliqué : celui de la batterie, jamais moins économe que le choix de l'utilisateur. */
    private fun recalculer() {
        val percent = _level.value
        val selonBatterie = when {
            percent <= THRESHOLD_CRITICAL -> BatteryMode.CRITICAL
            percent <= THRESHOLD_LOW      -> BatteryMode.LOW
            else                          -> BatteryMode.FULL
        }
        val impose = when (choix) {
            Choix.AUTO -> BatteryMode.FULL
            Choix.ECONOMIE -> BatteryMode.LOW
            Choix.ECONOMIE_MAX -> BatteryMode.CRITICAL
        }
        val newMode = maxOf(selonBatterie, impose)
        if (newMode != _mode.value) {
            Log.d(TAG, "Mode batterie : ${_mode.value} → $newMode ($percent%, choix $choix)")
            _mode.value = newMode
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level == -1 || scale == -1) return

            _level.value = (level * 100 / scale.toFloat()).toInt()
            recalculer()
        }
    }

    fun start() {
        context.registerReceiver(
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
    }

    fun stop() {
        try { context.unregisterReceiver(receiver) }
        catch (e: Exception) { /* déjà désenregistré */ }
    }

    /**
     * Intensité du scan selon le mode. Avant, une valeur était calculée ici
     * mais jamais utilisée : la batterie ne changeait rien au Bluetooth.
     */
    fun modeScan(): Int = when (_mode.value) {
        BatteryMode.FULL     -> android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY
        BatteryMode.LOW      -> android.bluetooth.le.ScanSettings.SCAN_MODE_BALANCED
        BatteryMode.CRITICAL -> android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER
    }

    /** Fréquence d'annonce selon le mode : plus rare, on est découvert un peu moins vite. */
    fun modeAnnonce(): Int = when (_mode.value) {
        BatteryMode.FULL     -> android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
        BatteryMode.LOW      -> android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_BALANCED
        BatteryMode.CRITICAL -> android.bluetooth.le.AdvertiseSettings.ADVERTISE_MODE_LOW_POWER
    }

    /**
     * En mode critique, on ne relaie que les messages prioritaires.
     */
    fun shouldRelay(packet: RelayPacket): Boolean {
        return when (_mode.value) {
            BatteryMode.FULL     -> true
            BatteryMode.LOW      -> true
            // Accusés et alertes toujours ; le reste seulement près de sa source
            BatteryMode.CRITICAL -> packet.type == com.relay.app.model.PacketType.ACK ||
                packet.ttl >= RelayPacket.MAX_HOPS - 1
        }
    }
}
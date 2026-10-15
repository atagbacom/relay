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

    companion object {
        const val THRESHOLD_LOW = 30       // % — passe en mode LOW
        const val THRESHOLD_CRITICAL = 15  // % — passe en mode CRITICAL
        private const val TAG = "BatteryMonitor"
    }

    private val _mode = MutableStateFlow(BatteryMode.FULL)
    val mode: StateFlow<BatteryMode> = _mode

    private val _level = MutableStateFlow(100)
    val level: StateFlow<Int> = _level

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level == -1 || scale == -1) return

            val percent = (level * 100 / scale.toFloat()).toInt()
            _level.value = percent

            val newMode = when {
                percent <= THRESHOLD_CRITICAL -> BatteryMode.CRITICAL
                percent <= THRESHOLD_LOW      -> BatteryMode.LOW
                else                          -> BatteryMode.FULL
            }

            if (newMode != _mode.value) {
                Log.d(TAG, "Mode batterie : ${_mode.value} → $newMode ($percent%)")
                _mode.value = newMode
            }
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
     * Fréquence de scan BLE selon le mode batterie.
     * En mode critique, on scanne moins souvent pour économiser.
     */
    fun scanIntervalMs(): Long = when (_mode.value) {
        BatteryMode.FULL     -> 0L       // Continu
        BatteryMode.LOW      -> 5_000L   // Toutes les 5 secondes
        BatteryMode.CRITICAL -> 30_000L  // Toutes les 30 secondes
    }

    /**
     * En mode critique, on ne relaie que les messages prioritaires.
     */
    fun shouldRelay(packet: RelayPacket): Boolean {
        return when (_mode.value) {
            BatteryMode.FULL     -> true
            BatteryMode.LOW      -> true
            BatteryMode.CRITICAL -> packet.ttl >= RelayPacket.MAX_HOPS - 1
        }
    }
}
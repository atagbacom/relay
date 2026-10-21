package com.relay.app.mesh

import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log

/**
 * Scan BLE passif — détecte les nœuds Relay à portée.
 *
 * Filtre sur l'UUID de service Relay pour ignorer
 * tous les autres appareils Bluetooth de l'environnement.
 */
class BleScanner(private val context: Context) {

    companion object {
        private const val TAG = "RelayScanner"
    }

    private var scanner: BluetoothLeScanner? = null
    private val identifiantsParAdresse = java.util.concurrent.ConcurrentHashMap<String, String>()

    // Callback appelé pour chaque pair découvert
    var onPeerDiscovered: ((address: String, shortId: String, rssi: Int) -> Unit)? = null


    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address
            val rssi = result.rssi
            // Extrait le shortId depuis le manufacturer data
            val manufacturerData = result.scanRecord
                ?.getManufacturerSpecificData(0x1234)
            EtatMesh.annoncesRecues.value += 1
            // L'identifiant n'est que dans la réponse au scan, qui n'accompagne
            // qu'une partie des annonces : on retient l'adresse pour reconnaître
            // l'appareil à toutes ses annonces (avant : vu une fois, puis perdu)
            val shortId = manufacturerData?.let { String(it, Charsets.UTF_8) }
                ?.also { identifiantsParAdresse[address] = it }
                ?: identifiantsParAdresse[address]
                ?: return

            Log.d(TAG, "Pair découvert : $shortId @ $address")
            onPeerDiscovered?.invoke(address, shortId, rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            val raison = EtatMesh.echecScan(errorCode)
            EtatMesh.scan.value = "échec : $raison"
            EtatMesh.noter("Scan Bluetooth impossible : $raison")
        }
    }

    /** Intensité du scan (ScanSettings), réglée par le mode batterie. */
    var modeScan: Int = ScanSettings.SCAN_MODE_LOW_LATENCY

    fun start() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager
        // Pas d'adaptateur (appareil sans Bluetooth) ou Bluetooth éteint : rien à faire
        val adaptateur = manager.adapter ?: return
        if (!adaptateur.isEnabled) return
        scanner = adaptateur.bluetoothLeScanner

        // Filtre — uniquement les appareils Relay
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleAdvertiser.RELAY_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(modeScan)
            .build()

        val s = scanner
        if (s == null) {
            EtatMesh.scan.value = "échec : scanner Bluetooth indisponible"
            return
        }
        runCatching { s.stopScan(scanCallback) }
        s.startScan(listOf(filter), settings, scanCallback)
        EtatMesh.scan.value = "actif"
        EtatMesh.noter("Scan Bluetooth actif")
    }

    fun stop() {
        runCatching { scanner?.stopScan(scanCallback) }
        EtatMesh.scan.value = "arrêté"
    }
}
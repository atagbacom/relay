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

    // Callback appelé pour chaque pair découvert
    var onPeerDiscovered: ((address: String, shortId: String) -> Unit)? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address

            // Extrait le shortId depuis le manufacturer data
            val manufacturerData = result.scanRecord
                ?.getManufacturerSpecificData(0x1234)
            val shortId = manufacturerData
                ?.let { String(it, Charsets.UTF_8) }
                ?: return

            Log.d(TAG, "Pair découvert : $shortId @ $address")
            onPeerDiscovered?.invoke(address, shortId)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan échoué : $errorCode")
        }
    }

    fun start() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager
        scanner = manager.adapter.bluetoothLeScanner

        // Filtre — uniquement les appareils Relay
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BleAdvertiser.RELAY_SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner?.startScan(listOf(filter), settings, scanCallback)
        Log.d(TAG, "Scan démarré")
    }

    fun stop() {
        scanner?.stopScan(scanCallback)
    }
}
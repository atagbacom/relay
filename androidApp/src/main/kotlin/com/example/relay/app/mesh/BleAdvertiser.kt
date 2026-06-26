package com.relay.app.mesh

import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import java.util.UUID

/**
 * Diffuse notre présence en BLE advertising.
 *
 * Tous les téléphones Relay à portée nous détectent
 * sans aucun appariement Bluetooth requis.
 * Le payload contient notre shortId pour identification immédiate.
 */
class BleAdvertiser(private val context: Context) {

    companion object {
        // UUID de service Relay — identifie l'app dans les beacons BLE
        // Généré une fois, fixe pour toute l'app
        val RELAY_SERVICE_UUID: UUID =
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

        private const val TAG = "RelayBLE"
    }

    private var advertiser: BluetoothLeAdvertiser? = null
    private var isAdvertising = false

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "Advertising démarré")
            isAdvertising = true
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising échoué : $errorCode")
            isAdvertising = false
        }
    }

    /**
     * Démarre le beacon BLE avec notre shortId dans le payload.
     *
     * @param shortId Notre identifiant court (8 chars hex)
     */
    fun start(shortId: String) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager
        advertiser = manager.adapter.bluetoothLeAdvertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)      // Les pairs peuvent se connecter
            .setTimeout(0)             // Advertising continu
            .build()

        // Encode shortId dans le manufacturer data (2 bytes company ID + payload)
        val shortIdBytes = shortId.toByteArray(Charsets.UTF_8)

        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(RELAY_SERVICE_UUID))
            .addManufacturerData(0x1234, shortIdBytes)
            .setIncludeDeviceName(false)   // Pas de nom — confidentialité
            .build()

        advertiser?.startAdvertising(settings, data, callback)
    }

    fun stop() {
        if (isAdvertising) {
            advertiser?.stopAdvertising(callback)
            isAdvertising = false
        }
    }
}
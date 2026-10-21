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
            isAdvertising = true
            EtatMesh.annonce.value = "active"
            EtatMesh.noter("Annonce Bluetooth active")
        }
        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            val raison = EtatMesh.echecAnnonce(errorCode)
            EtatMesh.annonce.value = "échec : $raison"
            EtatMesh.noter("Annonce Bluetooth impossible : $raison")
        }
    }

    /**
     * Démarre le beacon BLE avec notre shortId dans le payload.
     *
     * @param shortId Notre identifiant court (8 chars hex)
     */
    /** Fréquence d'annonce (AdvertiseSettings), réglée par le mode batterie. */
    var modeAnnonce: Int = AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY

    fun start(shortId: String) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager
        // Pas d'adaptateur (appareil sans Bluetooth) ou Bluetooth éteint : rien à faire
        val adaptateur = manager.adapter ?: return
        if (!adaptateur.isEnabled) return
        advertiser = adaptateur.bluetoothLeAdvertiser
        if (advertiser == null) {
            EtatMesh.annonce.value = "échec : ce téléphone ne sait pas émettre d'annonce Bluetooth"
            EtatMesh.noter("Pas d'émetteur d'annonce BLE sur ce téléphone")
            return
        }
        stop()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(modeAnnonce)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)      // Les pairs peuvent se connecter
            .setTimeout(0)             // Advertising continu
            .build()

        // Encode shortId dans le manufacturer data (2 bytes company ID + payload)
        val shortIdBytes = shortId.toByteArray(Charsets.UTF_8)

        // Une annonce BLE tient en 31 octets : l'UUID 128 bits (18) et les
        // drapeaux (3) les occupent presque. L'identifiant court part dans la
        // réponse au scan, lue par les scanners actifs avec l'annonce.
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(RELAY_SERVICE_UUID))
            .setIncludeDeviceName(false)   // Pas de nom — confidentialité
            .build()
        val reponse = AdvertiseData.Builder()
            .addManufacturerData(0x1234, shortIdBytes)
            .setIncludeDeviceName(false)
            .build()

        advertiser?.startAdvertising(settings, data, reponse, callback)
    }

    fun stop() {
        if (isAdvertising) {
            runCatching { advertiser?.stopAdvertising(callback) }
            isAdvertising = false
            EtatMesh.annonce.value = "arrêtée"
        }
    }
}
package com.relay.app.mesh

import android.bluetooth.*
import android.content.Context
import android.util.Log
import java.util.UUID

/**
 * Serveur GATT — reçoit les paquets des pairs qui se connectent.
 *
 * Chaque téléphone est simultanément serveur (reçoit)
 * et client (envoie). C'est la base du mesh symétrique.
 *
 * Architecture GATT Relay :
 * Service UUID     : RELAY_SERVICE_UUID
 * Caractéristique  : RELAY_PACKET_CHAR_UUID (write + notify)
 */
class BleGattServer(private val context: Context) {

    companion object {
        val RELAY_PACKET_CHAR_UUID: UUID =
            UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        private const val TAG = "RelayGattServer"
        // BLE MTU max — les paquets plus grands sont fragmentés
        const val MTU_SIZE = 512
    }

    private var gattServer: BluetoothGattServer? = null

    // Buffer d'assemblage pour les paquets fragmentés
    private val reassembleur = Reassembleur()

    // Callback quand un paquet complet est reçu
    var onPacketReceived: ((ByteArray) -> Unit)? = null

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED ->
                    Log.d(TAG, "Pair connecté : ${device.address}")
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Pair déconnecté : ${device.address}")
                    reassembleur.oublier(device.address)
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid != RELAY_PACKET_CHAR_UUID) {
                // Répondre quand même, sinon le client attend jusqu'à son délai maximal
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
                return
            }

            // Rassemble les fragments jusqu'à la taille annoncée par l'en-tête
            reassembleur.ajouter(device.address, value)?.let { paquet ->
                onPacketReceived?.invoke(paquet)
            }

            if (responseNeeded) {
                gattServer?.sendResponse(
                    device, requestId,
                    BluetoothGatt.GATT_SUCCESS, 0, null
                )
            }
        }
    }

    fun start() {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager
        stop()
        gattServer = manager.openGattServer(context, serverCallback)
        if (gattServer == null) {
            EtatMesh.serveur.value = "échec : serveur GATT indisponible"
            EtatMesh.noter("Serveur GATT impossible à ouvrir")
            return
        }

        val service = BluetoothGattService(
            BleAdvertiser.RELAY_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val packetChar = BluetoothGattCharacteristic(
            RELAY_PACKET_CHAR_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        service.addCharacteristic(packetChar)
        gattServer?.addService(service)
        EtatMesh.serveur.value = "actif"
        EtatMesh.noter("Serveur GATT actif")
    }

    fun stop() {
        runCatching { gattServer?.close() }
        gattServer = null
        EtatMesh.serveur.value = "arrêté"
    }
}
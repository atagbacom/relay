package com.relay.app.mesh

import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.relay.app.model.PacketCodec
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
    private val fragmentBuffers = mutableMapOf<String, ByteArray>()

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
                    fragmentBuffers.remove(device.address)
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
            if (characteristic.uuid != RELAY_PACKET_CHAR_UUID) return

            // Accumule les fragments jusqu'à la taille annoncée par l'en-tête
            val existing = fragmentBuffers[device.address] ?: ByteArray(0)
            val combined = existing + value
            fragmentBuffers[device.address] = combined

            if (combined.size >= PacketCodec.TAILLE_EN_TETE) {
                val attendue = PacketCodec.tailleAnnoncee(combined)
                when {
                    // En-tête invalide ou paquet plus long qu'annoncé : on jette
                    attendue == null || combined.size > attendue -> {
                        Log.w(TAG, "Fragments rejetés de ${device.address}")
                        fragmentBuffers.remove(device.address)
                    }
                    combined.size == attendue -> {
                        onPacketReceived?.invoke(combined)
                        fragmentBuffers.remove(device.address)
                    }
                }
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
        gattServer = manager.openGattServer(context, serverCallback)

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
        Log.d(TAG, "Serveur GATT démarré")
    }

    fun stop() {
        gattServer?.close()
    }
}
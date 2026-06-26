package com.relay.app.mesh

import android.bluetooth.*
import android.content.Context
import android.util.Log

/**
 * Client GATT — envoie un paquet vers un pair identifié
 * par son adresse MAC BLE.
 *
 * Gère automatiquement la fragmentation si le paquet
 * dépasse la MTU BLE (512 bytes).
 */
class BleGattClient(
    private val context: Context,
    private val targetAddress: String
) {
    companion object {
        private const val TAG = "RelayGattClient"
    }

    private var gatt: BluetoothGatt? = null
    var onSendComplete: ((success: Boolean) -> Unit)? = null

    private var pendingPayload: ByteArray? = null

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(TAG, "Connecté à $targetAddress")
                // Négocie la MTU maximale
                gatt.requestMtu(BleGattServer.MTU_SIZE)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Déconnecté de $targetAddress")
                gatt.close()
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // MTU négociée — on peut découvrir les services
                gatt.discoverServices()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                onSendComplete?.invoke(false)
                return
            }

            // Envoie le payload fragmenté
            pendingPayload?.let { payload ->
                sendFragmented(gatt, payload)
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Fragment envoyé")
            }
        }
    }

    /**
     * Envoie un paquet sérialisé vers le pair cible.
     */
    fun send(packetBytes: ByteArray) {
        pendingPayload = packetBytes
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager
        val device = manager.adapter.getRemoteDevice(targetAddress)
        gatt = device.connectGatt(context, false, gattCallback,
            BluetoothDevice.TRANSPORT_LE)
    }

    private fun sendFragmented(gatt: BluetoothGatt, payload: ByteArray) {
        val service = gatt.getService(BleAdvertiser.RELAY_SERVICE_UUID)
        val char = service?.getCharacteristic(BleGattServer.RELAY_PACKET_CHAR_UUID)
            ?: run { onSendComplete?.invoke(false); return }

        // Fragmente en chunks de MTU_SIZE
        val chunks = payload.toList().chunked(BleGattServer.MTU_SIZE)
            .map { it.toByteArray() }

        // Envoie séquentiellement
        // Sprint 4 : on gérera la file d'attente proprement
        chunks.forEach { chunk ->
            char.value = chunk
            gatt.writeCharacteristic(char)
            Thread.sleep(50) // Délai minimal entre fragments
        }

        onSendComplete?.invoke(true)
        gatt.disconnect()
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
    }
}
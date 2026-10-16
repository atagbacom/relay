package com.relay.app.mesh

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Client GATT — envoie un paquet vers un pair identifié
 * par son adresse MAC BLE.
 *
 * Le paquet est découpé selon la MTU réellement obtenue, puis envoyé
 * morceau par morceau : Android n'accepte qu'une écriture GATT à la fois,
 * on attend donc la confirmation de chaque morceau avant le suivant.
 */
@SuppressLint("MissingPermission") // permissions Bluetooth demandées au lancement (MainActivity)
class BleGattClient(
    private val context: Context,
    private val targetAddress: String
) {
    companion object {
        private const val TAG = "RelayGattClient"
        /** MTU demandée : valeur maximale de 512 octets + en-tête ATT. */
        private const val MTU_DEMANDEE = Fragmentation.VALEUR_MAX + 5
        /** Au-delà, l'envoi est abandonné (pair parti, radio bloquée…). */
        private const val DELAI_MAX_MS = 15_000L
    }

    private var gatt: BluetoothGatt? = null
    var onSendComplete: ((success: Boolean) -> Unit)? = null

    private var pendingPayload: ByteArray? = null
    private var tailleMorceau = Fragmentation.tailleMorceau(Fragmentation.MTU_MIN)
    private val file = ArrayDeque<ByteArray>()
    private var caracteristique: BluetoothGattCharacteristic? = null
    private var termine = false

    private val minuteur = Handler(Looper.getMainLooper())
    private val abandon = Runnable {
        Log.w(TAG, "Délai dépassé vers $targetAddress")
        terminer(false)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Connecté à $targetAddress")
                if (!gatt.requestMtu(MTU_DEMANDEE)) gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(TAG, "Déconnecté de $targetAddress")
                gatt.close()
                terminer(false)  // sans effet si l'envoi était déjà terminé
            } else {
                terminer(false)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            // MTU refusée : on garde la taille minimale garantie (20 octets utiles)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                tailleMorceau = Fragmentation.tailleMorceau(mtu)
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val char = gatt.getService(BleAdvertiser.RELAY_SERVICE_UUID)
                ?.getCharacteristic(BleGattServer.RELAY_PACKET_CHAR_UUID)
            val payload = pendingPayload
            if (status != BluetoothGatt.GATT_SUCCESS || char == null || payload == null) {
                terminer(false)
                return
            }
            caracteristique = char
            file.clear()
            file.addAll(Fragmentation.decouper(payload, tailleMorceau))
            envoyerSuivant(gatt)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                envoyerSuivant(gatt)
            } else {
                Log.w(TAG, "Écriture refusée par $targetAddress (statut $status)")
                terminer(false)
            }
        }
    }

    /**
     * Envoie un paquet sérialisé vers le pair cible.
     */
    fun send(packetBytes: ByteArray) {
        pendingPayload = packetBytes
        minuteur.postDelayed(abandon, DELAI_MAX_MS)
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE)
                as BluetoothManager
        val device = manager.adapter.getRemoteDevice(targetAddress)
        gatt = device.connectGatt(context, false, gattCallback,
            BluetoothDevice.TRANSPORT_LE)
    }

    /** Envoie le morceau suivant, ou termine si tout est parti. */
    private fun envoyerSuivant(gatt: BluetoothGatt) {
        val char = caracteristique ?: return terminer(false)
        val morceau = file.removeFirstOrNull() ?: return terminer(true)
        if (!ecrire(gatt, char, morceau)) {
            Log.w(TAG, "Écriture impossible vers $targetAddress")
            terminer(false)
        }
    }

    private fun ecrire(
        gatt: BluetoothGatt,
        char: BluetoothGattCharacteristic,
        valeur: ByteArray
    ): Boolean {
        val type = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT  // avec confirmation
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(char, valeur, type) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                char.writeType = type
                char.value = valeur
                gatt.writeCharacteristic(char)
            }
        }
    }

    @Synchronized
    private fun terminer(succes: Boolean) {
        if (termine) return
        termine = true
        minuteur.removeCallbacks(abandon)
        onSendComplete?.invoke(succes)
        gatt?.disconnect()
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
    }
}

package com.relay.app.mesh

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.relay.app.crypto.*
import com.relay.app.model.RelayPacket
import com.relay.app.model.PacketType
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * Service foreground — tourne en arrière-plan même écran éteint.
 *
 * Orchestre :
 * - BLE advertising (notre présence)
 * - BLE scanning (découverte des pairs)
 * - GATT server (réception)
 * - GATT clients (envoi)
 * - Routeur mesh
 * - Couche crypto
 */
class MeshService : Service() {

    companion object {
        const val CHANNEL_ID = "relay_mesh"
        const val NOTIF_ID = 1
        private const val TAG = "MeshService"

        // Intent actions
        const val ACTION_SEND = "com.relay.app.SEND"
        const val EXTRA_PACKET = "packet"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var advertiser: BleAdvertiser
    private lateinit var scanner: BleScanner
    private lateinit var gattServer: BleGattServer
    private lateinit var router: MeshRouter

    // Table des pairs découverts : shortId → adresse MAC
    private val peers = mutableMapOf<String, String>()

    // Notre identité — chargée depuis Room au Sprint 4
    // Pour l'instant générée à chaque démarrage du service
    private val localIdentity = RelayIdentity.generate()
    private val localShortId = RelayIdentity.shortId(localIdentity)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())

        router = MeshRouter(localShortId)
        advertiser = BleAdvertiser(this)
        scanner = BleScanner(this)
        gattServer = BleGattServer(this)

        setupRouter()
        setupScanner()
        setupGattServer()

        advertiser.start(localShortId)
        scanner.start()
        gattServer.start()

        Log.d(TAG, "MeshService démarré — identité : $localShortId")
    }

    private fun setupRouter() {
        router.onMessageForUs = { packet ->
            Log.d(TAG, "Message reçu pour nous depuis ${packet.senderId}")
            // Sprint 4 : on livre à l'UI via LiveData/Flow
        }

        router.onPacketToRelay = { packet ->
            scope.launch { relayToAllPeers(packet) }
        }
    }

    private fun setupScanner() {
        scanner.onPeerDiscovered = { address, shortId ->
            if (shortId != localShortId && !peers.containsKey(shortId)) {
                peers[shortId] = address
                Log.d(TAG, "Nouveau pair : $shortId @ $address")

                // Dès qu'on découvre un pair, on lui envoie nos prekeys
                // pour qu'il puisse nous contacter même si on disparaît
                scope.launch { sendPreKeyGossipTo(address) }
            }
        }
    }

    private fun setupGattServer() {
        gattServer.onPacketReceived = { bytes ->
            scope.launch {
                val packet = deserializePacket(bytes) ?: return@launch
                router.handle(packet)
            }
        }
    }

    /**
     * Envoie nos prekeys à un pair nouvellement découvert.
     */
    private suspend fun sendPreKeyGossipTo(peerAddress: String) {
        withContext(Dispatchers.IO) {
            val session = RelaySession(localIdentity, localShortId.hashCode())
            val bundle = session.createPreKeyBundle()
            val gossip = PreKeyGossip(localIdentity)
            val packet = gossip.createGossipPacket(localShortId, bundle)
            sendPacketTo(peerAddress, packet)
        }
    }

    /**
     * Relaie un paquet à tous les pairs connus.
     */
    private suspend fun relayToAllPeers(packet: RelayPacket) {
        withContext(Dispatchers.IO) {
            peers.values.forEach { address ->
                sendPacketTo(address, packet)
            }
        }
    }

    /**
     * Envoie un paquet à une adresse MAC précise via GATT.
     */
    private fun sendPacketTo(address: String, packet: RelayPacket) {
        val bytes = serializePacket(packet) ?: return
        val client = BleGattClient(this, address)
        client.onSendComplete = { success ->
            Log.d(TAG, "Envoi vers $address : $success")
            client.disconnect()
        }
        client.send(bytes)
    }

    // Sérialisation temporaire — Sprint 4 : Protocol Buffers
    private fun serializePacket(packet: RelayPacket): ByteArray? {
        return try {
            val bos = ByteArrayOutputStream()
            ObjectOutputStream(bos).use { it.writeObject(packet) }
            bos.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Erreur sérialisation : ${e.message}")
            null
        }
    }

    private fun deserializePacket(bytes: ByteArray): RelayPacket? {
        return try {
            val bis = ByteArrayInputStream(bytes)
            ObjectInputStream(bis).use { it.readObject() as RelayPacket }
        } catch (e: Exception) {
            Log.e(TAG, "Erreur désérialisation : ${e.message}")
            null
        }
    }

    // Notification foreground obligatoire Android 8+
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Relay Mesh",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Réseau mesh actif" }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Relay")
            .setContentText("Réseau mesh actif — $localShortId")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY  // Redémarre si le système tue le service
    }

    override fun onDestroy() {
        super.onDestroy()
        advertiser.stop()
        scanner.stop()
        gattServer.stop()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
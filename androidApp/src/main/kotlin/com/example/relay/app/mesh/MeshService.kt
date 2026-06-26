package com.relay.app.mesh

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.relay.app.crypto.*
import com.relay.app.model.*
import com.relay.app.repository.RelayRepository
import com.relay.app.storage.MessageEntity
import com.relay.app.storage.ContactEntity
import kotlinx.coroutines.*
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import java.io.*
import java.util.UUID

class MeshService : Service() {

    companion object {
        const val CHANNEL_ID = "relay_mesh"
        const val NOTIF_ID = 1
        const val ACTION_SEND = "com.relay.app.SEND"
        const val EXTRA_RECIPIENT_ID = "recipient_id"
        const val EXTRA_MESSAGE = "message"
        private const val TAG = "MeshService"

        // Broadcast vers l'UI quand un message arrive
        const val ACTION_MESSAGE_RECEIVED = "com.relay.app.MESSAGE_RECEIVED"
        const val EXTRA_SENDER_ID = "sender_id"
        const val EXTRA_PLAINTEXT = "plaintext"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var repo: RelayRepository
    private lateinit var advertiser: BleAdvertiser
    private lateinit var scanner: BleScanner
    private lateinit var gattServer: BleGattServer
    private lateinit var router: MeshRouter
    private lateinit var gossip: PreKeyGossip
    private lateinit var deferred: DeferredSession
    private lateinit var nodeTracker: NodeTracker
    private lateinit var batteryMonitor: BatteryMonitor
    private lateinit var sprayAndWait: SprayAndWait

    private val peers = mutableMapOf<String, String>() // shortId → MAC

    // Identité chargée depuis Room — persistante entre redémarrages
    private var localIdentity =
        org.signal.libsignal.protocol.IdentityKeyPair(ByteArray(0))
    private var localShortId = ""


    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Démarrage…"))

        repo = RelayRepository(this)


        scope.launch {
            // Charge ou crée l'identité persistante
            localIdentity = repo.getOrCreateIdentity()
            localShortId = repo.getShortId()

            gossip = PreKeyGossip(localIdentity)
            deferred = DeferredSession(localIdentity)

            nodeTracker = NodeTracker()
            batteryMonitor = BatteryMonitor(this@MeshService)
            batteryMonitor.start()


            router = MeshRouter(localShortId, nodeTracker, batteryMonitor)
            advertiser = BleAdvertiser(this@MeshService)
            scanner = BleScanner(this@MeshService)
            gattServer = BleGattServer(this@MeshService)


            setupRouter()
            setupScanner()
            setupGattServer()

            advertiser.start(localShortId)
            scanner.start()
            gattServer.start()

            updateNotification("Mesh actif — $localShortId")
            Log.d(TAG, "MeshService prêt — $localShortId")
            scope.launch {
                while (isActive) {
                    delay(600_000)
                    nodeTracker.prune()
                }
            }
        }
    }

    // ─── Setup callbacks ─────────────────────────────────────

    private fun setupRouter() {
        router.onMessageForUs = { packet ->
            scope.launch { handleIncomingPacket(packet) }
        }

        // Maintenant onPacketToRelay reçoit une liste d'adresses cibles
        router.onPacketToRelay = { packet, targetAddresses ->
            scope.launch {
                targetAddresses.forEach { address ->
                    sendPacketTo(address, packet)
                }
            }
        }

        router.onPreKeyReceived = { senderId, bundleBytes ->
            scope.launch {
                repo.upgradeTrust(senderId, TrustLevel.YELLOW)
                nodeTracker.markPreKeyExchanged(senderId)
            }
        }

        router.onAckReceived = { messageId ->
            scope.launch { repo.markDelivered(messageId) }
        }
    }
    private fun setupScanner() {
        scanner.onPeerDiscovered = { address, shortId, rssi ->
            if (shortId != localShortId) {
                val isNew = !nodeTracker.getActiveNodes()
                    .any { it.shortId == shortId }
                nodeTracker.seen(shortId, address, rssi)

                if (isNew) {
                    Log.d(TAG, "Nouveau pair : $shortId")
                    router.onNewPeerAvailable(shortId, address)
                    scope.launch { sendOurPreKeys(address) }
                    updateNotification(
                        "Mesh actif — ${nodeTracker.activeCount()} pairs"
                    )
                }
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

    // ─── Envoi d'un message ──────────────────────────────────

    /**
     * Appelé depuis l'UI via startService(intent).
     * Chiffre le message et l'injecte dans le mesh.
     */
    private fun sendMessage(recipientId: String, plaintext: String) {
        scope.launch {
            try {
                val store = repo.getOrCreateStore(
                    localIdentity,
                    localShortId.hashCode()
                )
                val recipientAddress = SignalProtocolAddress(recipientId, 1)
                val cipher = SessionCipher(store, recipientAddress)

                // Chiffre avec Double Ratchet
                val encrypted = RelayMessage.encrypt(cipher, plaintext)

                val packet = RelayPacket(
                    id = UUID.randomUUID().toString(),
                    type = PacketType.MESSAGE,
                    senderId = localShortId,
                    recipientId = recipientId,
                    payload = serializeEncrypted(encrypted),
                    signature = signPayload(serializeEncrypted(encrypted)),
                    ttl = RelayPacket.MAX_HOPS
                )

                // Persiste en base
                repo.saveMessage(
                    MessageEntity(
                        id = packet.id,
                        contactShortId = recipientId,
                        direction = "OUT",
                        plaintext = plaintext,
                        trustLevelAtSend = getTrustLevel(recipientId),
                        timestamp = System.currentTimeMillis()
                    )
                )

                // Injecte dans le mesh
                relayToAllPeers(packet)
                Log.d(TAG, "Message envoyé à $recipientId")

            } catch (e: Exception) {
                Log.e(TAG, "Erreur envoi : ${e.message}")
            }
        }
    }

    // ─── Réception d'un message ──────────────────────────────

    private suspend fun handleIncomingMessage(packet: RelayPacket) {
        try {
            val store = repo.getOrCreateStore(
                localIdentity,
                localShortId.hashCode()
            )
            val senderAddress = SignalProtocolAddress(packet.senderId, 1)
            val cipher = SessionCipher(store, senderAddress)

            val encrypted = deserializeEncrypted(packet.payload)
            val plaintext = RelayMessage.decrypt(cipher, encrypted)

            Log.d(TAG, "Message déchiffré de ${packet.senderId} : $plaintext")

            // Persiste
            repo.saveMessage(
                MessageEntity(
                    id = packet.id,
                    contactShortId = packet.senderId,
                    direction = "IN",
                    plaintext = plaintext,
                    trustLevelAtSend = getTrustLevel(packet.senderId),
                    timestamp = System.currentTimeMillis(),
                    delivered = true
                )
            )

            // Notifie l'UI
            sendBroadcast(Intent(ACTION_MESSAGE_RECEIVED).apply {
                putExtra(EXTRA_SENDER_ID, packet.senderId)
                putExtra(EXTRA_PLAINTEXT, plaintext)
            })

            // Envoie un ACK en retour
            val ack = RelayPacket(
                id = UUID.randomUUID().toString(),
                type = PacketType.ACK,
                senderId = localShortId,
                recipientId = packet.senderId,
                payload = packet.id.toByteArray(),  // ID du message acquitté
                signature = signPayload(packet.id.toByteArray()),
                ttl = RelayPacket.MAX_HOPS
            )
            relayToRouter(ack)

        } catch (e: Exception) {
            Log.e(TAG, "Erreur déchiffrement : ${e.message}")
        }
    }

    private fun relayToRouter(packet: RelayPacket) {
        val relays = nodeTracker.getBestRelays(
            excludeShortId = localShortId,
            count = SprayAndWait.SPRAY_COPIES
        )
        if (relays.isNotEmpty()) {
            relays.forEach { sendPacketTo(it.macAddress, packet) }
        }
    }
    // ─── Gossip prekeys ──────────────────────────────────────

    private suspend fun sendOurPreKeys(peerAddress: String) {
        val session = RelaySession(localIdentity, localShortId.hashCode())
        val bundle = session.createPreKeyBundle()
        val packet = gossip.createGossipPacket(localShortId, bundle)
        sendPacketTo(peerAddress, packet)
    }

    private suspend fun handlePreKeyGossip(
        senderId: String,
        bundleBytes: ByteArray
    ) {
        // Vérifie la signature et extrait le bundle
        val fakePacket = com.relay.app.model.RelayPacket(
            id = UUID.randomUUID().toString(),
            type = PacketType.PREKEY_GOSSIP,
            senderId = senderId,
            recipientId = null,
            payload = bundleBytes,
            signature = ByteArray(0),  // déjà vérifié par le router
            ttl = 0
        )

        val store = repo.getOrCreateStore(
            localIdentity, localShortId.hashCode()
        )
        val senderAddress = SignalProtocolAddress(senderId, 1)

        // Si pas encore de session avec ce pair
        if (!store.containsSession(senderAddress)) {
            Log.d(TAG, "Prekeys reçues de $senderId — niveau YELLOW")
            repo.upgradeTrust(senderId, TrustLevel.YELLOW)
        }
    }

    // ─── Transport ───────────────────────────────────────────

    private suspend fun relayToAllPeers(packet: RelayPacket) {
        withContext(Dispatchers.IO) {
            peers.values.forEach { address ->
                sendPacketTo(address, packet)
            }
        }
    }

    private fun sendPacketTo(address: String, packet: RelayPacket) {
        val bytes = serializePacket(packet) ?: return
        val client = BleGattClient(this, address)
        client.onSendComplete = { client.disconnect() }
        client.send(bytes)
    }

    // ─── Helpers ─────────────────────────────────────────────

    private fun signPayload(payload: ByteArray): ByteArray {
        return org.signal.libsignal.protocol.ecc.Curve.calculateSignature(
            localIdentity.privateKey, payload
        )
    }

    private suspend fun getTrustLevel(shortId: String): String {
        return repo.getContact(shortId)?.trustLevel?.name ?: "ORANGE"
    }

    private fun serializeEncrypted(payload: EncryptedPayload): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use {
            it.writeBoolean(payload.isPreKeyMessage)
            it.writeInt(payload.ciphertext.size)
            it.write(payload.ciphertext)
        }
        return bos.toByteArray()
    }

    private fun deserializeEncrypted(bytes: ByteArray): EncryptedPayload {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val isPreKey = dis.readBoolean()
        val size = dis.readInt()
        val ciphertext = ByteArray(size)
        dis.readFully(ciphertext)
        return EncryptedPayload(ciphertext, isPreKey)
    }

    private fun serializePacket(packet: RelayPacket): ByteArray? {
        return try {
            ByteArrayOutputStream().also { bos ->
                ObjectOutputStream(bos).use { it.writeObject(packet) }
            }.toByteArray()
        } catch (e: Exception) { null }
    }

    private fun deserializePacket(bytes: ByteArray): RelayPacket? {
        return try {
            ObjectInputStream(ByteArrayInputStream(bytes))
                .use { it.readObject() as RelayPacket }
        } catch (e: Exception) { null }
    }

    // ─── Lifecycle ───────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND -> {
                val recipientId = intent.getStringExtra(EXTRA_RECIPIENT_ID) ?: return START_STICKY
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: return START_STICKY
                sendMessage(recipientId, message)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::advertiser.isInitialized) advertiser.stop()
        if (::scanner.isInitialized) scanner.stop()
        if (::gattServer.isInitialized) gattServer.stop()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Relay Mesh",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Relay")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notif = buildNotification(text)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, notif)
    }
}
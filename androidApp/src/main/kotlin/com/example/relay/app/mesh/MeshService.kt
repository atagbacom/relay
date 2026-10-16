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
        /** Relance l'annonce, le scan et le serveur Bluetooth (écran de diagnostic). */
        const val ACTION_RELANCER = "com.relay.app.RELANCER"
        const val EXTRA_RECIPIENT_ID = "recipient_id"
        const val EXTRA_MESSAGE = "message"
        private const val TAG = "MeshService"

        // Broadcast vers l'UI quand un message arrive
        const val ACTION_MESSAGE_RECEIVED = "com.relay.app.MESSAGE_RECEIVED"
        const val EXTRA_SENDER_ID = "sender_id"
        const val EXTRA_PLAINTEXT = "plaintext"

        // Broadcasts d'état vers l'UI
        const val ACTION_PEER_COUNT_CHANGED = "com.relay.app.PEER_COUNT_CHANGED"
        const val ACTION_BATTERY_CHANGED = "com.relay.app.BATTERY_CHANGED"
    }

    // Une erreur dans une tâche du service est journalisée, elle ne doit
    // jamais fermer l'application
    private val gardien = CoroutineExceptionHandler { _, e -> Log.e(TAG, "Erreur dans le service mesh", e) }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob() + gardien)
    /** Terminé quand l'identité et le store Signal sont chargés. */
    private val pret = CompletableDeferred<Unit>()
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
    // Chargée depuis Room au démarrage. Avant : IdentityKeyPair(ByteArray(0)),
    // qui levait une exception à la création du service et faisait planter
    // l'application à chaque lancement.
    private lateinit var localIdentity: org.signal.libsignal.protocol.IdentityKeyPair
    private var localShortId = ""

    // Store Signal unique et persistant : publie nos clés ET déchiffre
    private lateinit var signalStore: PersistentSignalStore
    private lateinit var session: RelaySession


    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Notifications.creerCanaux(this)
        startForeground(NOTIF_ID, buildNotification("Démarrage…"))

        repo = RelayRepository(this)


        scope.launch {
            // Charge ou crée l'identité persistante
            localIdentity = repo.getOrCreateIdentity()
            localShortId = repo.getShortId()
            signalStore = repo.signalStore(
                localIdentity,
                RelaySession.registrationIdPour(localShortId)
            )
            session = RelaySession(localIdentity, signalStore)

            gossip = PreKeyGossip(localIdentity)
            deferred = DeferredSession(localIdentity)

            nodeTracker = NodeTracker()
            batteryMonitor = BatteryMonitor(this@MeshService)
            batteryMonitor.start()
            scope.launch {
                batteryMonitor.mode.collect { mode ->
                    sendBroadcast(Intent(ACTION_BATTERY_CHANGED).apply {
                        setPackage(packageName)
                        putExtra("level", batteryMonitor.level.value)
                        putExtra("mode", mode.name)
                    })
                }
            }


            router = MeshRouter(localShortId, nodeTracker, batteryMonitor)
            advertiser = BleAdvertiser(this@MeshService)
            scanner = BleScanner(this@MeshService)
            gattServer = BleGattServer(this@MeshService)


            setupRouter()
            setupScanner()
            setupGattServer()

            pret.complete(Unit)
            EtatMesh.identifiant.value = localShortId
            EtatMesh.noter("Service prêt, identifiant $localShortId")

            // Rallumer le Bluetooth relance le mesh (avant : il restait
            // éteint jusqu'au redémarrage du téléphone)
            registerReceiver(etatBluetooth, android.content.IntentFilter(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED))
            demarrerBle()

            // L'interface lit les appareils vus directement, rafraîchis toutes les 5 s
            scope.launch {
                while (isActive) {
                    EtatMesh.majPairs(nodeTracker.getActiveNodes().map {
                        EtatMesh.PairVu(it.shortId, it.macAddress, it.rssi, it.lastSeenAt)
                    })
                    delay(5_000)
                }
            }
            scope.launch {
                while (isActive) {
                    delay(600_000)
                    nodeTracker.prune()
                    broadcastPeerCount()
                }
            }
        }
    }

    // ─── Bluetooth ───────────────────────────────────────────

    private val etatBluetooth = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                android.bluetooth.BluetoothAdapter.STATE_ON -> {
                    EtatMesh.noter("Bluetooth rallumé : relance du mesh")
                    scope.launch { demarrerBle() }
                }
                android.bluetooth.BluetoothAdapter.STATE_OFF -> {
                    EtatMesh.noter("Bluetooth éteint : mesh en attente")
                    arreterBle()
                    EtatMesh.bluetoothActif.value = false
                    updateNotification("Bluetooth désactivé — activez-le pour relayer")
                }
            }
        }
    }

    /** (Re)démarre annonce, scan et serveur. Sans effet si le Bluetooth est éteint. */
    private fun demarrerBle() {
        val bluetooth = (getSystemService(BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager).adapter
        if (bluetooth == null || !bluetooth.isEnabled) {
            EtatMesh.bluetoothActif.value = bluetooth != null && bluetooth.isEnabled
            updateNotification("Bluetooth désactivé — activez-le pour relayer")
            EtatMesh.noter(if (bluetooth == null) "Pas de Bluetooth sur ce téléphone" else "Bluetooth éteint : mesh en attente")
            return
        }
        EtatMesh.bluetoothActif.value = true
        try {
            gattServer.start()
            advertiser.start(localShortId)
            scanner.start()
            updateNotification("Mesh actif — $localShortId")
        } catch (e: SecurityException) {
            updateNotification("Autorisations Bluetooth manquantes")
            EtatMesh.noter("Autorisations Bluetooth manquantes : ${e.message}")
        }
    }

    private fun arreterBle() {
        if (::advertiser.isInitialized) advertiser.stop()
        if (::scanner.isInitialized) scanner.stop()
        if (::gattServer.isInitialized) gattServer.stop()
    }

    // ─── Setup callbacks ─────────────────────────────────────

    private fun setupRouter() {
        router.onMessageForUs = { packet ->
            scope.launch { handleIncomingMessage(packet) }
        }

        // Maintenant onPacketToRelay reçoit une liste d'adresses cibles
        router.onPacketToRelay = { packet, targetAddresses ->
            scope.launch {
                targetAddresses.forEach { address ->
                    sendPacketTo(address, packet)
                }
            }
        }

        router.onPreKeyReceived = { packet ->
            scope.launch { handlePreKeyGossip(packet) }
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
                peers[shortId] = address

                if (isNew) {
                    EtatMesh.noter("Appareil Relay découvert : $shortId (signal $rssi dBm)")
                    router.onNewPeerAvailable(shortId, address)
                    scope.launch { sendOurPreKeys(address) }
                    updateNotification(
                        "Mesh actif — ${nodeTracker.activeCount()} pairs"
                    )
                    broadcastPeerCount()
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
            pret.await()
            try {
                val store = signalStore
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
            val store = signalStore
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
            val nom = repo.getContact(packet.senderId)?.alias ?: "Appareil ${packet.senderId.takeLast(4).uppercase()}"
            Notifications.message(this, packet.senderId, nom, plaintext)

            sendBroadcast(Intent(ACTION_MESSAGE_RECEIVED).apply {
                setPackage(packageName)
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
        val bundle = session.createPreKeyBundle(avecCleUnique = false)
        val packet = gossip.createGossipPacket(localShortId, bundle)
        sendPacketTo(peerAddress, packet)
    }

    /**
     * Clés reçues d'un pair. On vérifie la signature et que la clé correspond
     * à l'identifiant annoncé, puis on ouvre une session : nos messages vers
     * lui pourront partir même s'il n'est plus à portée.
     */
    private suspend fun handlePreKeyGossip(packet: RelayPacket) {
        val bundle = gossip.verifyAndExtract(packet)
        if (bundle == null) {
            Log.w(TAG, "Clés rejetées de ${packet.senderId} : signature ou identifiant invalide")
            return
        }
        val senderId = packet.senderId

        val existant = repo.getContact(senderId)
        if (existant == null) {
            repo.saveContact(
                RelayContact(
                    shortId = senderId,
                    publicKey = bundle.identityKey,
                    trustLevel = TrustLevel.YELLOW
                )
            )
        } else if (existant.publicKey != bundle.identityKey) {
            // Même identifiant, autre clé : usurpation possible, on n'y touche pas
            Log.w(TAG, "Clé différente pour $senderId : ignorée")
            return
        } else {
            repo.upgradeTrust(senderId, TrustLevel.YELLOW)
        }
        nodeTracker.markPreKeyExchanged(senderId)

        try {
            val adresse = SignalProtocolAddress(senderId, RelaySession.DEVICE_ID)
            if (session.accepterBundle(adresse, bundle)) {
                Log.d(TAG, "Session ouverte avec $senderId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Session impossible avec $senderId : ${e.message}")
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

    private fun broadcastPeerCount() {
        sendBroadcast(Intent(ACTION_PEER_COUNT_CHANGED).apply {
            setPackage(packageName)
            putExtra("count", nodeTracker.activeCount())
            putExtra("peers", nodeTracker.getActiveNodes().map { it.shortId }.toTypedArray())
        })
    }

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
            PacketCodec.encode(packet)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Paquet non encodable : ${e.message}")
            null
        }
    }

    /** Octets reçus d'un appareil quelconque : décodage strict, jamais de désérialisation Java. */
    private fun deserializePacket(bytes: ByteArray): RelayPacket? {
        return PacketCodec.decode(bytes).also {
            if (it == null) Log.w(TAG, "Paquet rejeté : format invalide (${bytes.size} octets)")
        }
    }

    // ─── Lifecycle ───────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SEND -> {
                val recipientId = intent.getStringExtra(EXTRA_RECIPIENT_ID) ?: return START_STICKY
                val message = intent.getStringExtra(EXTRA_MESSAGE) ?: return START_STICKY
                sendMessage(recipientId, message)
            }
            ACTION_RELANCER -> scope.launch {
                pret.await()
                EtatMesh.noter("Relance du mesh demandée")
                arreterBle()
                demarrerBle()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { unregisterReceiver(etatBluetooth) }
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
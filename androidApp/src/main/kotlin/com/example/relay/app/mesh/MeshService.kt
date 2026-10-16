package com.relay.app.mesh

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.relay.app.crypto.*
import com.relay.app.model.*
import com.relay.app.repository.RelayRepository
import com.relay.app.storage.EnvoiEntity
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
        /** Après un scan : envoyer notre clé à celui qu'on vient de scanner. */
        const val ACTION_VERIFIER = "com.relay.app.VERIFIER"
        const val EXTRA_NONCE = "nonce"
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


            router = MeshRouter(localShortId, nodeTracker, batteryMonitor::shouldRelay)
            advertiser = BleAdvertiser(this@MeshService)
            scanner = BleScanner(this@MeshService)
            gattServer = BleGattServer(this@MeshService)


            setupRouter()
            setupScanner()
            setupGattServer()

            pret.complete(Unit)
            lancerFileBle()
            scope.launch {
                while (isActive) {
                    delay(15_000)
                    relancerEnvois()
                }
            }
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

    // ─── File d'envoi Bluetooth ──────────────────────────────

    /**
     * Un seul envoi GATT à la fois : deux connexions simultanées vers le même
     * téléphone se gênaient, et le premier de deux messages rapides se perdait.
     */
    private val fileBle = kotlinx.coroutines.channels.Channel<Pair<String, ByteArray>>(capacity = 200)

    private fun lancerFileBle() = scope.launch {
        for ((adresse, octets) in fileBle) {
            val ok = withTimeoutOrNull(20_000) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val client = BleGattClient(this@MeshService, adresse)
                    client.onSendComplete = { succes ->
                        client.disconnect()
                        if (cont.isActive) cont.resumeWith(Result.success(succes))
                    }
                    client.send(octets)
                }
            } ?: false
            if (!ok) EtatMesh.noter("Envoi Bluetooth vers $adresse échoué")
        }
    }

    // ─── Envois en attente d'accusé ─────────────────────────

    /** Renvoie les messages non accusés : à leur destinataire s'il est là, sinon aux relais. */
    private suspend fun relancerEnvois(force: Boolean = false) {
        if (!pret.isCompleted) return
        val maintenant = System.currentTimeMillis()
        repo.envois.purger(maintenant - RelayPacket.TTL_48H)
        val actifs = nodeTracker.getActiveNodes()
        if (actifs.isEmpty()) return
        repo.envois.tous().forEach { e ->
            if (!force && maintenant - e.dernierEssai < 30_000) return@forEach
            val direct = actifs.firstOrNull { it.shortId == e.destinataire }
            val cibles = if (direct != null) listOf(direct.macAddress)
                         else nodeTracker.getBestRelays(localShortId, 3).map { it.macAddress }
            if (cibles.isEmpty()) return@forEach
            cibles.forEach { fileBle.trySend(it to e.paquet) }
            repo.envois.noterEssai(e.id, maintenant)
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
            scope.launch {
                repo.markDelivered(messageId)
                repo.envois.supprimer(messageId)
                EtatMesh.noter("Accusé de réception reçu pour ${messageId.take(8)}")
            }
        }

        // Quelqu'un vient de scanner notre QR et nous envoie sa clé
        router.onVerification = { packet ->
            scope.launch { recevoirVerification(packet) }
        }

        // Message déjà reçu, renvoyé par l'expéditeur : son accusé s'est perdu
        router.onDoublonPourNous = { packet ->
            scope.launch { envoyerAck(packet) }
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
                    scope.launch { delay(1_500); relancerEnvois(force = true) }
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

                // Gardé jusqu'à l'accusé de réception, puis tenté tout de suite
                val octets = serializePacket(packet) ?: return@launch
                repo.envois.enregistrer(
                    EnvoiEntity(id = packet.id, destinataire = recipientId, paquet = octets, creeA = System.currentTimeMillis())
                )
                EtatMesh.noter("Message ${packet.id.take(8)} pour $recipientId en file d'envoi")
                relancerEnvois(force = true)

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

            envoyerAck(packet)

        } catch (e: Exception) {
            Log.e(TAG, "Erreur déchiffrement : ${e.message}")
        }
    }

    // ─── Vérification en personne à un seul scan ────────────

    /** Notre clé, signée, avec le code à usage unique lu dans le QR de l'autre. */
    private fun envoyerVerification(destinataire: String, nonce: String) {
        scope.launch {
            pret.await()
            val monNom = com.relay.app.Reglages(this@MeshService).monNom.ifBlank { null }
            val contenu = VerificationMutuelle.contenu(localIdentity, nonce, monNom)
            val packet = RelayPacket(
                id = UUID.randomUUID().toString(),
                type = PacketType.VERIFICATION,
                senderId = localShortId,
                recipientId = destinataire,
                payload = contenu,
                signature = VerificationMutuelle.signer(localIdentity, contenu),
                ttl = RelayPacket.MAX_HOPS
            )
            val octets = serializePacket(packet) ?: return@launch
            repo.envois.enregistrer(
                EnvoiEntity(id = packet.id, destinataire = destinataire, paquet = octets, creeA = System.currentTimeMillis())
            )
            EtatMesh.noter("Vérification envoyée à $destinataire")
            relancerEnvois(force = true)
        }
    }

    private suspend fun recevoirVerification(packet: RelayPacket) {
        val recue = VerificationMutuelle.verifier(packet.senderId, packet.payload, packet.signature) {
            VerificationMutuelle.Nonces.consommer(it)
        }
        if (recue == null) {
            EtatMesh.noter("Vérification rejetée de ${packet.senderId} : signature, clé ou code invalide")
            return
        }
        envoyerAck(packet)
        EtatMesh.noter("${packet.senderId} vient de nous vérifier : comparaison des mots demandée")
        EtatMesh.verificationRecue.value = recue
        val nom = recue.nom ?: repo.getContact(recue.shortId)?.alias ?: "Appareil ${recue.shortId.takeLast(4).uppercase()}"
        Notifications.verification(this, recue.shortId, nom)
    }

    /** Accusé de réception : directement à l'expéditeur s'il est à portée, sinon aux relais. */
    private fun envoyerAck(message: RelayPacket) {
        val ack = RelayPacket(
            id = UUID.randomUUID().toString(),
            type = PacketType.ACK,
            senderId = localShortId,
            recipientId = message.senderId,
            payload = message.id.toByteArray(),  // ID du message acquitté
            signature = signPayload(message.id.toByteArray()),
            ttl = RelayPacket.MAX_HOPS
        )
        val direct = nodeTracker.getActiveNodes().firstOrNull { it.shortId == message.senderId }
        val cibles = if (direct != null) listOf(direct.macAddress)
                     else nodeTracker.getBestRelays(localShortId, SprayAndWait.SPRAY_COPIES).map { it.macAddress }
        cibles.forEach { sendPacketTo(it, ack) }
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

    private fun sendPacketTo(address: String, packet: RelayPacket) {
        val bytes = serializePacket(packet) ?: return
        fileBle.trySend(address to bytes)
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
            ACTION_VERIFIER -> {
                val dest = intent.getStringExtra(EXTRA_RECIPIENT_ID) ?: return START_STICKY
                val nonce = intent.getStringExtra(EXTRA_NONCE) ?: return START_STICKY
                envoyerVerification(dest, nonce)
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
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
import com.relay.app.storage.RelaisEntity
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
        const val ACTION_DIFFUSER_CLES = "com.relay.app.DIFFUSER_CLES"
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
            EtatMesh.choixBatterie.value = com.relay.app.Reglages(this@MeshService).modeBatterie
            scope.launch {
                EtatMesh.choixBatterie.collect { c ->
                    batteryMonitor.choisir(runCatching { BatteryMonitor.Choix.valueOf(c) }.getOrDefault(BatteryMonitor.Choix.AUTO))
                }
            }
            // Avant : diffusé seulement au changement de mode, le pourcentage
            // affiché restait figé
            scope.launch { batteryMonitor.level.collect { EtatMesh.batterie.value = it } }
            scope.launch {
                batteryMonitor.mode.collect { mode ->
                    EtatMesh.modeBatterie.value = mode.name
                    appliquerModeBatterie()
                }
            }


            router = MeshRouter(localShortId, nodeTracker, batteryMonitor::shouldRelay)
            // Copies gardées pour d'autres : rechargées, elles survivent à un redémarrage
            router.restaurer(repo.relais.valides(System.currentTimeMillis()).mapNotNull { PacketCodec.decode(it.paquet) })
            router.onGarde = { p ->
                serializePacket(p)?.let { octets ->
                    scope.launch { repo.relais.garder(RelaisEntity(p.id, octets, p.expiresAt)) }
                }
            }
            router.onRetrait = { id -> scope.launch { repo.relais.retirer(id) } }
            advertiser = BleAdvertiser(this@MeshService)
            scanner = BleScanner(this@MeshService)
            gattServer = BleGattServer(this@MeshService)
            appliquerModeBatterie()


            setupRouter()
            setupScanner()
            setupGattServer()

            pret.complete(Unit)
            lancerFileBle()
            scope.launch {
                var tour = 0
                while (isActive) {
                    delay(15_000)
                    relancerEnvois()
                    router.relancer()
                    // Les clés ne partaient qu'à la première détection : si cet
                    // unique envoi échouait, aucune session ne s'ouvrait jamais
                    // Toutes les minutes ; toutes les deux en mode critique
                    val rythme = if (batteryMonitor.mode.value == BatteryMonitor.BatteryMode.CRITICAL) 8 else 4
                    if (++tour % rythme == 0) diffuserNosCles()
                    // Android ralentit puis coupe un scan qui dure (30 min), et certains
                    // téléphones ne remontent plus les annonces répétées : le radar
                    // restait figé sur la première position. Relancé toutes les 5 min.
                    if (tour % 20 == 0 && EtatMesh.bluetoothActif.value == true) {
                        runCatching { scanner.start() }
                        repo.relais.purger(System.currentTimeMillis())
                    }
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
                    EtatMesh.majPairs(nodeTracker.getActiveNodes(withinMs = 60_000).map {
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
    private class EnvoiBle(val adresse: String, val octets: ByteArray, val cle: String, val apresSucces: (() -> Unit)?)

    private val fileBle = kotlinx.coroutines.channels.Channel<EnvoiBle>(capacity = kotlinx.coroutines.channels.Channel.UNLIMITED)

    /** Envois déjà dans la file : un même paquet vers une même adresse n'y entre qu'une fois. */
    private val enFile = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Adresses qui viennent d'échouer : téléphone parti ou Bluetooth coupé.
     * Pendant 15 s on n'y envoie plus rien, sinon chaque essai bloquait la
     * file plusieurs secondes et tous les autres messages prenaient du retard.
     */
    private val echecs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun mettreEnFile(adresse: String, octets: ByteArray, id: String, apresSucces: (() -> Unit)? = null) {
        val cle = "$adresse|$id"
        if (!enFile.add(cle)) return
        fileBle.trySend(EnvoiBle(adresse, octets, cle, apresSucces))
    }

    private fun lancerFileBle() = scope.launch {
        for (envoi in fileBle) {
            val adresse = envoi.adresse
            val echecRecent = echecs[adresse]?.let { System.currentTimeMillis() - it < 15_000 } == true
            if (echecRecent) { enFile.remove(envoi.cle); continue }
            val ok = withTimeoutOrNull(12_000) {
                suspendCancellableCoroutine<Boolean> { cont ->
                    val client = BleGattClient(this@MeshService, adresse)
                    client.onSendComplete = { succes ->
                        client.disconnect()
                        if (cont.isActive) cont.resumeWith(Result.success(succes))
                    }
                    // Délai dépassé : sans ça la connexion restait ouverte et
                    // bloquait les envois suivants vers le même téléphone
                    cont.invokeOnCancellation { runCatching { client.disconnect() } }
                    client.send(envoi.octets)
                }
            } ?: false
            enFile.remove(envoi.cle)
            if (ok) {
                echecs.remove(adresse)
                envoi.apresSucces?.invoke()
            } else {
                echecs[adresse] = System.currentTimeMillis()
                EtatMesh.noter("Envoi Bluetooth vers $adresse échoué : nouvel essai plus tard")
            }
        }
    }

    // ─── Envois en attente d'accusé ─────────────────────────

    /** Renvoie les messages non accusés : à leur destinataire s'il est là, sinon aux relais. */
    private suspend fun relancerEnvois(force: Boolean = false) {
        if (!pret.isCompleted) return
        val maintenant = System.currentTimeMillis()
        repo.envois.purger(maintenant - RelayPacket.TTL_48H)
        val actifs = nodeTracker.getActiveNodes(withinMs = 30_000)
        if (actifs.isEmpty()) return
        repo.envois.tous().forEach { e ->
            if (!force && maintenant - e.dernierEssai < 30_000) return@forEach
            val direct = actifs.firstOrNull { it.shortId == e.destinataire }
            val cibles = if (direct != null) listOf(direct.macAddress)
                         else nodeTracker.getBestRelays(localShortId, 3).map { it.macAddress }
            if (cibles.isEmpty()) return@forEach
            cibles.forEach { mettreEnFile(it, e.paquet, e.id) }
            repo.envois.noterEssai(e.id, maintenant)
        }
    }

    // ─── Bluetooth ───────────────────────────────────────────

    private val etatBluetooth = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.getIntExtra(android.bluetooth.BluetoothAdapter.EXTRA_STATE, -1)) {
                android.bluetooth.BluetoothAdapter.STATE_ON -> {
                    Notifications.retirerBluetoothEteint(this@MeshService)
                    EtatMesh.noter("Bluetooth rallumé : relance du mesh")
                    scope.launch { demarrerBle() }
                }
                android.bluetooth.BluetoothAdapter.STATE_OFF -> {
                    EtatMesh.noter("Bluetooth éteint : mesh en attente")
                    arreterBle()
                    EtatMesh.bluetoothActif.value = false
                    updateNotification("Bluetooth désactivé — activez-le pour relayer")
                    Notifications.bluetoothEteint(this@MeshService)
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
            if (bluetooth != null) Notifications.bluetoothEteint(this)
            EtatMesh.noter(if (bluetooth == null) "Pas de Bluetooth sur ce téléphone" else "Bluetooth éteint : mesh en attente")
            return
        }
        EtatMesh.bluetoothActif.value = true
        Notifications.retirerBluetoothEteint(this)
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

    /** Scan et annonce au rythme du mode batterie ; relancés seulement s'ils tournent. */
    private fun appliquerModeBatterie() {
        if (!::scanner.isInitialized || !::advertiser.isInitialized) return
        val scanAvant = scanner.modeScan
        val annonceAvant = advertiser.modeAnnonce
        scanner.modeScan = batteryMonitor.modeScan()
        advertiser.modeAnnonce = batteryMonitor.modeAnnonce()
        if (EtatMesh.bluetoothActif.value != true) return
        if (scanAvant == scanner.modeScan && annonceAvant == advertiser.modeAnnonce) return
        EtatMesh.noter("Mode batterie ${batteryMonitor.mode.value.name} : scan et annonce ajustés")
        runCatching { scanner.start(); advertiser.start(localShortId) }
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
                // Revenu après 30 s d'absence (Bluetooth coupé, parti puis revenu) :
                // traité comme une nouvelle rencontre, clés et messages repartent
                val isNew = !nodeTracker.getActiveNodes(withinMs = 30_000)
                    .any { it.shortId == shortId }
                nodeTracker.seen(shortId, address, rssi)
                peers[shortId] = address

                if (isNew) {
                    EtatMesh.noter("Appareil Relay découvert : $shortId (signal $rssi dBm)")
                    rencontre(shortId, address)
                }
            }
        }
    }

    /** Un pair arrive à portée : nos clés, ce qu'on garde pour lui, nos messages en attente. */
    private fun rencontre(shortId: String, address: String) {
        router.onNewPeerAvailable(shortId, address)
        scope.launch { sendOurPreKeys(address) }
        scope.launch { delay(1_500); relancerEnvois(force = true) }
        updateNotification("Mesh actif — ${nodeTracker.activeCount()} pairs")
        broadcastPeerCount()
    }

    private fun setupGattServer() {
        gattServer.onPacketReceived = { bytes, adresse ->
            scope.launch {
                val packet = deserializePacket(bytes) ?: return@launch
                // Paquet à son nombre de sauts maximal : il vient directement de
                // son expéditeur. Avant, un téléphone revenu (Bluetooth coupé, donc
                // nouvelle adresse) n'était reconnu qu'au scan : ses messages en
                // attente restaient bloqués jusqu'à ce qu'il nous écrive ET qu'un
                // autre événement relance les envois.
                if (packet.ttl == RelayPacket.MAX_HOPS && packet.senderId != localShortId &&
                    nodeTracker.vuParConnexion(packet.senderId, adresse)) {
                    EtatMesh.noter("${packet.senderId} est de retour (reconnu par sa connexion)")
                    peers[packet.senderId] = adresse
                    rencontre(packet.senderId, adresse)
                }
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
            val id = UUID.randomUUID().toString()
            // Enregistré d'abord : avant, un message sans session était perdu sans trace
            repo.saveMessage(
                MessageEntity(
                    id = id,
                    contactShortId = recipientId,
                    direction = "OUT",
                    plaintext = plaintext,
                    trustLevelAtSend = getTrustLevel(recipientId),
                    timestamp = System.currentTimeMillis()
                )
            )
            chiffrerEtEnvoyer(recipientId, id, plaintext)
        }
    }

    /**
     * Chiffre un message déjà enregistré et le met en file d'envoi. Sans
     * session, il attend les clés du destinataire : [envoyerEnAttente] le
     * reprendra dès qu'elles arrivent.
     */
    private suspend fun chiffrerEtEnvoyer(destinataire: String, id: String, texte: String): Boolean {
        val adresse = SignalProtocolAddress(destinataire, RelaySession.DEVICE_ID)
        if (!signalStore.containsSession(adresse)) {
            EtatMesh.noter("Message ${id.take(8)} en attente : clés de $destinataire pas encore reçues")
            envoyerNosCles(destinataire)
            return false
        }
        return try {
            val chiffre = serializeEncrypted(RelayMessage.encrypt(SessionCipher(signalStore, adresse), texte))
            val packet = RelayPacket(
                id = id,
                type = PacketType.MESSAGE,
                senderId = localShortId,
                recipientId = destinataire,
                payload = chiffre,
                signature = signPayload(chiffre),
                ttl = RelayPacket.MAX_HOPS
            )
            val octets = serializePacket(packet) ?: return false
            repo.envois.enregistrer(
                EnvoiEntity(id = id, destinataire = destinataire, paquet = octets, creeA = System.currentTimeMillis())
            )
            EtatMesh.noter("Message ${id.take(8)} pour $destinataire en file d'envoi")
            relancerEnvois(force = true)
            true
        } catch (e: Exception) {
            EtatMesh.noter("Chiffrement impossible pour $destinataire : ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            false
        }
    }

    /** Messages restés sans paquet (pas de session à l'envoi) : chiffrés maintenant. */
    private suspend fun envoyerEnAttente(contact: String) {
        val enFile = repo.envois.pour(contact).map { it.id }.toSet()
        repo.nonRemis(contact)
            .filter { it.id !in enFile }
            .forEach { chiffrerEtEnvoyer(contact, it.id, it.plaintext) }
    }

    /**
     * Session cassée (l'autre ne déchiffre plus nos messages, aucun accusé ne
     * revient) : on repart de ses clés fraîches et on rechiffre ce qui attend.
     * Les identifiants restent les mêmes : un message déjà reçu n'est pas doublé.
     */
    private val derniereResync = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private suspend fun resynchroniser(contact: String, bundle: org.signal.libsignal.protocol.state.PreKeyBundle) {
        val maintenant = System.currentTimeMillis()
        if (maintenant - (derniereResync[contact] ?: 0L) < 120_000) return
        val bloques = repo.envois.pour(contact).filter { it.tentatives >= 3 && maintenant - it.creeA > 60_000 }
        if (bloques.isEmpty()) return
        derniereResync[contact] = maintenant
        EtatMesh.noter("Session avec $contact renouvelée : ${bloques.size} message(s) sans accusé")
        val adresse = SignalProtocolAddress(contact, RelaySession.DEVICE_ID)
        signalStore.deleteSession(adresse)
        SessionBuilder(signalStore, adresse).process(bundle)
        bloques.forEach { repo.envois.supprimer(it.id) }
        envoyerEnAttente(contact)
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
                    // Heure d'écriture chez l'expéditeur : des messages arrivés dans le
                    // désordre (relais, renvois) s'affichent dans l'ordre où ils ont été écrits
                    timestamp = minOf(packet.createdAt, System.currentTimeMillis()),
                    delivered = true
                )
            )

            // Notifie l'UI
            val nom = repo.getContact(packet.senderId)?.nomAffiche ?: "Appareil ${packet.senderId.takeLast(4).uppercase()}"
            Notifications.message(this, packet.senderId, nom, plaintext)

            sendBroadcast(Intent(ACTION_MESSAGE_RECEIVED).apply {
                setPackage(packageName)
                putExtra(EXTRA_SENDER_ID, packet.senderId)
                putExtra(EXTRA_PLAINTEXT, plaintext)
            })

            envoyerAck(packet)
            // Premier message reçu de ce contact : la session existe, ce qui attendait peut partir
            if (encrypted.isPreKeyMessage) envoyerEnAttente(packet.senderId)

        } catch (e: org.signal.libsignal.protocol.DuplicateMessageException) {
            // Déjà déchiffré (par exemple avant un redémarrage) : l'expéditeur attend encore l'accusé
            EtatMesh.noter("Message ${packet.id.take(8)} déjà reçu : accusé renvoyé")
            envoyerAck(packet)
        } catch (e: Exception) {
            EtatMesh.noter("Message de ${packet.senderId} indéchiffrable : ${e.javaClass.simpleName} ${e.message.orEmpty()}")
            // L'expéditeur reçoit nos clés : s'il n'a pas d'accusé, il rouvrira la session
            envoyerNosCles(packet.senderId)
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
        val nom = repo.getContact(recue.shortId)?.alias ?: recue.nom ?: "Appareil ${recue.shortId.takeLast(4).uppercase()}"
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
        val direct = nodeTracker.getActiveNodes(withinMs = 30_000).firstOrNull { it.shortId == message.senderId }
        val cibles = if (direct != null) listOf(direct.macAddress)
                     else nodeTracker.getBestRelays(localShortId, SprayAndWait.SPRAY_COPIES).map { it.macAddress }
        cibles.forEach { sendPacketTo(it, ack) }
    }
    // ─── Gossip prekeys ──────────────────────────────────────

    private suspend fun sendOurPreKeys(peerAddress: String) {
        val bundle = session.createPreKeyBundle(avecCleUnique = false)
        val packet = gossip.createGossipPacket(localShortId, bundle, monNom())
        sendPacketTo(peerAddress, packet)
    }

    private fun monNom(): String? = com.relay.app.Reglages(this).monNom.ifBlank { null }

    /** Nos clés vers un contact : directement s'il est à portée, sinon par les relais. */
    private suspend fun envoyerNosCles(contact: String) {
        val direct = nodeTracker.getActiveNodes(withinMs = 30_000).firstOrNull { it.shortId == contact }
        val cibles = if (direct != null) listOf(direct.macAddress)
                     else nodeTracker.getBestRelays(localShortId, 3).map { it.macAddress }
        if (cibles.isEmpty()) return
        val packet = gossip.createGossipPacket(localShortId, session.createPreKeyBundle(avecCleUnique = false), monNom())
        cibles.forEach { sendPacketTo(it, packet) }
    }

    /** Nos clés à chaque voisin direct, régulièrement. */
    private suspend fun diffuserNosCles() {
        val voisins = nodeTracker.getActiveNodes(withinMs = 30_000)
        if (voisins.isEmpty()) return
        val packet = gossip.createGossipPacket(localShortId, session.createPreKeyBundle(avecCleUnique = false), monNom())
        voisins.forEach { sendPacketTo(it.macAddress, packet) }
    }

    /**
     * Clés reçues d'un pair. On vérifie la signature et que la clé correspond
     * à l'identifiant annoncé, puis on ouvre une session : nos messages vers
     * lui pourront partir même s'il n'est plus à portée.
     */
    private suspend fun handlePreKeyGossip(packet: RelayPacket) {
        val bundle = gossip.verifyAndExtract(packet)
        if (bundle == null) {
            EtatMesh.noter("Clés rejetées de ${packet.senderId} : signature ou identifiant invalide")
            return
        }
        val senderId = packet.senderId

        val existant = repo.getContact(senderId)
        val nomAnnonce = gossip.nomDe(packet)
        if (existant == null) {
            repo.saveContact(
                RelayContact(
                    shortId = senderId,
                    publicKey = bundle.identityKey,
                    trustLevel = TrustLevel.YELLOW,
                    nomAnnonce = nomAnnonce
                )
            )
        } else if (existant.publicKey != bundle.identityKey) {
            // Même identifiant, autre clé : usurpation possible, on n'y touche pas
            EtatMesh.noter("Clé différente reçue pour $senderId : ignorée (usurpation possible)")
            return
        } else {
            repo.upgradeTrust(senderId, TrustLevel.YELLOW)
            // Le contact a changé de nom : pris en compte sans nouveau scan.
            // Un nom donné à la main (alias) reste affiché en priorité.
            if (nomAnnonce != null && nomAnnonce != existant.nomAnnonce) {
                repo.getContact(senderId)?.let { repo.saveContact(it.copy(nomAnnonce = nomAnnonce)) }
                EtatMesh.noter("$senderId s'appelle maintenant « $nomAnnonce »")
            }
        }
        nodeTracker.markPreKeyExchanged(senderId)

        try {
            val adresse = SignalProtocolAddress(senderId, RelaySession.DEVICE_ID)
            if (session.accepterBundle(adresse, bundle)) {
                EtatMesh.noter("Session ouverte avec $senderId")
                // Qu'il puisse aussi nous écrire, même si notre envoi de clés s'est perdu
                envoyerNosCles(senderId)
                envoyerEnAttente(senderId)
            } else {
                resynchroniser(senderId, bundle)
            }
        } catch (e: Exception) {
            EtatMesh.noter("Session impossible avec $senderId : ${e.javaClass.simpleName} ${e.message.orEmpty()}")
        }
    }

    // ─── Transport ───────────────────────────────────────────

    private fun sendPacketTo(address: String, packet: RelayPacket) {
        val bytes = serializePacket(packet) ?: return
        val destinataire = packet.recipientId
        mettreEnFile(address, bytes, packet.id) {
            // Écrit chez son destinataire lui-même : la copie gardée ne sert plus
            if (destinataire != null && nodeTracker.getMacAddress(destinataire) == address) router.livre(packet.id)
        }
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
            // Notre nom vient de changer : nos voisins le reçoivent tout de suite
            ACTION_DIFFUSER_CLES -> scope.launch { pret.await(); diffuserNosCles() }
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
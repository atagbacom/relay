package com.relay.app.mesh

import android.util.Log
import com.relay.app.model.PacketType
import com.relay.app.model.RelayPacket

/**
 * Routeur mesh avec Spray-and-Wait et gestion batterie.
 *
 * Remplace le routeur naïf du Sprint 3.
 * Intègre :
 * - BloomFilter pour la déduplication
 * - SprayAndWait pour le contrôle des copies
 * - NodeTracker pour choisir les meilleurs relais
 * - BatteryMonitor pour adapter le comportement
 */
class MeshRouter(
    private val localShortId: String,
    private val nodeTracker: NodeTracker,
    /** Mode batterie : faut-il encore relayer ce paquet ? (BatteryMonitor.shouldRelay) */
    private val peutRelayer: (RelayPacket) -> Boolean = { true },
    /**
     * Signature du paquet par son expéditeur : vrai si valide, faux si
     * invalide, null si la clé de l'expéditeur est inconnue ici.
     */
    private val verifierSignature: (RelayPacket) -> Boolean? = { null },
    private val horloge: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** Paquets acceptés par expéditeur et par minute, hors paquets pour nous. */
        const val DEBIT_MAX_PAR_MINUTE = 60
    }

    /** Un paquet vient d'être rejeté (signature fausse, débit dépassé) : pour le journal. */
    var onRejet: ((RelayPacket, String) -> Unit)? = null

    /** Paquets reçus de chaque expéditeur dans la minute en cours. */
    private val debit = HashMap<String, Pair<Long, Int>>()

    /** Vrai si l'expéditeur dépasse son débit : un appareil qui inonde le réseau est freiné. */
    private fun debitDepasse(expediteur: String): Boolean {
        val maintenant = horloge()
        val (depuis, n) = debit[expediteur]?.takeIf { maintenant - it.first < 60_000 } ?: (maintenant to 0)
        debit[expediteur] = depuis to n + 1
        if (debit.size > 500) debit.entries.removeAll { maintenant - it.value.first >= 60_000 }
        return n + 1 > DEBIT_MAX_PAR_MINUTE
    }
    private val bloom = BloomFilter()
    private val sprayAndWait = SprayAndWait(localShortId)

    // Callbacks vers MeshService
    var onMessageForUs: ((RelayPacket) -> Unit)? = null
    var onPacketToRelay: ((RelayPacket, List<String>) -> Unit)? = null
    var onPreKeyReceived: ((RelayPacket) -> Unit)? = null
    /** Reçoit l'identifiant du MESSAGE accusé (et non celui de l'expéditeur de l'accusé). */
    var onAckReceived: ((String) -> Unit)? = null
    /** Un message pour nous arrive une seconde fois : l'expéditeur n'a pas eu l'accusé, le renvoyer. */
    var onDoublonPourNous: ((RelayPacket) -> Unit)? = null
    /** Quelqu'un vient de scanner notre QR et nous envoie sa clé. */
    var onVerification: ((RelayPacket) -> Unit)? = null
    /** Un contact n'arrive plus à déchiffrer nos messages et demande une nouvelle session. */
    var onResync: ((RelayPacket) -> Unit)? = null

    /**
     * Paquets destinés à d'autres, gardés en phase Wait jusqu'à ce que leur
     * destinataire passe à portée (stocker, transporter, livrer).
     */
    private val enAttente = LinkedHashMap<String, RelayPacket>()
    private val maxEnAttente = 200

    /** Dernière tentative de livraison de chaque paquet gardé. */
    private val tentatives = HashMap<String, Long>()

    /**
     * Messages dont on a vu passer l'accusé : livrés, inutile de les garder
     * ou de les relayer encore (les autres copies sont nettoyées).
     */
    private val livres = object : LinkedHashMap<String, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 2000
    }

    /**
     * Dernières clés publiées par chaque appareil, gardées pour les remettre
     * à ceux qui ne l'ont jamais croisé : sans elles, impossible de lui écrire.
     */
    private val clesConnues = object : LinkedHashMap<String, RelayPacket>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RelayPacket>?) = size > 30
    }

    /** Un paquet est gardé : à recopier en base pour survivre à un redémarrage. */
    var onGarde: ((RelayPacket) -> Unit)? = null
    /** Un paquet gardé n'est plus utile (livré, accusé ou expiré). */
    var onRetrait: ((String) -> Unit)? = null

    /** Recharge les paquets gardés avant un redémarrage. */
    @Synchronized
    fun restaurer(paquets: List<RelayPacket>) {
        paquets.filter { it.isValid() }.forEach {
            enAttente[it.id] = it
            bloom.add(it.id)
        }
    }

    /** Le destinataire a bien reçu le paquet (écriture Bluetooth confirmée) : on le lâche. */
    @Synchronized
    fun livre(packetId: String) {
        if (enAttente.remove(packetId) != null) {
            tentatives.remove(packetId)
            onRetrait?.invoke(packetId)
        }
    }

    /** Adresse du destinataire s'il est à portée en ce moment (vu il y a moins de 30 s). */
    private fun adresseProche(shortId: String?): String? =
        shortId?.let { nodeTracker.adresseRecente(it) }

    /**
     * Toutes les 15 s : chaque paquet gardé dont le destinataire est à portée
     * repart vers lui, au plus toutes les 20 s, jusqu'à la confirmation.
     */
    @Synchronized
    fun relancer(maintenant: Long = System.currentTimeMillis()) {
        retirerExpires()
        enAttente.values.toList().forEach { p ->
            val mac = adresseProche(p.recipientId) ?: return@forEach
            if (maintenant - (tentatives[p.id] ?: 0L) < 20_000) return@forEach
            tentatives[p.id] = maintenant
            onPacketToRelay?.invoke(p.decrementTtl(), listOf(mac))
        }
    }

    private fun retirerExpires() {
        enAttente.values.filter { !it.isValid() }.map { it.id }.forEach {
            enAttente.remove(it); tentatives.remove(it); onRetrait?.invoke(it)
        }
    }

    /**
     * Point d'entrée unique — traite chaque paquet entrant.
     */
    @Synchronized
    fun handle(packet: RelayPacket) {

        // ── Déduplication ──────────────────────────────────
        if (bloom.mightContain(packet.id)) {
            Log.v("MeshRouter", "Paquet déjà vu : ${packet.id.take(8)}")
            if ((packet.type == PacketType.MESSAGE || packet.type == PacketType.VERIFICATION) && packet.recipientId == localShortId) {
                onDoublonPourNous?.invoke(packet)
            }
            return
        }
        bloom.add(packet.id)

        // ── Déjà livré (accusé vu passer) ─────────────────
        if (packet.type != PacketType.ACK && livres.containsKey(packet.id)) return

        // ── Expiration ─────────────────────────────────────
        if (!packet.isValid()) {
            Log.v("MeshRouter", "Paquet expiré : ${packet.id.take(8)}")
            return
        }

        // ── Intégrité à chaque saut ────────────────────────
        // Avant, seul le destinataire vérifiait : un relais faisait suivre un
        // paquet falsifié, qui encombrait le réseau jusqu'au bout.
        if (verifierSignature(packet) == false) {
            onRejet?.invoke(packet, "signature invalide")
            return
        }

        // ── Débit par expéditeur (faux pairs, inondation) ──
        if (packet.recipientId != localShortId && debitDepasse(packet.senderId)) {
            onRejet?.invoke(packet, "débit dépassé")
            return
        }

        // ── Vérification batterie ──────────────────────────
        if (packet.recipientId != localShortId && !peutRelayer(packet)) {
            Log.d("MeshRouter", "Mode critique — paquet ignoré")
            return
        }

        // ── Dispatch par type ──────────────────────────────
        when (packet.type) {
            PacketType.MESSAGE       -> handleMessage(packet)
            PacketType.PREKEY_GOSSIP -> handleGossip(packet)
            PacketType.DEFERRED_INIT -> handleDeferred(packet)
            PacketType.ACK           -> handleAck(packet)
            // Pour nous : à vérifier ; sinon acheminé comme un message
            PacketType.VERIFICATION  ->
                if (packet.recipientId == localShortId) onVerification?.invoke(packet) else handleMessage(packet)
            PacketType.RESYNC        ->
                if (packet.recipientId == localShortId) onResync?.invoke(packet) else handleMessage(packet)
        }
    }

    private fun handleMessage(packet: RelayPacket) {
        val isForUs = packet.recipientId == localShortId
        val adresseDirecte = adresseProche(packet.recipientId)

        when (val action = sprayAndWait.decide(packet, isForUs, adresseDirecte != null)) {

            is SprayAction.DELIVER -> {
                Log.d("MeshRouter", "Message livré de ${packet.senderId}")
                onMessageForUs?.invoke(packet)
            }

            is SprayAction.FORWARD_DIRECT -> {
                // Destinataire en vue : livraison directe. Le paquet reste gardé
                // tant que l'écriture n'est pas confirmée (avant : un échec le perdait)
                garder(packet)
                tentatives[packet.id] = System.currentTimeMillis()
                onPacketToRelay?.invoke(packet.decrementTtl(), listOf(adresseDirecte!!))
                nodeTracker.recordRelay(packet.recipientId!!)
            }

            is SprayAction.SPRAY -> {
                // Gardé pour le destinataire, et des copies aux meilleurs relais
                garder(packet)
                val relays = nodeTracker.getBestRelays(
                    excludeShortId = packet.senderId,
                    count = action.copies
                )
                if (relays.isNotEmpty()) {
                    Log.d("MeshRouter", "Spray ${action.copies} copies vers ${relays.size} relais")
                    onPacketToRelay?.invoke(packet.decrementTtl(), relays.map { it.macAddress })
                    relays.forEach { nodeTracker.recordRelay(it.shortId) }
                }
            }

            is SprayAction.WAIT -> {
                // Phase Wait : on garde le paquet, livré si son destinataire passe
                garder(packet)
                Log.v("MeshRouter", "Wait — paquet gardé : ${packet.id.take(8)}")
            }
        }
    }

    private fun handleGossip(packet: RelayPacket) {
        clesConnues.remove(packet.senderId)
        clesConnues[packet.senderId] = packet
        // Met à jour le tracker
        nodeTracker.markPreKeyExchanged(packet.senderId)
        onPreKeyReceived?.invoke(packet)

        // Relaie le gossip à tous les voisins actifs
        val relays = nodeTracker.getActiveNodes()
            .filter { it.shortId != packet.senderId }
            .map { it.macAddress }

        if (relays.isNotEmpty()) {
            onPacketToRelay?.invoke(packet.decrementTtl(), relays)
        }
    }

    private fun handleDeferred(packet: RelayPacket) {
        if (packet.recipientId == localShortId) {
            onMessageForUs?.invoke(packet)
        } else {
            // Relaie vers le destinataire si on l'a en vue
            val recipientMac = packet.recipientId?.let {
                nodeTracker.getMacAddress(it)
            }
            if (recipientMac != null) {
                onPacketToRelay?.invoke(
                    packet.decrementTtl(),
                    listOf(recipientMac)
                )
            } else {
                // Garde et relaie lors du prochain spray
                val relays = nodeTracker.getBestRelays(
                    excludeShortId = packet.senderId,
                    count = 3  // Moins de copies pour les différés
                ).map { it.macAddress }
                if (relays.isNotEmpty()) {
                    onPacketToRelay?.invoke(packet.decrementTtl(), relays)
                }
            }
        }
    }

    @Synchronized
    private fun handleAck(packet: RelayPacket) {
        // Le message accusé est livré : chaque relais qui voit passer l'accusé
        // jette sa copie et ne la relaiera plus
        val accuse = String(packet.payload, Charsets.UTF_8)
        livres[accuse] = true
        livre(accuse)

        if (packet.recipientId == localShortId) {
            onAckReceived?.invoke(accuse)
            return
        }
        // Vers l'expéditeur s'il est là ; sinon gardé pour lui, et quelques
        // copies aux voisins pour qu'ils nettoient aussi
        val adresse = adresseProche(packet.recipientId)
        garder(packet)
        if (adresse != null) {
            tentatives[packet.id] = System.currentTimeMillis()
            onPacketToRelay?.invoke(packet.decrementTtl(), listOf(adresse))
        } else {
            val voisins = nodeTracker.getBestRelays(excludeShortId = packet.senderId, count = 3).map { it.macAddress }
            if (voisins.isNotEmpty()) onPacketToRelay?.invoke(packet.decrementTtl(), voisins)
        }
    }

    @Synchronized
    private fun garder(packet: RelayPacket) {
        if (packet.recipientId == null || packet.ttl <= 1) return
        if (enAttente.containsKey(packet.id)) return
        enAttente[packet.id] = packet
        onGarde?.invoke(packet)
        while (enAttente.size > maxEnAttente) {
            val plusAncien = enAttente.keys.first()
            enAttente.remove(plusAncien); tentatives.remove(plusAncien); onRetrait?.invoke(plusAncien)
        }
    }

    /** Nombre de paquets d'autres appareils gardés pour être livrés plus tard. */
    @Synchronized
    fun nombreEnAttente(): Int = enAttente.size

    /**
     * Appelé par MeshService quand un nouveau pair est découvert.
     * Si on était en phase Wait pour un message destiné à ce pair,
     * on peut maintenant le livrer directement.
     */
    @Synchronized
    fun onNewPeerAvailable(shortId: String, macAddress: String) {
        Log.d("MeshRouter", "Nouveau pair disponible : $shortId")
        retirerExpires()
        // Les clés des autres appareils, pour qu'il puisse leur écrire
        clesConnues.values.filter { it.senderId != shortId && it.isValid() && it.ttl > 1 }
            .takeLast(20)
            .forEach { onPacketToRelay?.invoke(it.decrementTtl(), listOf(macAddress)) }

        // Envoyés tout de suite, mais gardés jusqu'à la confirmation de livraison
        val maintenant = System.currentTimeMillis()
        enAttente.values.filter { it.recipientId == shortId }.forEach { p ->
            tentatives[p.id] = maintenant
            onPacketToRelay?.invoke(p.decrementTtl(), listOf(macAddress))
        }
    }
}
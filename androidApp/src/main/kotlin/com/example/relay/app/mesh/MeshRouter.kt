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
    private val batteryMonitor: BatteryMonitor
) {
    private val bloom = BloomFilter()
    private val sprayAndWait = SprayAndWait(localShortId)

    // Callbacks vers MeshService
    var onMessageForUs: ((RelayPacket) -> Unit)? = null
    var onPacketToRelay: ((RelayPacket, List<String>) -> Unit)? = null
    var onPreKeyReceived: ((String, ByteArray) -> Unit)? = null
    var onAckReceived: ((String) -> Unit)? = null

    /**
     * Point d'entrée unique — traite chaque paquet entrant.
     */
    fun handle(packet: RelayPacket) {

        // ── Déduplication ──────────────────────────────────
        if (bloom.mightContain(packet.id)) {
            Log.v("MeshRouter", "Paquet déjà vu : ${packet.id.take(8)}")
            return
        }
        bloom.add(packet.id)

        // ── Expiration ─────────────────────────────────────
        if (!packet.isValid()) {
            Log.v("MeshRouter", "Paquet expiré : ${packet.id.take(8)}")
            return
        }

        // ── Vérification batterie ──────────────────────────
        if (!batteryMonitor.shouldRelay(packet)) {
            Log.d("MeshRouter", "Mode critique — paquet ignoré")
            return
        }

        // ── Dispatch par type ──────────────────────────────
        when (packet.type) {
            PacketType.MESSAGE       -> handleMessage(packet)
            PacketType.PREKEY_GOSSIP -> handleGossip(packet)
            PacketType.DEFERRED_INIT -> handleDeferred(packet)
            PacketType.ACK           -> handleAck(packet)
        }
    }

    private fun handleMessage(packet: RelayPacket) {
        val isForUs = packet.recipientId == localShortId
        val hasDirectContact = packet.recipientId?.let {
            nodeTracker.getMacAddress(it) != null
        } ?: false

        when (val action = sprayAndWait.decide(packet, isForUs, hasDirectContact)) {

            is SprayAction.DELIVER -> {
                Log.d("MeshRouter", "Message livré de ${packet.senderId}")
                onMessageForUs?.invoke(packet)
            }

            is SprayAction.FORWARD_DIRECT -> {
                // On a le destinataire en vue — livraison directe
                val recipientMac = packet.recipientId?.let {
                    nodeTracker.getMacAddress(it)
                }
                if (recipientMac != null) {
                    Log.d("MeshRouter", "Livraison directe à ${packet.recipientId}")
                    onPacketToRelay?.invoke(packet.decrementTtl(), listOf(recipientMac))
                    nodeTracker.recordRelay(packet.recipientId!!)
                }
            }

            is SprayAction.SPRAY -> {
                // Distribue les copies aux meilleurs relais
                val relays = nodeTracker.getBestRelays(
                    excludeShortId = packet.senderId,
                    count = action.copies
                )
                if (relays.isNotEmpty()) {
                    val addresses = relays.map { it.macAddress }
                    Log.d("MeshRouter",
                        "Spray ${action.copies} copies vers ${relays.size} relais")
                    onPacketToRelay?.invoke(packet.decrementTtl(), addresses)
                    relays.forEach { nodeTracker.recordRelay(it.shortId) }
                }
            }

            is SprayAction.WAIT -> {
                // Phase Wait — on garde le paquet mais on ne relaie pas
                Log.v("MeshRouter", "Wait — paquet gardé : ${packet.id.take(8)}")
            }
        }
    }

    private fun handleGossip(packet: RelayPacket) {
        // Met à jour le tracker
        nodeTracker.markPreKeyExchanged(packet.senderId)
        onPreKeyReceived?.invoke(packet.senderId, packet.payload)

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

    private fun handleAck(packet: RelayPacket) {
        if (packet.recipientId == localShortId) {
            onAckReceived?.invoke(packet.senderId)
        } else {
            // Relaie l'ACK vers l'expéditeur original
            val senderMac = nodeTracker.getMacAddress(packet.recipientId ?: return)
            if (senderMac != null) {
                onPacketToRelay?.invoke(packet.decrementTtl(), listOf(senderMac))
            }
        }
    }

    /**
     * Appelé par MeshService quand un nouveau pair est découvert.
     * Si on était en phase Wait pour un message destiné à ce pair,
     * on peut maintenant le livrer directement.
     */
    fun onNewPeerAvailable(shortId: String, macAddress: String) {
        Log.d("MeshRouter", "Nouveau pair disponible : $shortId")
        // Le prochain passage dans handle() détectera le contact direct
        // via nodeTracker.getMacAddress()
    }
}
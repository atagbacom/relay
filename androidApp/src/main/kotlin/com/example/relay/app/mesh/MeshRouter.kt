package com.relay.app.mesh

import com.relay.app.model.RelayPacket
import com.relay.app.model.PacketType

/**
 * Cerveau du mesh — décide quoi faire de chaque paquet reçu.
 *
 * Règles dans l'ordre :
 * 1. Paquet déjà vu → ignore
 * 2. Paquet expiré → ignore
 * 3. Paquet pour nous → livre à l'application
 * 4. Paquet pour quelqu'un d'autre → relaie si TTL > 0
 * 5. Broadcast (gossip) → cache + relaie
 */
class MeshRouter(private val localShortId: String) {

    private val bloom = BloomFilter()
    private val preKeyCache = mutableMapOf<String, ByteArray>()

    // Callbacks vers le service
    var onMessageForUs: ((RelayPacket) -> Unit)? = null
    var onPacketToRelay: ((RelayPacket) -> Unit)? = null
    var onPreKeyReceived: ((String, ByteArray) -> Unit)? = null

    /**
     * Point d'entrée — traite chaque paquet entrant.
     */
    fun handle(packet: RelayPacket) {

        // 1. Déduplication
        if (bloom.mightContain(packet.id)) return
        bloom.add(packet.id)

        // 2. Expiration
        if (!packet.isValid()) return

        // 3. Dispatch selon type et destinataire
        when {
            // Paquet qui nous est destiné
            packet.recipientId == localShortId -> {
                onMessageForUs?.invoke(packet)
            }

            // Gossip de prekeys — on cache et on relaie
            packet.type == PacketType.PREKEY_GOSSIP -> {
                preKeyCache[packet.senderId] = packet.payload
                onPreKeyReceived?.invoke(packet.senderId, packet.payload)
                relayIfPossible(packet)
            }

            // Tout le reste avec TTL restant — on relaie
            packet.ttl > 0 -> relayIfPossible(packet)
        }
    }

    /**
     * Relaie un paquet en décrémentant le TTL.
     */
    private fun relayIfPossible(packet: RelayPacket) {
        val decremented = packet.decrementTtl()
        if (decremented.ttl >= 0) {
            onPacketToRelay?.invoke(decremented)
        }
    }

    /**
     * Récupère les prekeys cachées d'un pair.
     * Null si jamais vues dans le réseau.
     */
    fun getCachedPreKeys(peerId: String): ByteArray? = preKeyCache[peerId]
}
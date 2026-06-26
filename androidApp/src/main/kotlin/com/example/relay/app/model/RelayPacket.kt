package com.relay.app.model

/**
 * Un paquet Relay — l'unité qui circule dans le mesh.
 *
 * Tout ce qui transite entre nœuds est un RelayPacket.
 * Le type détermine comment le récepteur le traite.
 */
data class RelayPacket(
    val id: String,                    // UUID unique — pour la déduplication
    val type: PacketType,
    val senderId: String,              // shortId de l'expéditeur
    val recipientId: String?,          // null = broadcast (gossip, etc.)
    val payload: ByteArray,            // contenu sérialisé selon le type
    val signature: ByteArray,          // signature Ed25519 de l'expéditeur
    val ttl: Int = 7,                  // nombre de sauts restants
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() + TTL_48H
) {
    companion object {
        const val TTL_48H = 48L * 60 * 60 * 1000  // 48 heures en ms
        const val MAX_HOPS = 7
    }

    /**
     * Le paquet est-il encore valide ?
     * Vérifie TTL (sauts) et expiration temporelle.
     */
    fun isValid(): Boolean {
        return ttl > 0 && System.currentTimeMillis() < expiresAt
    }

    /**
     * Crée une copie avec TTL décrémenté pour le relais.
     */
    fun decrementTtl(): RelayPacket = copy(ttl = ttl - 1)
}

enum class PacketType {
    MESSAGE,          // Message chiffré Double Ratchet
    PREKEY_GOSSIP,    // Bundle de prekeys signé pour propagation
    DEFERRED_INIT,    // Initiation de session différée
    ACK               // Accusé de réception
}
package com.relay.app.crypto

import com.relay.app.model.PacketType
import com.relay.app.model.RelayPacket
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.Curve
import java.security.SecureRandom
import java.util.UUID

/**
 * Gère les sessions différées — niveau ORANGE.
 *
 * Utilisé quand Alice veut contacter Bob mais :
 * - Bob n'est pas dans le réseau mesh actuellement
 * - Personne n'a encore relayé ses prekeys
 *
 * Alice envoie un paquet d'initiation signé dans le réseau.
 * Le paquet attend que Bob réapparaisse (jusqu'à 48h).
 * Protection anti-replay : nonce à usage unique + expiration.
 */
class DeferredSession(private val localIdentity: IdentityKeyPair) {

    // Nonces déjà vus — évite les attaques de replay
    // Sprint 3 : persisté dans Room pour survivre aux redémarrages
    private val seenNonces = mutableSetOf<String>()

    /**
     * Crée un paquet d'initiation de session différée.
     *
     * @param localShortId  Notre identifiant court
     * @param recipientId   L'identifiant du destinataire visé
     * @param initialMessage Message à livrer quand Bob réapparaît
     */
    fun createDeferredPacket(
        localShortId: String,
        recipientId: String,
        initialMessage: String
    ): RelayPacket {
        // Nonce aléatoire — usage unique, anti-replay
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val nonceHex = nonce.joinToString("") { "%02x".format(it) }

        // Payload : nonce + message en clair pour l'instant
        // Sprint 2 : chiffrement asymétrique du message avec la clé publique
        // de Bob sera ajouté quand on aura son bundle
        val payload = buildPayload(nonceHex, recipientId, initialMessage)

        // Signature du payload complet avec notre clé Ed25519
        val signature = Curve.calculateSignature(
            localIdentity.privateKey,
            payload
        )

        return RelayPacket(
            id = UUID.randomUUID().toString(),
            type = PacketType.DEFERRED_INIT,
            senderId = localShortId,
            recipientId = recipientId,
            payload = payload,
            signature = signature,
            ttl = RelayPacket.MAX_HOPS,
            expiresAt = System.currentTimeMillis() + RelayPacket.TTL_48H
        )
    }

    /**
     * Vérifie un paquet d'initiation reçu.
     * Retourne le message initial si le paquet est valide, null sinon.
     */
    fun verifyDeferredPacket(
        packet: RelayPacket,
        senderPublicKey: org.signal.libsignal.protocol.IdentityKey
    ): String? {
        return try {
            // Vérifie la signature
            val isValid = Curve.verifySignature(
                senderPublicKey.publicKey,
                packet.payload,
                packet.signature
            )
            if (!isValid) return null

            // Vérifie le paquet n'est pas expiré
            if (!packet.isValid()) return null

            // Extrait le nonce et vérifie qu'il n'a pas déjà été vu
            val (nonceHex, _, message) = parsePayload(packet.payload)
            if (seenNonces.contains(nonceHex)) return null

            // Marque le nonce comme utilisé
            seenNonces.add(nonceHex)

            message
        } catch (e: Exception) {
            null
        }
    }

    private fun buildPayload(
        nonce: String,
        recipientId: String,
        message: String
    ): ByteArray {
        return "$nonce|$recipientId|$message".toByteArray(Charsets.UTF_8)
    }

    private fun parsePayload(bytes: ByteArray): Triple<String, String, String> {
        val parts = String(bytes, Charsets.UTF_8).split("|", limit = 3)
        return Triple(parts[0], parts[1], parts[2])
    }
}
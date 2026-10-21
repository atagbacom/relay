package com.relay.app.crypto

import com.relay.app.model.PacketType
import com.relay.app.model.RelayPacket
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.util.Base64
import java.util.UUID

/**
 * Gère la propagation des prekeys dans le réseau mesh.
 *
 * Quand Bob est présent dans le réseau, il diffuse son bundle
 * de prekeys signé par sa clé Ed25519. Chaque nœud cache ce bundle.
 * Quand Alice cherche à contacter Bob via un intermédiaire,
 * elle reçoit le bundle et vérifie la signature avant de l'utiliser.
 *
 * Résultat : niveau de confiance YELLOW.
 */
class PreKeyGossip(private val localIdentity: IdentityKeyPair) {

    /**
     * Crée un paquet gossip contenant le bundle de prekeys local.
     * Ce paquet est diffusé à tous les nœuds voisins.
     */
    fun createGossipPacket(
        shortId: String,
        bundle: PreKeyBundle,
        nom: String? = null
    ): RelayPacket {
        // Sérialise le bundle, suivi du nom choisi : signé avec lui, il ne peut
        // pas être changé en route. Un changement de nom se propage ainsi sans
        // nouveau scan (avant : il ne partait que dans le QR).
        val bundleBytes = serializeBundle(bundle, nom)

        // Signe le bundle avec la clé privée Ed25519 locale
        // Quiconque reçoit ce paquet peut vérifier la signature
        // avec la clé publique contenue dans le bundle lui-même
        val signature = Curve.calculateSignature(
            localIdentity.privateKey,
            bundleBytes
        )

        return RelayPacket(
            id = UUID.randomUUID().toString(),
            type = PacketType.PREKEY_GOSSIP,
            senderId = shortId,
            recipientId = null,        // broadcast
            payload = bundleBytes,
            signature = signature,
            ttl = RelayPacket.MAX_HOPS
        )
    }

    /**
     * Vérifie un paquet gossip reçu.
     * Retourne le bundle si la signature est valide, null sinon.
     *
     * C'est ici que l'attaque de Bitchat est bloquée :
     * un nœud malveillant ne peut pas injecter une fausse clé
     * parce qu'il ne peut pas signer avec la clé privée de Bob.
     */
    fun verifyAndExtract(packet: RelayPacket): PreKeyBundle? {
        return try {
            val bundle = deserializeBundle(packet.payload)

            // La clé publique du bundle est la clé de Bob
            // On vérifie que la signature a bien été produite
            // par la clé privée correspondante
            val isValid = Curve.verifySignature(
                bundle.identityKey.publicKey,
                packet.payload,
                packet.signature
            )

            // La clé doit aussi correspondre à l'identifiant annoncé : sinon
            // n'importe qui pourrait signer son propre bundle au nom d'un autre.
            val bonExpediteur = RelayIdentity.shortIdOf(bundle.identityKey) == packet.senderId

            if (isValid && bonExpediteur) bundle else null
        } catch (e: Exception) {
            // Signature invalide ou bundle corrompu — on ignore silencieusement
            null
        }
    }

    // Sérialisation simple pour le Sprint 2
    // Sprint 3 : on migre vers Protocol Buffers pour l'efficacité réseau
    /** Nom annoncé dans un paquet déjà vérifié par [verifyAndExtract], s'il y en a un. */
    fun nomDe(packet: RelayPacket): String? = runCatching {
        String(packet.payload, Charsets.UTF_8).split("|").getOrNull(8)
            ?.takeIf { it.isNotEmpty() }
            ?.let { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8).trim().take(40) }
            ?.ifEmpty { null }
    }.getOrNull()

    private fun serializeBundle(bundle: PreKeyBundle, nom: String?): ByteArray {
        val parts = listOf(
            bundle.registrationId.toString(),
            bundle.deviceId.toString(),
            bundle.preKeyId.toString(),
            bundle.preKey?.serialize()?.let { Base64.getEncoder().encodeToString(it) } ?: "",
            bundle.signedPreKeyId.toString(),
            bundle.signedPreKey.serialize().let { Base64.getEncoder().encodeToString(it) },
            Base64.getEncoder().encodeToString(bundle.signedPreKeySignature),
            Base64.getEncoder().encodeToString(bundle.identityKey.serialize()),
            // 9e champ, ignoré par les versions précédentes ; base64 URL : jamais de « | »
            nom?.trim()?.take(40)?.takeIf { it.isNotEmpty() }
                ?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) } ?: ""
        )
        return parts.joinToString("|").toByteArray(Charsets.UTF_8)
    }

    private fun deserializeBundle(bytes: ByteArray): PreKeyBundle {
        val parts = String(bytes, Charsets.UTF_8).split("|")
        val decode = { s: String -> Base64.getDecoder().decode(s) }

        return PreKeyBundle(
            parts[0].toInt(),
            parts[1].toInt(),
            parts[2].toInt(),
            if (parts[3].isEmpty()) null else org.signal.libsignal.protocol.ecc.Curve.decodePoint(decode(parts[3]), 0),
            parts[4].toInt(),
            org.signal.libsignal.protocol.ecc.Curve.decodePoint(decode(parts[5]), 0),
            decode(parts[6]),
            org.signal.libsignal.protocol.IdentityKey(decode(parts[7]), 0)
        )
    }
}
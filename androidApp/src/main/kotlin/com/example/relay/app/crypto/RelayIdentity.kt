package com.relay.app.crypto

import org.signal.libsignal.protocol.IdentityKeyPair

class RelayIdentity {

    companion object {

        /**
         * Génère une nouvelle identité cryptographique.
         * IdentityKeyPair contient :
         * - clé publique Ed25519 (partageable)
         * - clé privée Ed25519 (ne quitte jamais l'appareil)
         */
        fun generate(): IdentityKeyPair {
            return IdentityKeyPair.generate()
        }

        /**
         * Sérialise pour stockage Room.
         * Ne stocke jamais la clé privée en clair hors du keystore.
         */
        fun serialize(identity: IdentityKeyPair): ByteArray {
            return identity.serialize()
        }

        /**
         * Recharge depuis le stockage.
         */
        fun deserialize(bytes: ByteArray): IdentityKeyPair {
            return IdentityKeyPair(bytes)
        }

        /**
         * Identifiant public lisible — les 8 premiers bytes de la clé publique
         * encodés en hex. Ex : "a3f7b2c1"
         * C'est ce que l'utilisateur voit comme son identité dans l'UI.
         */
        fun shortId(identity: IdentityKeyPair): String {
            val pubKeyBytes = identity.publicKey.publicKey.serialize()
            return pubKeyBytes.take(4).joinToString("") {
                "%02x".format(it)
            }
        }
    }
}
package com.relay.app.crypto

import org.signal.libsignal.protocol.IdentityKey
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

        /** Octets de hachage gardés dans l'identifiant : 64 bits, 16 caractères hexadécimaux. */
        const val OCTETS_ID = 8

        /**
         * Identifiant public : les 8 premiers octets du SHA-256 de la clé
         * publique, en hexadécimal (16 caractères).
         *
         * Avant : les 4 premiers octets de la clé elle-même, dont le premier vaut
         * toujours 05. Il restait 24 bits : fabriquer une clé au même
         * identifiant qu'une victime prenait quelques secondes (issue #12).
         * Avec 64 bits, il faudrait des milliards de milliards d'essais. La
         * vérification en personne reste la seule garantie forte.
         */
        fun shortId(identity: IdentityKeyPair): String = shortIdOf(identity.publicKey)

        /** Le même identifiant, calculé depuis la clé publique d'un correspondant. */
        fun shortIdOf(key: IdentityKey): String = idDeCle(key.publicKey.serialize())

        /** Depuis la clé sérialisée (33 octets) : sert aussi à la migration de la base. */
        fun idDeCle(cle: ByteArray): String =
            java.security.MessageDigest.getInstance("SHA-256").digest(cle)
                .take(OCTETS_ID).joinToString("") { "%02x".format(it) }

        /** Identifiant de l'ancien format (8 caractères) : téléphone resté sur une version incompatible. */
        fun ancienFormat(id: String): Boolean = id.length == 8

        /** Affichage en groupes de 4 : « 96a0 1e6b 435b 11ab ». */
        fun lisible(id: String): String = id.chunked(4).joinToString(" ")
    }
}
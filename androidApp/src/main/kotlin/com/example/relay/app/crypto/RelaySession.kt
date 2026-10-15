package com.relay.app.crypto

import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import kotlin.random.Random

/**
 * Sessions Signal de l'appareil, adossées à UN store : celui qui publie les
 * clés pré-signées est aussi celui qui déchiffre le premier message reçu.
 */
class RelaySession(
    private val localIdentity: IdentityKeyPair,
    private val store: SignalProtocolStore
) {

    /** Store en mémoire, pour les tests. */
    constructor(localIdentity: IdentityKeyPair, localRegistrationId: Int) :
        this(localIdentity, InMemorySignalProtocolStore(localIdentity, localRegistrationId))

    /**
     * Crée un bundle de prekeys à partager avec les pairs.
     * Ce bundle est ce que Bob diffuse pour qu'Alice
     * puisse initier une session même si Bob est absent.
     *
     * @param avecCleUnique inclure une clé à usage unique. Un bundle diffusé à
     * tout le mesh n'en met pas : le premier à l'utiliser la consommerait, et
     * les messages des suivants deviendraient indéchiffrables.
     */
    fun createPreKeyBundle(avecCleUnique: Boolean = true): PreKeyBundle {
        val signee = cleSigneeCourante()

        var preKeyId = -1
        var preKeyPublique: org.signal.libsignal.protocol.ecc.ECPublicKey? = null
        if (avecCleUnique) {
            val paire = Curve.generateKeyPair()
            preKeyId = idLibre { store.containsPreKey(it) }
            // La partie privée reste chez nous : sans elle, le premier message
            // reçu (PreKeySignalMessage) ne peut pas être déchiffré.
            store.storePreKey(preKeyId, PreKeyRecord(preKeyId, paire))
            preKeyPublique = paire.publicKey
        }

        return PreKeyBundle(
            store.localRegistrationId,
            DEVICE_ID,
            preKeyId,
            preKeyPublique,
            signee.id,
            signee.keyPair.publicKey,
            signee.signature,
            localIdentity.publicKey
        )
    }

    /**
     * Ouvre une session avec un pair à partir de son bundle vérifié,
     * si aucune n'existe encore. Retourne true si une session a été créée.
     */
    fun accepterBundle(adresse: SignalProtocolAddress, bundle: PreKeyBundle): Boolean {
        if (store.containsSession(adresse)) return false
        SessionBuilder(store, adresse).process(bundle)
        return true
    }

    /**
     * Initie une session avec un pair via son PreKeyBundle.
     * C'est l'implémentation X3DH côté Alice.
     */
    fun initiateSession(
        recipientAddress: SignalProtocolAddress,
        recipientBundle: PreKeyBundle
    ): SessionCipher {
        val sessionBuilder = SessionBuilder(store, recipientAddress)
        sessionBuilder.process(recipientBundle)
        return SessionCipher(store, recipientAddress)
    }

    /**
     * Récupère un SessionCipher pour un pair existant.
     */
    fun getSessionCipher(address: SignalProtocolAddress): SessionCipher {
        return SessionCipher(store, address)
    }

    /**
     * La clé pré-signée en cours, renouvelée chaque semaine. Les anciennes
     * restent un mois, le temps que les messages encore en route arrivent.
     */
    private fun cleSigneeCourante(): SignedPreKeyRecord {
        val maintenant = System.currentTimeMillis()
        val existantes = store.loadSignedPreKeys()
        existantes
            .filter { maintenant - it.timestamp > CONSERVATION_MS }
            .forEach { store.removeSignedPreKey(it.id) }

        existantes.maxByOrNull { it.timestamp }
            ?.takeIf { maintenant - it.timestamp < ROTATION_MS }
            ?.let { return it }

        val paire = Curve.generateKeyPair()
        val signature = Curve.calculateSignature(localIdentity.privateKey, paire.publicKey.serialize())
        val id = idLibre { store.containsSignedPreKey(it) }
        val record = SignedPreKeyRecord(id, maintenant, paire, signature)
        store.storeSignedPreKey(id, record)
        return record
    }

    private fun idLibre(pris: (Int) -> Boolean): Int {
        var id = Random.nextInt(1, ID_MAX)
        while (pris(id)) id = if (id >= ID_MAX - 1) 1 else id + 1
        return id
    }

    companion object {
        const val DEVICE_ID = 1
        private const val ID_MAX = 0xFFFFFF
        private const val ROTATION_MS = 7L * 24 * 60 * 60 * 1000
        private const val CONSERVATION_MS = 30L * 24 * 60 * 60 * 1000

        /** Identifiant d'enregistrement Signal stable, dans la plage autorisée (1 à 16380). */
        fun registrationIdPour(shortId: String): Int = (shortId.hashCode() and 0x3FFF) % 16380 + 1
    }
}

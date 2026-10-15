package com.relay.app.crypto

import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord

class RelaySession(
    private val localIdentity: IdentityKeyPair,
    private val localRegistrationId: Int
) {

    // Store en mémoire pour le Sprint 1
    // Sprint 2 : on migre vers Room pour la persistance
    private val store = InMemorySignalProtocolStore(
        localIdentity,
        localRegistrationId
    )

    /**
     * Crée un bundle de prekeys à partager avec les pairs.
     * Ce bundle est ce que Bob diffuse pour qu'Alice
     * puisse initier une session même si Bob est absent.
     */
    fun createPreKeyBundle(): PreKeyBundle {
        val preKeyPair = Curve.generateKeyPair()
        val signedPreKeyPair = Curve.generateKeyPair()
        val signedPreKeySignature = Curve.calculateSignature(
            localIdentity.privateKey,
            signedPreKeyPair.publicKey.serialize()
        )

        // Les parties privées restent chez nous : sans elles, le premier
        // message (PreKeySignalMessage) reçu ne peut pas être déchiffré.
        store.storePreKey(PRE_KEY_ID, PreKeyRecord(PRE_KEY_ID, preKeyPair))
        store.storeSignedPreKey(
            SIGNED_PRE_KEY_ID,
            SignedPreKeyRecord(
                SIGNED_PRE_KEY_ID,
                System.currentTimeMillis(),
                signedPreKeyPair,
                signedPreKeySignature
            )
        )

        return PreKeyBundle(
            localRegistrationId,
            1,           // deviceId
            PRE_KEY_ID,
            preKeyPair.publicKey,
            SIGNED_PRE_KEY_ID,
            signedPreKeyPair.publicKey,
            signedPreKeySignature,
            localIdentity.publicKey
        )
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

    private companion object {
        const val PRE_KEY_ID = 1
        const val SIGNED_PRE_KEY_ID = 1
    }
}
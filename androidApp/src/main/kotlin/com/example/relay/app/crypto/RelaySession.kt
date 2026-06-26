package com.relay.app.crypto

import org.signal.libsignal.protocol.*
import org.signal.libsignal.protocol.ecc.Curve
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import org.signal.libsignal.protocol.state.PreKeyBundle

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

        return PreKeyBundle(
            localRegistrationId,
            1,           // deviceId
            1,           // preKeyId
            preKeyPair.publicKey,
            1,           // signedPreKeyId
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
}
package com.relay.app.crypto

import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage

data class EncryptedPayload(
    val ciphertext: ByteArray,
    val isPreKeyMessage: Boolean  // true = premier message, false = suivants
)

class RelayMessage {

    companion object {

        /**
         * Chiffre un message texte avec Double Ratchet.
         * Chaque appel fait avancer le ratchet —
         * chaque message a une clé différente.
         */
        fun encrypt(cipher: SessionCipher, plaintext: String): EncryptedPayload {
            val message = cipher.encrypt(plaintext.toByteArray(Charsets.UTF_8))
            return EncryptedPayload(
                ciphertext = message.serialize(),
                isPreKeyMessage = message is PreKeySignalMessage
            )
        }

        /**
         * Déchiffre. Le ratchet avance côté destinataire aussi.
         * Si le message est un PreKeyMessage (premier contact),
         * la session s'établit automatiquement.
         */
        fun decrypt(cipher: SessionCipher, payload: EncryptedPayload): String {
            val plaintext = if (payload.isPreKeyMessage) {
                cipher.decrypt(PreKeySignalMessage(payload.ciphertext))
            } else {
                cipher.decrypt(SignalMessage(payload.ciphertext))
            }
            return String(plaintext, Charsets.UTF_8)
        }
    }
}
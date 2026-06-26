package com.relay.app.model

import com.relay.app.crypto.TrustLevel
import org.signal.libsignal.protocol.IdentityKey

/**
 * Un contact dans Relay.
 *
 * @param shortId       Identifiant lisible — 8 chars hex de sa clé publique
 * @param publicKey     Sa clé publique Ed25519 — reçue par un des 3 canaux
 * @param trustLevel    Niveau de confiance actuel
 * @param alias         Nom optionnel donné par l'utilisateur local
 * @param firstSeenAt   Timestamp du premier contact
 * @param lastSeenAt    Timestamp du dernier message reçu ou relayé
 */
data class RelayContact(
    val shortId: String,
    val publicKey: IdentityKey,
    val trustLevel: TrustLevel,
    val alias: String? = null,
    val firstSeenAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis()
) {
    /**
     * Nom affiché dans l'UI.
     * Alias si défini, sinon shortId.
     */
    val displayName: String
        get() = alias ?: shortId

    /**
     * Monte le niveau de confiance du contact.
     * Retourne une copie avec le nouveau niveau.
     */
    fun upgrade(to: TrustLevel): RelayContact {
        return copy(trustLevel = trustLevel.upgradeTo(to))
    }
}
package com.relay.app.crypto

import org.junit.Assert.*
import org.junit.Test

class VerificationMutuelleTest {

    private val nonces = VerificationMutuelle.Nonces

    @Test
    fun `celui qui scanne est reconnu par celui qui a été scanné`() {
        val a = RelayIdentity.generate()
        val nonce = nonces.emettre()
        val contenu = VerificationMutuelle.contenu(a, nonce, "Aminata")
        val sig = VerificationMutuelle.signer(a, contenu)

        val recue = VerificationMutuelle.verifier(RelayIdentity.shortId(a), contenu, sig) { nonces.consommer(it) }
        assertNotNull(recue)
        assertEquals(RelayIdentity.shortId(a), recue!!.shortId)
        assertEquals(a.publicKey, recue.cle)
        assertEquals("Aminata", recue.nom)
    }

    @Test
    fun `le code à usage unique ne sert qu'une fois`() {
        val n = nonces.emettre()
        assertTrue(nonces.consommer(n))
        assertFalse(nonces.consommer(n))
    }

    @Test
    fun `un code expiré ou inconnu est refusé`() {
        val n = nonces.emettre(maintenant = 1_000L)
        assertFalse(nonces.consommer(n, maintenant = 1_000L + 6 * 60 * 1000L))
        assertFalse(nonces.consommer("code-jamais-emis"))
    }

    @Test
    fun `une clé annoncée sous l'identifiant d'un autre est refusée`() {
        val attaquant = RelayIdentity.generate()
        val victime = RelayIdentity.generate()
        val nonce = nonces.emettre()
        val contenu = VerificationMutuelle.contenu(attaquant, nonce, null)
        val sig = VerificationMutuelle.signer(attaquant, contenu)
        assertNull(VerificationMutuelle.verifier(RelayIdentity.shortId(victime), contenu, sig) { nonces.consommer(it) })
        // le code n'a pas été consommé par la tentative refusée
        assertTrue(nonces.consommer(nonce))
    }

    @Test
    fun `un contenu modifié après signature est refusé`() {
        val a = RelayIdentity.generate()
        val nonce = nonces.emettre()
        val contenu = VerificationMutuelle.contenu(a, nonce, "Aminata")
        val sig = VerificationMutuelle.signer(a, contenu)
        val modifie = contenu.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertNull(VerificationMutuelle.verifier(RelayIdentity.shortId(a), modifie, sig) { nonces.consommer(it) })
    }
}

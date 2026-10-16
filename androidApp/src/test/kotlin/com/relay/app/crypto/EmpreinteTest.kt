package com.relay.app.crypto

import org.junit.Assert.*
import org.junit.Test

class EmpreinteTest {

    @Test
    fun `empreinte en quatre groupes de quatre caractères`() {
        val e = Empreinte.de(RelayIdentity.generate().publicKey)
        assertTrue(e, Regex("[0-9A-F]{4}( [0-9A-F]{4}){3}").matches(e))
    }

    @Test
    fun `même clé même empreinte, clés différentes empreintes différentes`() {
        val a = RelayIdentity.generate().publicKey
        val b = RelayIdentity.generate().publicKey
        assertEquals(Empreinte.de(a), Empreinte.de(a))
        assertNotEquals(Empreinte.de(a), Empreinte.de(b))
    }

    @Test
    fun `les deux téléphones voient les mêmes quatre mots`() {
        val a = RelayIdentity.generate().publicKey
        val b = RelayIdentity.generate().publicKey
        val chezA = Empreinte.motsDeSecurite(a, b)
        val chezB = Empreinte.motsDeSecurite(b, a)
        assertEquals(4, chezA.size)
        assertEquals(chezA, chezB)
        assertTrue(chezA.all { it in Empreinte.MOTS })
    }

    @Test
    fun `un intermédiaire change les mots`() {
        val alice = RelayIdentity.generate().publicKey
        val bob = RelayIdentity.generate().publicKey
        val intrus = RelayIdentity.generate().publicKey
        // Alice croit voir Bob mais a scanné l'intrus ; Bob voit l'intrus aussi
        val chezAlice = Empreinte.motsDeSecurite(alice, intrus)
        val chezBob = Empreinte.motsDeSecurite(bob, intrus)
        assertNotEquals(chezAlice, chezBob)
    }

    @Test
    fun `liste de mots, 64 mots distincts`() {
        assertEquals(64, Empreinte.MOTS.size)
        assertEquals(64, Empreinte.MOTS.toSet().size)
    }
}

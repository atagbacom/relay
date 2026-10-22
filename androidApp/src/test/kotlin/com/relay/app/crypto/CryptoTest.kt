package com.relay.app.crypto

import org.junit.Assert.*
import org.junit.Test
import org.signal.libsignal.protocol.SignalProtocolAddress

class CryptoTest {

    @Test
    fun `génération identité produit des clés valides`() {
        val identity = RelayIdentity.generate()
        assertNotNull(identity.publicKey)
        assertNotNull(identity.privateKey)
    }

    @Test
    fun `sérialisation identité est réversible`() {
        val original = RelayIdentity.generate()
        val bytes = RelayIdentity.serialize(original)
        val restored = RelayIdentity.deserialize(bytes)

        assertEquals(
            original.publicKey.publicKey.serialize().toList(),
            restored.publicKey.publicKey.serialize().toList()
        )
    }

    @Test
    fun `identifiant de 64 bits tiré du hachage de la clé`() {
        val identity = RelayIdentity.generate()
        val id = RelayIdentity.shortId(identity)
        assertEquals(16, id.length)
        assertTrue(id.matches(Regex("[0-9a-f]+")))
        assertNotEquals(id, RelayIdentity.shortId(RelayIdentity.generate()))
        assertFalse(RelayIdentity.ancienFormat(id))
    }

    @Test
    fun `identifiant stable pour une clé donnée`() {
        // SHA-256 de 05 suivi de 32 zéros, 8 premiers octets
        assertEquals("96a01e6b435b11ab", RelayIdentity.idDeCle(byteArrayOf(5) + ByteArray(32)))
        assertEquals("96a0 1e6b 435b 11ab", RelayIdentity.lisible("96a01e6b435b11ab"))
    }

    @Test
    fun `chiffrement et déchiffrement X3DH bout en bout`() {
        // Alice
        val aliceIdentity = RelayIdentity.generate()
        val aliceSession = RelaySession(aliceIdentity, 1)
        val aliceAddress = SignalProtocolAddress("alice", 1)

        // Bob
        val bobIdentity = RelayIdentity.generate()
        val bobSession = RelaySession(bobIdentity, 2)
        val bobAddress = SignalProtocolAddress("bob", 1)

        // Bob génère son bundle de prekeys
        val bobBundle = bobSession.createPreKeyBundle()

        // Alice initie une session avec Bob via son bundle
        val aliceToBobCipher = aliceSession.initiateSession(bobAddress, bobBundle)

        // Alice chiffre un message
        val plaintext = "Relay fonctionne sans serveur"
        val encrypted = RelayMessage.encrypt(aliceToBobCipher, plaintext)

        // Bob déchiffre
        val bobFromAliceCipher = bobSession.getSessionCipher(aliceAddress)
        val decrypted = RelayMessage.decrypt(bobFromAliceCipher, encrypted)

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun `double ratchet, clé différente à chaque message`() {
        val aliceIdentity = RelayIdentity.generate()
        val aliceSession = RelaySession(aliceIdentity, 1)
        val bobIdentity = RelayIdentity.generate()
        val bobSession = RelaySession(bobIdentity, 2)
        val bobAddress = SignalProtocolAddress("bob", 1)

        val bobBundle = bobSession.createPreKeyBundle()
        val cipher = aliceSession.initiateSession(bobAddress, bobBundle)

        val msg1 = RelayMessage.encrypt(cipher, "message 1")
        val msg2 = RelayMessage.encrypt(cipher, "message 2")

        // Les ciphertexts doivent être différents même pour des messages identiques
        assertFalse(msg1.ciphertext.toList() == msg2.ciphertext.toList())
    }
}
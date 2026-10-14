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
    fun `shortId produit 8 caractères hex`() {
        val identity = RelayIdentity.generate()
        val id = RelayIdentity.shortId(identity)
        assertEquals(8, id.length)
        assertTrue(id.matches(Regex("[0-9a-f]+")))
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
    fun `double ratchet : clé différente à chaque message`() {
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
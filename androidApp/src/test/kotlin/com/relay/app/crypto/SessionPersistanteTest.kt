package com.relay.app.crypto

import com.relay.app.storage.SignalRecordDao
import com.relay.app.storage.SignalRecordEntity
import org.junit.Assert.*
import org.junit.Test
import org.signal.libsignal.protocol.SignalProtocolAddress

/** Base Room simulée : ce qui y est écrit survit à la « reconstruction » du store. */
private class FausseBase : SignalRecordDao {
    private val lignes = linkedMapOf<Pair<String, String>, SignalRecordEntity>()
    override fun getAll() = lignes.values.toList()
    override fun save(record: SignalRecordEntity) { lignes[record.kind to record.recordKey] = record }
    override fun delete(kind: String, recordKey: String) { lignes.remove(kind to recordKey) }
}

class SessionPersistanteTest {

    private val adresseAlice = SignalProtocolAddress("alice", RelaySession.DEVICE_ID)
    private val adresseBob = SignalProtocolAddress("bob", RelaySession.DEVICE_ID)

    @Test
    fun `bundle diffusé sans clé à usage unique, le premier message se déchiffre`() {
        val bob = RelaySession(RelayIdentity.generate(), 2)
        val alice = RelaySession(RelayIdentity.generate(), 1)

        val bundle = bob.createPreKeyBundle(avecCleUnique = false)
        assertNull(bundle.preKey)
        assertTrue(alice.accepterBundle(adresseBob, bundle))

        val chiffre = RelayMessage.encrypt(alice.getSessionCipher(adresseBob), "Route coupée au pont")
        assertTrue(chiffre.isPreKeyMessage)
        assertEquals(
            "Route coupée au pont",
            RelayMessage.decrypt(bob.getSessionCipher(adresseAlice), chiffre)
        )
    }

    @Test
    fun `accepterBundle n'écrase pas une session existante`() {
        val bob = RelaySession(RelayIdentity.generate(), 2)
        val alice = RelaySession(RelayIdentity.generate(), 1)

        assertTrue(alice.accepterBundle(adresseBob, bob.createPreKeyBundle(avecCleUnique = false)))
        assertFalse(alice.accepterBundle(adresseBob, bob.createPreKeyBundle(avecCleUnique = false)))
    }

    @Test
    fun `la clé pré-signée est réutilisée tant qu'elle a moins d'une semaine`() {
        val bob = RelaySession(RelayIdentity.generate(), 2)
        val premier = bob.createPreKeyBundle(avecCleUnique = false)
        val second = bob.createPreKeyBundle(avecCleUnique = false)
        assertEquals(premier.signedPreKeyId, second.signedPreKeyId)
    }

    @Test
    fun `après un redémarrage, le store rechargé déchiffre encore`() {
        val base = FausseBase()
        val identiteBob = RelayIdentity.generate()

        // Bob publie ses clés, puis son téléphone redémarre
        val bobAvant = RelaySession(identiteBob, PersistentSignalStore(identiteBob, 2, base))
        val bundle = bobAvant.createPreKeyBundle(avecCleUnique = false)

        val alice = RelaySession(RelayIdentity.generate(), 1)
        alice.accepterBundle(adresseBob, bundle)
        val premier = RelayMessage.encrypt(alice.getSessionCipher(adresseBob), "Premier message")

        val bobApres = RelaySession(identiteBob, PersistentSignalStore(identiteBob, 2, base))
        assertEquals("Premier message", RelayMessage.decrypt(bobApres.getSessionCipher(adresseAlice), premier))

        // Et la session ouverte par ce premier message survit à un second redémarrage
        val second = RelayMessage.encrypt(alice.getSessionCipher(adresseBob), "Second message")
        val bobEncore = RelaySession(identiteBob, PersistentSignalStore(identiteBob, 2, base))
        assertEquals("Second message", RelayMessage.decrypt(bobEncore.getSessionCipher(adresseAlice), second))
    }

    @Test
    fun `des clés signées au nom d'un autre sont rejetées`() {
        val victime = RelayIdentity.generate()
        val attaquant = RelayIdentity.generate()

        // L'attaquant signe correctement SON bundle, mais l'annonce sous l'identifiant de la victime
        val bundle = RelaySession(attaquant, 3).createPreKeyBundle(avecCleUnique = false)
        val paquet = PreKeyGossip(attaquant).createGossipPacket(RelayIdentity.shortId(victime), bundle)

        assertNull(PreKeyGossip(RelayIdentity.generate()).verifyAndExtract(paquet))
    }

    @Test
    fun `registrationId dans la plage autorisée par Signal`() {
        listOf("05a3f7b2", "05000000", "05ffffff", "", "unknown").forEach {
            assertTrue(RelaySession.registrationIdPour(it) in 1..16380)
        }
    }
}

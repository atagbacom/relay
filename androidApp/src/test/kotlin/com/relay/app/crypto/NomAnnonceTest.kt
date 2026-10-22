package com.relay.app.crypto

import org.junit.Assert.*
import org.junit.Test

class NomAnnonceTest {

    private val identite = RelayIdentity.generate()
    private val shortId = RelayIdentity.shortIdOf(identite.publicKey)
    private val gossip = PreKeyGossip(identite)
    private fun bundle() = RelaySession(identite, 1).createPreKeyBundle(avecCleUnique = false)

    @Test
    fun `le nom voyage avec les clés et se lit après vérification`() {
        val paquet = gossip.createGossipPacket(shortId, bundle(), "Ama | Cotonou")
        assertNotNull(gossip.verifyAndExtract(paquet))
        assertEquals("Ama | Cotonou", gossip.nomDe(paquet))
    }

    @Test
    fun `sans nom, les clés restent valides`() {
        val paquet = gossip.createGossipPacket(shortId, bundle())
        assertNotNull(gossip.verifyAndExtract(paquet))
        assertNull(gossip.nomDe(paquet))
    }

    @Test
    fun `un nom changé en route invalide le paquet`() {
        val paquet = gossip.createGossipPacket(shortId, bundle(), "Ama")
        val autre = gossip.createGossipPacket(shortId, bundle(), "Koffi")
        // Même structure, champ du nom (9e) remplacé : la signature ne correspond plus
        val champs = String(paquet.payload).split("|").toMutableList()
        champs[8] = String(autre.payload).split("|")[8]
        val falsifie = paquet.copy(payload = champs.joinToString("|").toByteArray())
        assertNotEquals(String(paquet.payload), String(falsifie.payload))
        assertNull(gossip.verifyAndExtract(falsifie))
    }

    @Test
    fun `la version voyage avec les clés et le nom`() {
        val v = PreKeyGossip.VersionAnnoncee(42, "0.3.0")
        val paquet = gossip.createGossipPacket(shortId, bundle(), "Ama", v)
        assertNotNull(gossip.verifyAndExtract(paquet))
        assertEquals("Ama", gossip.nomDe(paquet))
        assertEquals(v, gossip.versionDe(paquet))
    }

    @Test
    fun `paquet d'une version précédente, sans version`() {
        val paquet = gossip.createGossipPacket(shortId, bundle(), "Ama")
        assertNull(gossip.versionDe(paquet))
    }
}

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
        // Même structure, nom remplacé : la signature ne correspond plus
        val falsifie = paquet.copy(payload = String(paquet.payload).substringBeforeLast("|").plus("|")
            .plus(String(autre.payload).substringAfterLast("|")).toByteArray())
        assertNull(gossip.verifyAndExtract(falsifie))
    }
}

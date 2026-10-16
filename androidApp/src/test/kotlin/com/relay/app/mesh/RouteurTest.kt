package com.relay.app.mesh

import com.relay.app.model.PacketType
import com.relay.app.model.RelayPacket
import org.junit.Assert.*
import org.junit.Test

class RouteurTest {

    private fun paquet(
        id: String,
        type: PacketType = PacketType.MESSAGE,
        de: String = "05aaaaaa",
        pour: String? = "05bbbbbb",
        payload: ByteArray = ByteArray(10),
        ttl: Int = RelayPacket.MAX_HOPS,
    ) = RelayPacket(id = id, type = type, senderId = de, recipientId = pour, payload = payload, signature = ByteArray(64), ttl = ttl)

    @Test
    fun `l'accusé de réception transmet l'identifiant du message accusé`() {
        val r = MeshRouter("05aaaaaa", NodeTracker())
        var accuse: String? = null
        r.onAckReceived = { accuse = it }
        r.handle(paquet("ack-1", PacketType.ACK, de = "05bbbbbb", pour = "05aaaaaa", payload = "message-42".toByteArray()))
        assertEquals("message-42", accuse)
    }

    @Test
    fun `un message pour nous reçu deux fois déclenche un nouvel accusé`() {
        val r = MeshRouter("05bbbbbb", NodeTracker())
        var livres = 0
        var doublons = 0
        r.onMessageForUs = { livres++ }
        r.onDoublonPourNous = { doublons++ }
        val p = paquet("m-1")
        r.handle(p)
        r.handle(p)
        assertEquals(1, livres)
        assertEquals(1, doublons)
    }

    @Test
    fun `un paquet sans relais disponible est gardé puis livré quand son destinataire passe`() {
        val r = MeshRouter("05cccccc", NodeTracker())
        val envoyes = mutableListOf<Pair<String, List<String>>>()
        r.onPacketToRelay = { p, cibles -> envoyes += p.id to cibles }

        r.handle(paquet("m-2", pour = "05dddddd"))
        assertTrue("rien à faire tant que personne n'est là", envoyes.isEmpty())
        assertEquals(1, r.nombreEnAttente())

        r.onNewPeerAvailable("05dddddd", "AA:BB:CC:DD:EE:FF")
        assertEquals(listOf("m-2" to listOf("AA:BB:CC:DD:EE:FF")), envoyes)
        assertEquals(0, r.nombreEnAttente())
    }

    @Test
    fun `un paquet en phase d'attente n'est livré qu'à son destinataire`() {
        val r = MeshRouter("05cccccc", NodeTracker())
        val envoyes = mutableListOf<String>()
        r.onPacketToRelay = { _, cibles -> envoyes += cibles }

        r.handle(paquet("m-3", pour = "05dddddd", ttl = 3))   // une seule copie : phase Wait
        r.onNewPeerAvailable("05eeeeee", "11:11:11:11:11:11")
        assertTrue(envoyes.isEmpty())
        r.onNewPeerAvailable("05dddddd", "22:22:22:22:22:22")
        assertEquals(listOf("22:22:22:22:22:22"), envoyes)
    }

    @Test
    fun `l'adresse Bluetooth d'un appareil est mise à jour quand elle change`() {
        val t = NodeTracker()
        t.seen("05dddddd", "11:11:11:11:11:11", -60)
        t.seen("05dddddd", "22:22:22:22:22:22", -58)
        assertEquals("22:22:22:22:22:22", t.getMacAddress("05dddddd"))
        assertEquals(1, t.activeCount())
    }
}

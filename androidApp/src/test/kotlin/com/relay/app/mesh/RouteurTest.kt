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
        assertEquals("gardé tant que la livraison n'est pas confirmée", 1, r.nombreEnAttente())
        r.livre("m-2")
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

    @Test
    fun `un accusé vu passer fait jeter la copie gardée par le relais`() {
        val r = MeshRouter("05cccccc", NodeTracker())
        val retires = mutableListOf<String>()
        r.onRetrait = { retires += it }
        r.handle(paquet("m-4", de = "05aaaaaa", pour = "05dddddd"))
        r.handle(paquet("ack-4", PacketType.ACK, de = "05dddddd", pour = "05aaaaaa", payload = "m-4".toByteArray()))
        assertEquals(listOf("m-4"), retires)
    }

    @Test
    fun `un message déjà accusé n'est plus gardé ni relayé`() {
        val r = MeshRouter("05cccccc", NodeTracker())
        val gardes = mutableListOf<String>()
        r.onGarde = { gardes += it.id }
        r.handle(paquet("ack-5", PacketType.ACK, de = "05dddddd", pour = "05aaaaaa", payload = "m-5".toByteArray()))
        r.handle(paquet("m-5", de = "05aaaaaa", pour = "05dddddd"))
        assertFalse(gardes.contains("m-5"))
    }

    @Test
    fun `le relais réessaie tant que le destinataire proche n'a pas confirmé`() {
        val t = NodeTracker()
        t.seen("05dddddd", "22:22:22:22:22:22", -60)
        val r = MeshRouter("05cccccc", t)
        val envoyes = mutableListOf<String>()
        r.onPacketToRelay = { p, cibles -> if (p.id == "m-6") envoyes += cibles }
        r.handle(paquet("m-6", de = "05aaaaaa", pour = "05dddddd"))
        assertEquals(listOf("22:22:22:22:22:22"), envoyes)
        r.relancer(System.currentTimeMillis() + 21_000)
        assertEquals(2, envoyes.size)
        r.livre("m-6")
        r.relancer(System.currentTimeMillis() + 60_000)
        assertEquals(2, envoyes.size)
    }

    @Test
    fun `les clés d'un appareil sont remises à celui qui ne l'a jamais croisé`() {
        val r = MeshRouter("05cccccc", NodeTracker())
        val envoyes = mutableListOf<Pair<String, List<String>>>()
        r.onPacketToRelay = { p, cibles -> envoyes += p.id to cibles }
        r.handle(paquet("cles-d", PacketType.PREKEY_GOSSIP, de = "05dddddd", pour = null))
        r.onNewPeerAvailable("05eeeeee", "33:33:33:33:33:33")
        assertEquals(listOf("cles-d" to listOf("33:33:33:33:33:33")), envoyes)
    }

    @Test
    fun `un appareil revenu avec une nouvelle adresse est reconnu par sa connexion`() {
        val t = NodeTracker()
        assertTrue("inconnu jusque-là : nouvelle rencontre", t.vuParConnexion("05dddddd", "44:44:44:44:44:44"))
        assertEquals("44:44:44:44:44:44", t.adresseRecente("05dddddd"))
        assertFalse("déjà à portée : rien à relancer", t.vuParConnexion("05dddddd", "55:55:55:55:55:55"))
        assertEquals("44:44:44:44:44:44", t.getMacAddress("05dddddd"))
    }

    @Test
    fun `une demande de nouvelle session pour nous est remise, sinon relayée`() {
        val r = MeshRouter("05cccccc", NodeTracker())
        val recues = mutableListOf<String>()
        val gardes = mutableListOf<String>()
        r.onResync = { recues += it.id }
        r.onGarde = { gardes += it.id }
        r.handle(paquet("rs-1", PacketType.RESYNC, de = "05dddddd", pour = "05cccccc", payload = "m-9".toByteArray()))
        r.handle(paquet("rs-2", PacketType.RESYNC, de = "05dddddd", pour = "05eeeeee", payload = "m-8".toByteArray()))
        assertEquals(listOf("rs-1"), recues)
        assertEquals(listOf("rs-2"), gardes)
    }

    @Test
    fun `un paquet à la signature invalide n'est ni gardé ni relayé`() {
        val r = MeshRouter("05cccccc", NodeTracker(), verifierSignature = { it.id != "falsifie" })
        val gardes = mutableListOf<String>()
        val rejets = mutableListOf<String>()
        r.onGarde = { gardes += it.id }
        r.onRejet = { p, _ -> rejets += p.id }
        r.handle(paquet("falsifie", pour = "05dddddd"))
        r.handle(paquet("honnete", pour = "05dddddd"))
        assertEquals(listOf("honnete"), gardes)
        assertEquals(listOf("falsifie"), rejets)
    }

    @Test
    fun `clé inconnue, le paquet est relayé et le destinataire vérifiera`() {
        val r = MeshRouter("05cccccc", NodeTracker(), verifierSignature = { null })
        val gardes = mutableListOf<String>()
        r.onGarde = { gardes += it.id }
        r.handle(paquet("inconnu", pour = "05dddddd"))
        assertEquals(listOf("inconnu"), gardes)
    }

    @Test
    fun `un appareil qui inonde le réseau est freiné, ses messages pour nous passent`() {
        var maintenant = 0L
        val r = MeshRouter("05cccccc", NodeTracker(), horloge = { maintenant })
        var gardes = 0
        var pourNous = 0
        r.onGarde = { gardes++ }
        r.onMessageForUs = { pourNous++ }
        repeat(MeshRouter.DEBIT_MAX_PAR_MINUTE + 20) { r.handle(paquet("f-$it", de = "05eeeeee", pour = "05dddddd")) }
        assertEquals(MeshRouter.DEBIT_MAX_PAR_MINUTE, gardes)
        r.handle(paquet("pour-nous", de = "05eeeeee", pour = "05cccccc"))
        assertEquals(1, pourNous)
        maintenant += 61_000
        r.handle(paquet("apres", de = "05eeeeee", pour = "05dddddd"))
        assertEquals(MeshRouter.DEBIT_MAX_PAR_MINUTE + 1, gardes)
    }
}

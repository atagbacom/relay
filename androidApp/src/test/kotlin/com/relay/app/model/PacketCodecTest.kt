package com.relay.app.model

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream

class PacketCodecTest {

    private fun paquet(
        type: PacketType = PacketType.MESSAGE,
        recipientId: String? = "05b2c3d4",
        payload: ByteArray = ByteArray(300) { it.toByte() },
        ttl: Int = RelayPacket.MAX_HOPS
    ) = RelayPacket(
        id = "3f2a9c1e-7b44-4d0a-9e8f-1c2d3e4f5a6b",
        type = type,
        senderId = "05a1b2c3",
        recipientId = recipientId,
        payload = payload,
        signature = ByteArray(64) { (it * 3).toByte() },
        ttl = ttl,
        createdAt = 1_760_000_000_000,
        expiresAt = 1_760_172_800_000
    )

    private fun assertMemePaquet(attendu: RelayPacket, recu: RelayPacket?) {
        assertNotNull(recu)
        recu!!
        assertEquals(attendu.id, recu.id)
        assertEquals(attendu.type, recu.type)
        assertEquals(attendu.senderId, recu.senderId)
        assertEquals(attendu.recipientId, recu.recipientId)
        assertArrayEquals(attendu.payload, recu.payload)
        assertArrayEquals(attendu.signature, recu.signature)
        assertEquals(attendu.ttl, recu.ttl)
        assertEquals(attendu.createdAt, recu.createdAt)
        assertEquals(attendu.expiresAt, recu.expiresAt)
    }

    @Test
    fun `aller-retour pour chaque type de paquet`() {
        PacketType.values().forEach { type ->
            val p = paquet(type = type)
            assertMemePaquet(p, PacketCodec.decode(PacketCodec.encode(p)))
        }
    }

    @Test
    fun `diffusion sans destinataire et payload vide`() {
        val p = paquet(type = PacketType.PREKEY_GOSSIP, recipientId = null, payload = ByteArray(0))
        assertMemePaquet(p, PacketCodec.decode(PacketCodec.encode(p)))
    }

    @Test
    fun `la taille annoncée est celle du paquet, même pile un multiple de 512`() {
        // Recherche d'un payload qui donne un paquet de 1024 octets exactement
        val vide = PacketCodec.encode(paquet(payload = ByteArray(0))).size
        val p = paquet(payload = ByteArray(1024 - vide))
        val octets = PacketCodec.encode(p)
        assertEquals(1024, octets.size)
        assertEquals(1024, PacketCodec.tailleAnnoncee(octets))
        assertEquals(1024, PacketCodec.tailleAnnoncee(octets.copyOf(PacketCodec.TAILLE_EN_TETE)))
    }

    @Test
    fun `paquet tronqué rejeté`() {
        val octets = PacketCodec.encode(paquet())
        listOf(0, 3, PacketCodec.TAILLE_EN_TETE, octets.size / 2, octets.size - 1).forEach {
            assertNull("tronqué à $it", PacketCodec.decode(octets.copyOf(it)))
        }
    }

    @Test
    fun `octets en trop rejetés`() {
        val octets = PacketCodec.encode(paquet())
        assertNull(PacketCodec.decode(octets + byteArrayOf(0)))
    }

    @Test
    fun `autre version ou mauvais en-tête rejetés`() {
        val octets = PacketCodec.encode(paquet())
        assertNull(PacketCodec.decode(octets.copyOf().also { it[2] = 2 }))
        assertNull(PacketCodec.decode(octets.copyOf().also { it[0] = 0 }))
    }

    @Test
    fun `type inconnu et ttl hors limites rejetés`() {
        val octets = PacketCodec.encode(paquet())
        val iType = PacketCodec.TAILLE_EN_TETE
        assertNull(PacketCodec.decode(octets.copyOf().also { it[iType] = 99 }))
        assertNull(PacketCodec.decode(octets.copyOf().also { it[iType + 1] = 50 }))
    }

    @Test
    fun `longueurs internes mensongères rejetées`() {
        val octets = PacketCodec.encode(paquet(payload = ByteArray(10)))
        // Le dernier champ de longueur (payload, 4 octets) annonce un payload énorme
        val iLongueurPayload = octets.size - 10 - 4
        val faux = octets.copyOf().also {
            it[iLongueurPayload] = 0x7F; it[iLongueurPayload + 1] = -1
            it[iLongueurPayload + 2] = -1; it[iLongueurPayload + 3] = -1
        }
        assertNull(PacketCodec.decode(faux))
    }

    @Test
    fun `paquet trop grand refusé à l'encodage et au décodage`() {
        try {
            PacketCodec.encode(paquet(payload = ByteArray(PacketCodec.MAX_PAYLOAD + 1)))
            fail("un payload trop long doit être refusé")
        } catch (e: IllegalArgumentException) { }
        assertNull(PacketCodec.decode(ByteArray(PacketCodec.MAX_TAILLE + 1)))
    }

    @Test
    fun `un flux de sérialisation Java n'est jamais interprété`() {
        val bos = ByteArrayOutputStream()
        ObjectOutputStream(bos).use { it.writeObject(arrayListOf("gadget")) }
        assertNull(PacketCodec.decode(bos.toByteArray()))
    }
}

package com.relay.app.mesh

import com.relay.app.model.PacketCodec
import com.relay.app.model.PacketType
import com.relay.app.model.RelayPacket
import org.junit.Assert.*
import org.junit.Test

class FragmentationTest {

    private fun paquetEncode(tailleCharge: Int): ByteArray = PacketCodec.encode(
        RelayPacket(
            id = "3f2a9c1e-7b44-4d0a-9e8f-1c2d3e4f5a6b",
            type = PacketType.MESSAGE,
            senderId = "05a1b2c3",
            recipientId = "05b2c3d4",
            payload = ByteArray(tailleCharge) { it.toByte() },
            signature = ByteArray(64)
        )
    )

    @Test
    fun `taille des morceaux selon la MTU`() {
        assertEquals(20, Fragmentation.tailleMorceau(23))     // minimum garanti
        assertEquals(20, Fragmentation.tailleMorceau(10))     // MTU absurde : on reste au minimum
        assertEquals(182, Fragmentation.tailleMorceau(185))
        assertEquals(512, Fragmentation.tailleMorceau(517))   // valeur maximale d'une caractéristique
        assertEquals(512, Fragmentation.tailleMorceau(1000))
    }

    @Test
    fun `découpage sans perte, morceaux jamais plus grands que la taille`() {
        val paquet = paquetEncode(1000)
        listOf(20, 182, 512).forEach { taille ->
            val morceaux = Fragmentation.decouper(paquet, taille)
            assertTrue(morceaux.all { it.size in 1..taille })
            assertArrayEquals(paquet, morceaux.reduce { a, b -> a + b })
        }
    }

    @Test
    fun `réassemblage pour plusieurs MTU, y compris paquet multiple exact`() {
        val vide = paquetEncode(0).size
        listOf(paquetEncode(1000), paquetEncode(1024 - vide)).forEach { paquet ->
            listOf(20, 182, 512).forEach { taille ->
                val r = Reassembleur()
                val morceaux = Fragmentation.decouper(paquet, taille)
                morceaux.dropLast(1).forEach { assertNull(r.ajouter("AA:BB", it)) }
                assertArrayEquals(paquet, r.ajouter("AA:BB", morceaux.last()))
            }
        }
    }

    @Test
    fun `deux appareils envoient en même temps sans se mélanger`() {
        val p1 = paquetEncode(300)
        val p2 = paquetEncode(700)
        val m1 = Fragmentation.decouper(p1, 20)
        val m2 = Fragmentation.decouper(p2, 20)
        val r = Reassembleur()
        var recu1: ByteArray? = null
        var recu2: ByteArray? = null
        for (i in 0 until maxOf(m1.size, m2.size)) {
            m1.getOrNull(i)?.let { r.ajouter("A", it)?.let { p -> recu1 = p } }
            m2.getOrNull(i)?.let { r.ajouter("B", it)?.let { p -> recu2 = p } }
        }
        assertArrayEquals(p1, recu1)
        assertArrayEquals(p2, recu2)
    }

    @Test
    fun `en-tête invalide jeté, l'appareil peut renvoyer ensuite`() {
        val r = Reassembleur()
        assertNull(r.ajouter("A", ByteArray(20) { 0x11 }))
        val paquet = paquetEncode(100)
        val morceaux = Fragmentation.decouper(paquet, 20)
        morceaux.dropLast(1).forEach { assertNull(r.ajouter("A", it)) }
        assertArrayEquals(paquet, r.ajouter("A", morceaux.last()))
    }

    @Test
    fun `déconnexion en cours de paquet, le tampon est oublié`() {
        val r = Reassembleur()
        val paquet = paquetEncode(100)
        val morceaux = Fragmentation.decouper(paquet, 20)
        r.ajouter("A", morceaux[0])
        r.oublier("A")
        morceaux.dropLast(1).forEach { assertNull(r.ajouter("A", it)) }
        assertArrayEquals(paquet, r.ajouter("A", morceaux.last()))
    }

    @Test
    fun `envoi interrompu puis nouveau paquet, le nouveau arrive entier`() {
        val r = Reassembleur()
        val coupe = Fragmentation.decouper(paquetEncode(300), 20)
        assertNull(r.ajouter("A", coupe[0]))
        assertNull(r.ajouter("A", coupe[1]))   // la suite ne viendra jamais
        val suivant = paquetEncode(120)
        var complet: ByteArray? = null
        Fragmentation.decouper(suivant, 20).forEach { complet = r.ajouter("A", it) ?: complet }
        assertArrayEquals(suivant, complet)
    }

    @Test
    fun `tampon abandonné trop longtemps oublié`() {
        var maintenant = 0L
        val r = Reassembleur { maintenant }
        val paquet = paquetEncode(100)
        val morceaux = Fragmentation.decouper(paquet, 20)
        assertNull(r.ajouter("A", morceaux[0]))
        maintenant += Reassembleur.EXPIRATION_MS + 1
        // la suite arrive trop tard : rattachée à rien, elle ne forme pas de paquet
        morceaux.drop(1).forEach { assertNull(r.ajouter("A", it)) }
        var complet: ByteArray? = null
        morceaux.forEach { complet = r.ajouter("A", it) ?: complet }
        assertArrayEquals(paquet, complet)
    }
}

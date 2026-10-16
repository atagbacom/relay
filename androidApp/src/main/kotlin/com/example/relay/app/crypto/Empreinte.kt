package com.relay.app.crypto

import org.signal.libsignal.protocol.IdentityKey
import java.security.MessageDigest

/**
 * Ce que deux personnes comparent à l'œil ou à voix haute pour être sûres
 * de parler à la bonne personne.
 */
object Empreinte {

    /** Empreinte lisible d'une clé : 16 caractères hexadécimaux en 4 groupes. */
    fun de(cle: IdentityKey): String =
        sha256(cle.serialize())
            .take(8)
            .joinToString("") { "%02X".format(it) }
            .chunked(4)
            .joinToString(" ")

    /**
     * Quatre mots communs aux deux téléphones : ils dépendent des deux clés,
     * dans n'importe quel ordre. Si quelqu'un s'est interposé, les mots
     * diffèrent d'un téléphone à l'autre.
     */
    fun motsDeSecurite(a: IdentityKey, b: IdentityKey): List<String> {
        val (x, y) = listOf(a.serialize(), b.serialize()).sortedWith { p, q -> comparer(p, q) }
        val h = sha256(x + y)
        // 4 mots × 6 bits : 24 bits, soit plus de 16 millions de combinaisons
        val bits = ((h[0].toInt() and 0xFF) shl 16) or ((h[1].toInt() and 0xFF) shl 8) or (h[2].toInt() and 0xFF)
        return (0 until 4).map { i -> MOTS[(bits shr (18 - 6 * i)) and 0x3F] }
    }

    private fun sha256(octets: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(octets)

    private fun comparer(p: ByteArray, q: ByteArray): Int {
        for (i in 0 until minOf(p.size, q.size)) {
            val d = (p[i].toInt() and 0xFF) - (q[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return p.size - q.size
    }

    /** 64 mots concrets, faciles à prononcer et à distinguer à l'oral. */
    val MOTS = listOf(
        "manguier", "pirogue", "lagune", "tambour", "baobab", "calebasse", "marché", "pagne",
        "colline", "rivière", "soleil", "étoile", "orage", "savane", "palmier", "igname",
        "arachide", "ananas", "papaye", "goyave", "citron", "piment", "maïs", "manioc",
        "cheval", "lion", "tortue", "aigle", "pélican", "crabe", "abeille", "girafe",
        "village", "boussole", "lanterne", "panier", "filet", "rame", "voile", "ancre",
        "montagne", "forêt", "désert", "vallée", "source", "rocher", "sable", "nuage",
        "flûte", "guitare", "balafon", "chanson", "danse", "conte", "miroir", "fenêtre",
        "bateau", "vélo", "train", "pont", "phare", "jardin", "ruche", "plume",
    )
}

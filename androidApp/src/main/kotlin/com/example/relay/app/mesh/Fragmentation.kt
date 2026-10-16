package com.relay.app.mesh

import com.relay.app.model.PacketCodec

/**
 * Découpage d'un paquet en écritures GATT, et réassemblage côté réception.
 * Logique pure, sans Android : testée hors appareil.
 */
object Fragmentation {
    /** MTU minimale garantie par la norme BLE. */
    const val MTU_MIN = 23
    /** Octets pris par l'en-tête ATT dans chaque écriture. */
    const val EN_TETE_ATT = 3
    /** Valeur maximale d'une caractéristique. */
    const val VALEUR_MAX = 512

    /** Octets utiles par écriture pour une MTU négociée. */
    fun tailleMorceau(mtu: Int): Int = (mtu - EN_TETE_ATT).coerceIn(MTU_MIN - EN_TETE_ATT, VALEUR_MAX)

    fun decouper(paquet: ByteArray, taille: Int): List<ByteArray> {
        require(taille > 0)
        return (paquet.indices step taille).map {
            paquet.copyOfRange(it, minOf(it + taille, paquet.size))
        }
    }
}

/**
 * Rassemble les fragments reçus de chaque appareil jusqu'à la taille
 * annoncée par l'en-tête du paquet (PacketCodec).
 */
class Reassembleur {
    private val tampons = mutableMapOf<String, ByteArray>()

    /**
     * Ajoute un fragment reçu de [source]. Retourne le paquet complet quand
     * il l'est, null sinon. Un en-tête invalide ou un paquet plus long
     * qu'annoncé vide le tampon de cette source.
     */
    @Synchronized
    fun ajouter(source: String, fragment: ByteArray): ByteArray? {
        val tout = (tampons[source] ?: ByteArray(0)) + fragment
        if (tout.size < PacketCodec.TAILLE_EN_TETE) {
            tampons[source] = tout
            return null
        }
        val attendue = PacketCodec.tailleAnnoncee(tout)
        return when {
            attendue == null || tout.size > attendue -> { tampons.remove(source); null }
            tout.size == attendue -> { tampons.remove(source); tout }
            else -> { tampons[source] = tout; null }
        }
    }

    @Synchronized
    fun oublier(source: String) {
        tampons.remove(source)
    }
}

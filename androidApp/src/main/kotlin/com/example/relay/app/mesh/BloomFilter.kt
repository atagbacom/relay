package com.relay.app.mesh

import kotlin.math.abs

/**
 * Filtre de Bloom pour la déduplication des paquets mesh.
 *
 * Évite de relayer un paquet déjà vu sans stocker tous les IDs.
 * Faux positifs possibles (< 1% avec ces paramètres) —
 * acceptable : on rate un relais rare plutôt que de boucler.
 *
 * @param size      Taille du bitset en bits — 10 000 = ~1.2 KB mémoire
 * @param hashCount Nombre de fonctions de hachage — 7 = optimal pour 1% FPR
 */
class BloomFilter(
    private val size: Int = 10_000,
    private val hashCount: Int = 7
) {
    private val bits = BooleanArray(size)

    /**
     * Ajoute un identifiant de paquet.
     */
    fun add(packetId: String) {
        hashes(packetId).forEach { bits[it] = true }
    }

    /**
     * Teste si un paquet a probablement déjà été vu.
     * false = certainement nouveau
     * true  = probablement déjà vu (rare faux positif possible)
     */
    fun mightContain(packetId: String): Boolean {
        return hashes(packetId).all { bits[it] }
    }

    private fun hashes(value: String): List<Int> {
        return (0 until hashCount).map { seed ->
            val h = (value + seed).hashCode()
            abs(h) % size
        }
    }

    /**
     * Réinitialise le filtre — appelé toutes les heures
     * pour éviter la saturation progressive.
     */
    fun reset() = bits.fill(false)
}
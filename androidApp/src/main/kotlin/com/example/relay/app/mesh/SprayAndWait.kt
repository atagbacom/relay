package com.relay.app.mesh

import com.relay.app.model.RelayPacket
import android.util.Log

/**
 * Implémentation de Spray-and-Wait pour le routage mesh.
 *
 * Principe :
 * Phase Spray — l'expéditeur distribue L copies du message
 *               aux L premiers relais rencontrés.
 * Phase Wait  — chaque relais attend de rencontrer
 *               directement le destinataire pour livrer.
 *
 * Avantages vs flooding naïf :
 * - Nombre de copies borné à L (ici 7) — pas d'explosion
 * - Probabilité de livraison élevée si L bien choisi
 * - Consommation réseau O(L) au lieu de O(N²)
 *
 * Référence : Spyropoulos et al., "Spray and Wait:
 * An Efficient Routing Scheme for Intermittently
 * Connected Mobile Networks", ACM SIGCOMM 2005.
 */
class SprayAndWait(private val localShortId: String) {

    companion object {
        // Nombre de copies initiales
        // Valeur optimale pour réseau 20-50 nœuds, modèle RWP
        const val SPRAY_COPIES = 7

        private const val TAG = "SprayAndWait"
    }

    // Copies restantes par packetId
    // Si 0 : on est en phase Wait — on livre uniquement au destinataire direct
    private val remainingCopies = mutableMapOf<String, Int>()

    /**
     * Initialise le spray pour un nouveau message qu'on envoie.
     * Retourne le nombre de copies à distribuer.
     */
    fun initSpray(packetId: String): Int {
        remainingCopies[packetId] = SPRAY_COPIES
        Log.d(TAG, "Spray initialisé pour $packetId — $SPRAY_COPIES copies")
        return SPRAY_COPIES
    }

    /**
     * Détermine l'action à prendre pour un paquet reçu.
     *
     * @return SprayAction décrivant quoi faire
     */
    fun decide(
        packet: RelayPacket,
        isForUs: Boolean,
        hasDirectContact: Boolean
    ): SprayAction {

        // Paquet pour nous — livrer directement
        if (isForUs) return SprayAction.DELIVER

        // On a le destinataire en vue directe — livrer
        if (hasDirectContact) return SprayAction.FORWARD_DIRECT

        val copies = remainingCopies[packet.id]

        return when {
            // Nouveau paquet qu'on voit pour la première fois en relais
            copies == null -> {
                // On reçoit ce paquet d'un autre nœud
                // On prend la moitié des copies restantes (Binary Spray)
                val ourCopies = maxOf(1, packet.ttl / 2)
                remainingCopies[packet.id] = ourCopies
                if (ourCopies > 1) SprayAction.SPRAY(ourCopies)
                else SprayAction.WAIT
            }

            // On a encore des copies à distribuer — phase spray
            copies > 1 -> {
                remainingCopies[packet.id] = copies - 1
                SprayAction.SPRAY(copies - 1)
            }

            // Plus qu'une copie — phase wait
            else -> SprayAction.WAIT
        }
    }

    /**
     * Consomme une copie lors d'un relais réussi.
     */
    fun consumeCopy(packetId: String) {
        val current = remainingCopies[packetId] ?: return
        if (current > 0) remainingCopies[packetId] = current - 1
    }

    /**
     * Nettoie les entrées expirées pour libérer la mémoire.
     * Appelé toutes les heures.
     */
    fun pruneExpired(activePacketIds: Set<String>) {
        val toRemove = remainingCopies.keys.filter {
            it !in activePacketIds
        }
        toRemove.forEach { remainingCopies.remove(it) }
    }
}

/**
 * Décision de routage retournée par SprayAndWait.
 */
sealed class SprayAction {
    object DELIVER : SprayAction()          // Livrer à l'application locale
    object WAIT : SprayAction()             // Attendre le destinataire direct
    object FORWARD_DIRECT : SprayAction()  // Envoyer directement au destinataire
    data class SPRAY(val copies: Int) : SprayAction() // Distribuer N copies
}
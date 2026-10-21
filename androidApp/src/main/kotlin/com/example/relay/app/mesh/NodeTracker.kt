package com.relay.app.mesh

import android.util.Log

/**
 * Suit les nœuds connus du réseau mesh.
 *
 * Maintient pour chaque pair :
 * - Son adresse MAC BLE
 * - La dernière fois qu'on l'a vu
 * - Le nombre de messages qu'on lui a relayés
 * - Si on a déjà échangé des prekeys avec lui
 *
 * Ces métriques servent à Spray-and-Wait pour choisir
 * les N meilleurs relais pour chaque message.
 */
class NodeTracker {

    data class NodeInfo(
        val shortId: String,
        /** Peut changer : Android fait tourner l'adresse Bluetooth (vie privée). */
        var macAddress: String,
        val firstSeenAt: Long = System.currentTimeMillis(),
        var lastSeenAt: Long = System.currentTimeMillis(),
        var relayCount: Int = 0,
        var hasExchangedPreKeys: Boolean = false,
        var rssi: Int = -100   // Force du signal BLE — plus proche de 0 = mieux
    )

    private val nodes = java.util.concurrent.ConcurrentHashMap<String, NodeInfo>()

    /**
     * Enregistre ou met à jour un nœud découvert.
     */
    fun seen(shortId: String, macAddress: String, rssi: Int = -100) {
        val existing = nodes[shortId]
        if (existing != null) {
            existing.lastSeenAt = System.currentTimeMillis()
            existing.rssi = rssi
            existing.macAddress = macAddress   // toujours la dernière adresse vue
        } else {
            nodes[shortId] = NodeInfo(shortId, macAddress, rssi = rssi)
            Log.d("NodeTracker", "Nouveau nœud : $shortId (rssi=$rssi)")
        }
    }

    /**
     * Un paquet vient d'arriver directement de ce pair, par une connexion
     * Bluetooth : il est donc là, à cette adresse. Utile quand le scan ne l'a
     * pas encore reconnu (après un Bluetooth coupé, il a changé d'adresse et
     * certains téléphones remontent rarement l'identifiant).
     *
     * Retourne vrai si le pair n'était pas considéré à portée : c'est alors
     * une nouvelle rencontre. Si le scan l'a vu il y a moins de 30 s, on
     * garde son adresse de scan, la plus sûre.
     */
    fun vuParConnexion(shortId: String, macAddress: String): Boolean {
        val maintenant = System.currentTimeMillis()
        val existing = nodes[shortId]
        if (existing != null && maintenant - existing.lastSeenAt < 30_000) return false
        if (existing != null) {
            existing.macAddress = macAddress
            existing.lastSeenAt = maintenant
        } else {
            nodes[shortId] = NodeInfo(shortId, macAddress, rssi = -80)
        }
        return true
    }

    /**
     * Marque un nœud comme ayant échangé ses prekeys.
     * Ces nœuds sont préférés comme relais — plus fiables.
     */
    fun markPreKeyExchanged(shortId: String) {
        nodes[shortId]?.hasExchangedPreKeys = true
    }

    /**
     * Incrémente le compteur de relais pour un nœud.
     */
    fun recordRelay(shortId: String) {
        nodes[shortId]?.relayCount = (nodes[shortId]?.relayCount ?: 0) + 1
    }

    /**
     * Retourne les N meilleurs relais disponibles pour un message.
     *
     * Critères de sélection dans l'ordre :
     * 1. Nœuds actifs dans les 60 dernières secondes
     * 2. Préférence pour ceux qui ont échangé leurs prekeys
     * 3. Préférence pour le meilleur signal BLE (RSSI)
     * 4. Maximum N nœuds
     */
    fun getBestRelays(
        excludeShortId: String,
        count: Int = SprayAndWait.SPRAY_COPIES
    ): List<NodeInfo> {
        val cutoff = System.currentTimeMillis() - 30_000  // 30 secondes : au-delà l'adresse peut avoir changé

        return nodes.values
            .filter { it.shortId != excludeShortId }
            .filter { it.lastSeenAt > cutoff }
            .sortedWith(
                compareByDescending<NodeInfo> { it.hasExchangedPreKeys }
                    .thenByDescending { it.rssi }
            )
            .take(count)
    }

    /**
     * Retourne l'adresse MAC d'un pair par son shortId.
     */
    fun getMacAddress(shortId: String): String? {
        return nodes[shortId]?.macAddress
    }

    /**
     * Adresse d'un pair vu il y a moins de [withinMs] : au-delà, l'adresse a
     * pu changer (Bluetooth coupé puis rallumé) ou le pair est parti, et un
     * envoi direct échouerait.
     */
    fun adresseRecente(shortId: String, withinMs: Long = 30_000): String? =
        nodes[shortId]?.takeIf { System.currentTimeMillis() - it.lastSeenAt < withinMs }?.macAddress

    /**
     * Retourne tous les nœuds actifs récemment.
     */
    fun getActiveNodes(withinMs: Long = 120_000): List<NodeInfo> {
        val cutoff = System.currentTimeMillis() - withinMs
        return nodes.values.filter { it.lastSeenAt > cutoff }
    }

    /**
     * Nombre de nœuds actifs — affiché dans l'UI.
     */
    fun activeCount(): Int = getActiveNodes().size

    /**
     * Nettoie les nœuds qu'on n'a pas vus depuis 10 minutes.
     * Appelé périodiquement par MeshService.
     */
    fun prune() {
        val cutoff = System.currentTimeMillis() - 600_000
        val toRemove = nodes.filter { it.value.lastSeenAt < cutoff }.keys
        toRemove.forEach { nodes.remove(it) }
        if (toRemove.isNotEmpty()) {
            Log.d("NodeTracker", "Nœuds supprimés : ${toRemove.size}")
        }
    }
}
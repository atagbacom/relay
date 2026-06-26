package com.relay.app.crypto

/**
 * Les trois niveaux de confiance de Relay.
 *
 * Règle absolue : on monte, on ne descend jamais.
 * Une fois GREEN, toujours GREEN avec ce contact.
 */
enum class TrustLevel(val displayColor: String, val description: String) {

    /**
     * ORANGE — Session différée.
     * Alice n'a jamais croisé Bob. Elle envoie un paquet signé
     * dans le réseau qui attend que Bob réapparaisse.
     * Sécurité : chiffrement présent, mais pas de garantie
     * d'authenticité de la clé de Bob (TOFU — Trust On First Use).
     */
    ORANGE(
        displayColor = "#FF6B00",
        description = "Premier contact — clé non vérifiée"
    ),

    /**
     * YELLOW — Prekeys propagées par gossip signé.
     * Bob a diffusé ses prekeys dans le réseau.
     * Elles sont signées par sa clé Ed25519 — on peut vérifier
     * que Bob les a bien émises. Mais on fait confiance
     * au réseau de propagation pour les transmettre.
     */
    YELLOW(
        displayColor = "#FFB800",
        description = "Clé vérifiée via le réseau"
    ),

    /**
     * GREEN — Échange direct.
     * Alice et Bob ont échangé leurs clés en direct :
     * en face à face via QR code, ou via un canal
     * préexistant (Signal, email, lien partagé).
     * Confiance maximale. Usurpation cryptographiquement impossible.
     */
    GREEN(
        displayColor = "#00C853",
        description = "Clé vérifiée directement"
    );

    /**
     * Tente une montée de niveau.
     * Ne fait rien si le niveau demandé est inférieur au niveau actuel.
     */
    fun upgradeTo(target: TrustLevel): TrustLevel {
        return if (target.ordinal > this.ordinal) target else this
    }
}
package com.relay.app.ui

/** Contact tel qu'affiché dans les listes. */
data class UiContact(
    val shortId: String,
    val displayName: String,
    val trustLevel: String,
    val lastMessage: String?,
    val isOnline: Boolean
)

/** Message tel qu'affiché dans une conversation. */
data class UiMessage(
    val id: String,
    val text: String,
    val direction: String,        // "OUT" | "IN"
    val senderOrRecipient: String,
    val trustLevel: String,
    val timestamp: Long,
    /** Accusé de réception reçu du destinataire. */
    val delivered: Boolean = false
)


/** Une alerte diffusée : à qui, et depuis quand (pour suivre la remise). */
data class SuiviAlerte(val destinataires: List<String>, val depuis: Long)

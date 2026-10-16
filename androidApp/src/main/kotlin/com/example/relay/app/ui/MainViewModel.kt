package com.relay.app.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.relay.app.crypto.Empreinte
import com.relay.app.crypto.QrKeyExchange
import com.relay.app.crypto.TrustLevel
import com.relay.app.mesh.BatteryMonitor
import com.relay.app.mesh.EtatMesh
import com.relay.app.mesh.MeshService
import com.relay.app.model.RelayContact
import com.relay.app.repository.RelayRepository
import com.relay.app.storage.MessageEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = RelayRepository(app)

    val shortId = MutableStateFlow("…")
    val qrBitmap = MutableStateFlow<Bitmap?>(null)
    /** Empreinte lisible de notre clé, à comparer à voix haute. */
    val empreinte = MutableStateFlow("")
    private var identite: org.signal.libsignal.protocol.IdentityKeyPair? = null
    val peerCount = MutableStateFlow(0)
    /** Identifiants courts des appareils à portée Bluetooth directe, lus dans l'état du service. */
    val peers: StateFlow<List<String>> = EtatMesh.pairs
        .map { liste -> liste.map { it.shortId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val batteryLevel = MutableStateFlow(100)
    val batteryMode = MutableStateFlow("FULL")

    val contacts: StateFlow<List<UiContact>> = repo.observeContacts()
        .map { list -> list.map { it.toUiContact() } }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())

    fun messagesForContact(contactId: String): Flow<List<UiMessage>> {
        return repo.observeConversation(contactId).map { entities ->
            entities.map { it.toUiMessage() }
        }
    }

    fun getContact(contactId: String): Flow<RelayContact?> {
        return repo.observeContacts().map { list ->
            list.find { it.shortId == contactId }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MeshService.ACTION_MESSAGE_RECEIVED -> {
                    // Room Flow se met à jour automatiquement
                }
                MeshService.ACTION_PEER_COUNT_CHANGED -> {
                    peerCount.value = intent.getIntExtra("count", 0)
                }
                MeshService.ACTION_BATTERY_CHANGED -> {
                    batteryLevel.value = intent.getIntExtra("level", 100)
                    batteryMode.value = intent.getStringExtra("mode") ?: "FULL"
                }
            }
        }
    }

    init {
        viewModelScope.launch {
            val identity = repo.getOrCreateIdentity()
            identite = identity
            shortId.value = repo.getShortId()
            empreinte.value = Empreinte.de(identity.publicKey)
            regenererQr()
        }

        val filter = IntentFilter().apply {
            addAction(MeshService.ACTION_MESSAGE_RECEIVED)
            addAction(MeshService.ACTION_PEER_COUNT_CHANGED)
            addAction(MeshService.ACTION_BATTERY_CHANGED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * Le QR contient un horodatage et expire après 5 minutes (anti-rejeu) :
     * l'écran de vérification le renouvelle régulièrement.
     */
    fun regenererQr() {
        val identity = identite ?: return
        val payload = QrKeyExchange.generatePayload(identity, shortId.value)
        qrBitmap.value = QrKeyExchange.generateBitmap(payload)
    }

    /** Les quatre mots que les deux téléphones doivent afficher à l'identique. */
    fun motsDeSecurite(autre: org.signal.libsignal.protocol.IdentityKey): List<String> =
        identite?.let { Empreinte.motsDeSecurite(it.publicKey, autre) } ?: emptyList()

    /** Donne un nom lisible à un contact (vide = revenir à l'identifiant). */
    fun renommer(contactId: String, nom: String) {
        viewModelScope.launch {
            val contact = repo.getContact(contactId) ?: return@launch
            repo.saveContact(contact.copy(alias = nom.trim().take(40).ifEmpty { null }))
        }
    }

    /**
     * Alerte : un message chiffré envoyé à chaque contact, par le même
     * chemin que les autres messages (relais de proche en proche).
     * Retourne les destinataires et l'heure d'envoi, pour suivre la remise.
     */
    fun diffuserAlerte(type: String, precision: String = ""): SuiviAlerte {
        val destinataires = contacts.value.map { it.shortId }
        val depuis = System.currentTimeMillis()
        val texte = "ALERTE · $type" + precision.trim().takeIf { it.isNotEmpty() }?.let { " — $it" }.orEmpty()
        destinataires.forEach { sendMessage(it, texte) }
        return SuiviAlerte(destinataires, depuis)
    }

    private val typesAlerte = TypesAlerte(app)
    val types = MutableStateFlow(typesAlerte.tous())

    fun ajouterTypeAlerte(nom: String, detail: String) {
        typesAlerte.ajouter(nom, detail); types.value = typesAlerte.tous()
    }

    fun supprimerTypeAlerte(nom: String) {
        typesAlerte.supprimer(nom); types.value = typesAlerte.tous()
    }

    /** Pour chaque destinataire de l'alerte : true quand elle lui a été remise. */
    fun remiseAlerte(suivi: SuiviAlerte): Flow<Map<String, Boolean>> {
        if (suivi.destinataires.isEmpty()) return flowOf(emptyMap())
        return combine(suivi.destinataires.map { id ->
            repo.observeConversation(id).map { messages ->
                id to (messages.lastOrNull { it.direction == "OUT" && it.timestamp >= suivi.depuis }?.delivered ?: false)
            }
        }) { paires -> paires.toMap() }
    }

    fun sendMessage(recipientId: String, text: String) {
        if (text.isBlank()) return
        val intent = Intent(getApplication(), MeshService::class.java).apply {
            action = MeshService.ACTION_SEND
            putExtra(MeshService.EXTRA_RECIPIENT_ID, recipientId)
            putExtra(MeshService.EXTRA_MESSAGE, text)
        }
        getApplication<Application>().startService(intent)
    }

    /**
     * Ajoute un contact au niveau GREEN après scan QR réussi.
     */
    fun addGreenContact(qrContact: QrKeyExchange.QrContact) {
        viewModelScope.launch {
            val existing = repo.getContact(qrContact.shortId)
            val contact = existing?.copy(
                publicKey = qrContact.publicKey,
                trustLevel = TrustLevel.GREEN
            ) ?: RelayContact(
                shortId = qrContact.shortId,
                publicKey = qrContact.publicKey,
                trustLevel = TrustLevel.GREEN
            )
            repo.saveContact(contact)
        }
    }

    // Conversions UI
    private fun RelayContact.toUiContact() = UiContact(
        shortId = shortId,
        displayName = displayName,
        trustLevel = trustLevel.name,
        lastMessage = null,
        isOnline = false
    )

    private fun MessageEntity.toUiMessage() = UiMessage(
        id = id,
        text = plaintext,
        direction = direction,
        senderOrRecipient = contactShortId,
        trustLevel = trustLevelAtSend,
        timestamp = timestamp,
        delivered = delivered
    )

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(receiver)
    }
}
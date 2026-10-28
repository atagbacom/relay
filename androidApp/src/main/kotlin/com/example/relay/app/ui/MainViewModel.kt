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
import com.relay.app.crypto.VerificationMutuelle
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
    private val reglages = com.relay.app.Reglages(app)
    /** Le nom que l'utilisateur s'est donné (vide si aucun). */
    val monNom = MutableStateFlow(reglages.monNom)

    fun changerMonNom(nom: String) {
        reglages.monNom = nom
        monNom.value = reglages.monNom
        regenererQr()
        // Les contacts à portée l'apprennent tout de suite ; les autres à la prochaine rencontre
        runCatching {
            getApplication<Application>().startService(
                Intent(getApplication(), MeshService::class.java).setAction(MeshService.ACTION_DIFFUSER_CLES)
            )
        }
    }

    /** Empreinte lisible de notre clé, à comparer à voix haute. */
    val empreinte = MutableStateFlow("")
    private var identite: org.signal.libsignal.protocol.IdentityKeyPair? = null
    val peerCount = MutableStateFlow(0)
    /** Appareils à portée, avec la force de leur signal (radar). */
    val pairsVus: StateFlow<List<EtatMesh.PairVu>> = EtatMesh.pairs

    /** Identifiants courts des appareils à portée Bluetooth directe, lus dans l'état du service. */
    val peers: StateFlow<List<String>> = EtatMesh.pairs
        .map { liste -> liste.map { it.shortId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val batteryLevel: StateFlow<Int> = EtatMesh.batterie
    val batteryMode: StateFlow<String> = EtatMesh.modeBatterie
    val choixBatterie: StateFlow<String> = EtatMesh.choixBatterie

    fun choisirBatterie(choix: String) {
        reglages.modeBatterie = choix
        EtatMesh.choixBatterie.value = choix
    }

    /**
     * Contacts avec l'aperçu de leur dernier message et leur joignabilité,
     * les conversations les plus récentes en premier (avant : aperçu vide).
     */
    val contacts: StateFlow<List<UiContact>> = combine(
        repo.observeContacts(), repo.observeDerniers(), EtatMesh.pairs
    ) { liste, derniers, pairs ->
        val parContact = derniers.associateBy { it.contactShortId }
        val aPortee = pairs.map { it.shortId }.toSet()
        liste
            .map { c -> c.toUiContact(parContact[c.shortId], c.shortId in aPortee) }
            .sortedByDescending { parContact[it.shortId]?.timestamp ?: 0L }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())

    fun messagesForContact(contactId: String): Flow<List<UiMessage>> {
        return repo.observeConversation(contactId).map { entities ->
            entities.map { it.toUiMessage() }
        }
    }

    /**
     * Un autre contact au même nom, connu avant celui-ci : probablement la même
     * personne qui a réinstallé Relay ou changé de téléphone (nouvelle clé).
     */
    fun ancienAppareilPossible(contactId: String): Flow<RelayContact?> =
        repo.observeContacts().map { liste ->
            val c = liste.find { it.shortId == contactId } ?: return@map null
            val nom = c.nomAffiche?.trim()?.lowercase() ?: return@map null
            liste.filter { it.shortId != contactId && it.nomAffiche?.trim()?.lowercase() == nom && it.firstSeenAt < c.firstSeenAt }
                .maxByOrNull { it.firstSeenAt }
        }

    fun regrouper(ancien: String, nouveau: String) {
        viewModelScope.launch { repo.regrouper(ancien, nouveau) }
    }

    fun supprimerContact(contactId: String) {
        viewModelScope.launch { repo.supprimerContact(contactId) }
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
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /**
     * Le QR contient un horodatage et expire après 5 minutes (anti-rejeu) :
     * l'écran de vérification le renouvelle régulièrement.
     */
    fun regenererQr() {
        val identity = identite ?: return
        val payload = QrKeyExchange.generatePayload(
            identity, shortId.value, VerificationMutuelle.Nonces.emettre(), reglages.monNom
        )
        qrBitmap.value = QrKeyExchange.generateBitmap(payload)
    }

    /** Les quatre mots que les deux téléphones doivent afficher à l'identique. */
    /** Mots identiques des deux côtés : celui qui nous a vérifiés devient « vérifié en personne ». */
    fun confirmerVerification(recue: VerificationMutuelle.Recue) {
        addGreenContact(QrKeyExchange.QrContact(recue.shortId, recue.cle), recue.nom)
        EtatMesh.verificationRecue.value = null
    }

    fun refuserVerification() {
        EtatMesh.verificationRecue.value = null
    }

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
    fun addGreenContact(qrContact: QrKeyExchange.QrContact, nom: String? = qrContact.nom) {
        // Vérification à un seul scan : notre propre clé part vers celui qu'on vient de scanner
        qrContact.nonce?.let { nonce ->
            val i = Intent(getApplication(), MeshService::class.java).apply {
                action = MeshService.ACTION_VERIFIER
                putExtra(MeshService.EXTRA_RECIPIENT_ID, qrContact.shortId)
                putExtra(MeshService.EXTRA_NONCE, nonce)
            }
            runCatching { getApplication<Application>().startService(i) }
        }
        viewModelScope.launch {
            val existing = repo.getContact(qrContact.shortId)
            val contact = existing?.copy(
                publicKey = qrContact.publicKey,
                trustLevel = TrustLevel.GREEN,
                // le nom transmis ; un nom donné à la main (alias) reste prioritaire
                nomAnnonce = nom ?: existing.nomAnnonce
            ) ?: RelayContact(
                shortId = qrContact.shortId,
                publicKey = qrContact.publicKey,
                trustLevel = TrustLevel.GREEN,
                nomAnnonce = nom
            )
            repo.saveContact(contact)
        }
    }

    // Conversions UI
    private fun RelayContact.toUiContact(dernier: MessageEntity?, aPortee: Boolean) = UiContact(
        shortId = shortId,
        displayName = displayName,
        trustLevel = trustLevel.name,
        lastMessage = dernier?.let {
            when {
                it.direction == "IN" -> it.plaintext
                it.delivered -> "Vous : ${it.plaintext}"
                else -> "En attente · ${it.plaintext}"
            }
        },
        isOnline = aPortee
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
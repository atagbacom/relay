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
import com.relay.app.crypto.QrKeyExchange
import com.relay.app.crypto.TrustLevel
import com.relay.app.mesh.BatteryMonitor
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
    val peerCount = MutableStateFlow(0)
    /** Identifiants courts des appareils à portée Bluetooth directe. */
    val peers = MutableStateFlow<List<String>>(emptyList())
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
                    peers.value = intent.getStringArrayExtra("peers")?.toList() ?: emptyList()
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
            val id = repo.getShortId()
            shortId.value = id

            // Génère le QR code
            val payload = QrKeyExchange.generatePayload(identity, id)
            qrBitmap.value = QrKeyExchange.generateBitmap(payload)
        }

        val filter = IntentFilter().apply {
            addAction(MeshService.ACTION_MESSAGE_RECEIVED)
            addAction(MeshService.ACTION_PEER_COUNT_CHANGED)
            addAction(MeshService.ACTION_BATTERY_CHANGED)
        }
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
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
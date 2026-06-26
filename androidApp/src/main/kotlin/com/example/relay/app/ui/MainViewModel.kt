package com.relay.app.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.relay.app.mesh.MeshService
import com.relay.app.repository.RelayRepository
import com.relay.app.storage.MessageEntity
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class UiMessage(
    val id: String,
    val text: String,
    val direction: String,   // "IN" | "OUT"
    val senderOrRecipient: String,
    val trustLevel: String,
    val timestamp: Long
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = RelayRepository(app)

    private val _shortId = MutableStateFlow("…")
    val shortId: StateFlow<String> = _shortId

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages

    private val _peers = MutableStateFlow<List<String>>(emptyList())
    val peers: StateFlow<List<String>> = _peers

    // Écoute les broadcasts du MeshService
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MeshService.ACTION_MESSAGE_RECEIVED) {
                val sender = intent.getStringExtra(MeshService.EXTRA_SENDER_ID) ?: return
                val text = intent.getStringExtra(MeshService.EXTRA_PLAINTEXT) ?: return
                addIncomingMessage(sender, text)
            }
        }
    }

    init {
        viewModelScope.launch {
            _shortId.value = repo.getShortId()
        }

        // Écoute les contacts découverts
        viewModelScope.launch {
            repo.observeContacts().collect { contacts ->
                _peers.value = contacts.map { it.shortId }
            }
        }

        // Enregistre le receiver
        app.registerReceiver(
            receiver,
            IntentFilter(MeshService.ACTION_MESSAGE_RECEIVED),
            Context.RECEIVER_NOT_EXPORTED
        )
    }

    fun sendMessage(recipientId: String, text: String) {
        if (text.isBlank() || recipientId.isBlank()) return

        // Envoie via le service
        val intent = Intent(getApplication(), MeshService::class.java).apply {
            action = MeshService.ACTION_SEND
            putExtra(MeshService.EXTRA_RECIPIENT_ID, recipientId)
            putExtra(MeshService.EXTRA_MESSAGE, text)
        }
        getApplication<Application>().startService(intent)

        // Ajoute immédiatement en UI (optimistic update)
        _messages.value = _messages.value + UiMessage(
            id = System.currentTimeMillis().toString(),
            text = text,
            direction = "OUT",
            senderOrRecipient = recipientId,
            trustLevel = "ORANGE",
            timestamp = System.currentTimeMillis()
        )
    }

    private fun addIncomingMessage(sender: String, text: String) {
        _messages.value = _messages.value + UiMessage(
            id = System.currentTimeMillis().toString(),
            text = text,
            direction = "IN",
            senderOrRecipient = sender,
            trustLevel = "YELLOW",
            timestamp = System.currentTimeMillis()
        )
    }

    override fun onCleared() {
        super.onCleared()
        getApplication<Application>().unregisterReceiver(receiver)
    }
}
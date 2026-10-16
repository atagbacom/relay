package com.relay.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.relay.app.ui.components.LigneConversation

/** Toutes les conversations, la plus récente en haut. */
@Composable
fun MessagesScreen(viewModel: MainViewModel, onOuvrirConversation: (String) -> Unit) {
    val contacts by viewModel.contacts.collectAsState(emptyList())
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Text(
            "Messages",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp)
        )
        Text(
            "Chiffrés de bout en bout. Aucun serveur ne les voit.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
        )
        if (contacts.isEmpty()) {
            Text(
                "Aucune conversation pour l'instant. Touchez un appareil sur le radar pour lui écrire.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp)
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(contacts, key = { it.shortId }) { c ->
                    LigneConversation(c, onClick = { onOuvrirConversation(c.shortId) })
                }
            }
        }
    }
}

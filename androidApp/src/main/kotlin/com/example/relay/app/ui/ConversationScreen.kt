package com.relay.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.relay.app.ui.theme.RelayColors
import com.relay.app.ui.theme.RelayDimens
import com.relay.app.ui.theme.RelayType

@Composable
fun ConversationScreen(
    contactId: String,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onQrExchange: () -> Unit
) {
    val messages by viewModel.messagesForContact(contactId).collectAsState(emptyList())
    val contact by viewModel.getContact(contactId).collectAsState(null)
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val trustColor = when (contact?.trustLevel?.name) {
        "GREEN"  -> RelayColors.Green
        "YELLOW" -> RelayColors.Yellow
        else     -> RelayColors.Orange
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RelayColors.Background)
            .systemBarsPadding()
    ) {

        // ── Header ──────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(RelayColors.Surface)
                .padding(RelayDimens.PaddingM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onBack) {
                Text("←",
                    color = RelayColors.Green,
                    fontFamily = RelayType.Mono,
                    fontSize = RelayDimens.FontL
                )
            }

            // Indicateur de confiance
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(trustColor, RoundedCornerShape(5.dp))
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    contact?.displayName ?: contactId,
                    color = RelayColors.TextPrimary,
                    fontFamily = RelayType.Mono,
                    fontSize = RelayDimens.FontM,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    when (contact?.trustLevel?.name) {
                        "GREEN"  -> "Confiance maximale"
                        "YELLOW" -> "Clé vérifiée via réseau"
                        else     -> "Premier contact"
                    },
                    color = trustColor,
                    fontFamily = RelayType.Mono,
                    fontSize = RelayDimens.FontXS
                )
            }

            // Bouton échange QR — passe au niveau GREEN
            if (contact?.trustLevel?.name != "GREEN") {
                TextButton(onClick = onQrExchange) {
                    Text(
                        "QR →",
                        color = RelayColors.Green,
                        fontFamily = RelayType.Mono,
                        fontSize = RelayDimens.FontS
                    )
                }
            }
        }

        // ── Messages ────────────────────────────────────────
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = RelayDimens.PaddingM),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(vertical = RelayDimens.PaddingM)
        ) {
            items(messages) { msg ->
                MessageBubble(msg)
            }
        }

        // ── Saisie ──────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(RelayColors.Surface)
                .padding(RelayDimens.PaddingM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        "Message chiffré…",
                        color = RelayColors.TextMuted,
                        fontFamily = RelayType.Mono,
                        fontSize = RelayDimens.FontS
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = RelayColors.Green,
                    unfocusedBorderColor = RelayColors.Border,
                    focusedTextColor = RelayColors.TextPrimary,
                    unfocusedTextColor = RelayColors.TextPrimary,
                    cursorColor = RelayColors.Green
                ),
                shape = RoundedCornerShape(RelayDimens.RadiusM),
                singleLine = true
            )

            Button(
                onClick = {
                    if (inputText.isNotBlank()) {
                        viewModel.sendMessage(contactId, inputText)
                        inputText = ""
                    }
                },
                enabled = inputText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = RelayColors.Green,
                    disabledContainerColor = RelayColors.GreenDim
                ),
                shape = RoundedCornerShape(RelayDimens.RadiusM),
                contentPadding = PaddingValues(
                    horizontal = 20.dp, vertical = 12.dp
                )
            ) {
                Text(
                    "→",
                    color = RelayColors.Background,
                    fontSize = RelayDimens.FontL,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MainScreen(viewModel: MainViewModel) {

    val shortId by viewModel.shortId.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val peers by viewModel.peers.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var selectedPeer by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Scroll auto vers le bas à chaque nouveau message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D0D0D))
            .systemBarsPadding()
    ) {

        // ── Header ──────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "RELAY",
                    color = Color(0xFF00C853),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp
                )
                Text(
                    shortId,
                    color = Color(0xFF666666),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Indicateur réseau
            val networkColor = if (peers.isEmpty()) Color(0xFF444444)
                               else Color(0xFF00C853)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(networkColor, RoundedCornerShape(4.dp))
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (peers.isEmpty()) "isolé"
                    else "${peers.size} pair${if (peers.size > 1) "s" else ""}",
                    color = networkColor,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // ── Sélecteur de pair ────────────────────────────────
        if (peers.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF111111))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                peers.forEach { peerId ->
                    val isSelected = selectedPeer == peerId
                    Surface(
                        onClick = { selectedPeer = peerId },
                        shape = RoundedCornerShape(4.dp),
                        color = if (isSelected) Color(0xFF1E3A2F)
                                else Color(0xFF1A1A1A),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isSelected) Color(0xFF00C853)
                            else Color(0xFF333333)
                        )
                    ) {
                        Text(
                            peerId,
                            modifier = Modifier.padding(
                                horizontal = 10.dp, vertical = 6.dp
                            ),
                            color = if (isSelected) Color(0xFF00C853)
                                    else Color(0xFF666666),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // ── Messages ────────────────────────────────────────
        LazyColumn(
            modifier = Modifier.weight(1f).padding(16.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillParentMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "Aucun message",
                                color = Color(0xFF333333),
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Les pairs à portée apparaissent en haut",
                                color = Color(0xFF222222),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            items(messages) { msg ->
                MessageBubble(msg)
            }
        }

        // ── Saisie ──────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A1A1A))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (selectedPeer.isEmpty()) "Sélectionne un pair…"
                        else "Message pour $selectedPeer…",
                        color = Color(0xFF444444),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF00C853),
                    unfocusedBorderColor = Color(0xFF333333),
                    focusedTextColor = Color(0xFFEEEEEE),
                    unfocusedTextColor = Color(0xFFEEEEEE),
                    cursorColor = Color(0xFF00C853)
                ),
                shape = RoundedCornerShape(8.dp),
                singleLine = true
            )

            Button(
                onClick = {
                    if (inputText.isNotBlank() && selectedPeer.isNotEmpty()) {
                        viewModel.sendMessage(selectedPeer, inputText)
                        inputText = ""
                    }
                },
                enabled = inputText.isNotBlank() && selectedPeer.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00C853),
                    disabledContainerColor = Color(0xFF1E3A2F)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    "→",
                    color = Color(0xFF0D0D0D),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Black
                )
            }
        }
    }
}

@Composable
fun MessageBubble(msg: UiMessage) {
    val isOut = msg.direction == "OUT"

    // Couleur du niveau de confiance
    val trustColor = when (msg.trustLevel) {
        "GREEN"  -> Color(0xFF00C853)
        "YELLOW" -> Color(0xFFFFB800)
        else     -> Color(0xFFFF6B00)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isOut) Arrangement.End else Arrangement.Start
    ) {
        Column(
            horizontalAlignment = if (isOut) Alignment.End else Alignment.Start
        ) {
            // Label expéditeur/destinataire + niveau de confiance
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    msg.senderOrRecipient,
                    color = Color(0xFF555555),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(trustColor, RoundedCornerShape(3.dp))
                )
            }

            Spacer(Modifier.height(2.dp))

            // Bulle
            Surface(
                shape = RoundedCornerShape(
                    topStart = 12.dp,
                    topEnd = 12.dp,
                    bottomStart = if (isOut) 12.dp else 2.dp,
                    bottomEnd = if (isOut) 2.dp else 12.dp
                ),
                color = if (isOut) Color(0xFF1E3A2F) else Color(0xFF1A1A1A),
                border = androidx.compose.foundation.BorderStroke(
                    0.5.dp,
                    if (isOut) Color(0xFF00C853).copy(alpha = 0.3f)
                    else Color(0xFF333333)
                )
            ) {
                Text(
                    msg.text,
                    modifier = Modifier.padding(
                        horizontal = 14.dp, vertical = 10.dp
                    ),
                    color = Color(0xFFEEEEEE),
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}
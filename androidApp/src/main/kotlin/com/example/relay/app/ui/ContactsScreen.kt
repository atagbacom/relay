package com.relay.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.relay.app.ui.theme.RelayColors
import com.relay.app.ui.theme.RelayDimens
import com.relay.app.ui.theme.RelayType

@Composable
fun ContactsScreen(
    viewModel: MainViewModel,
    onContactSelected: (String) -> Unit,
    onQrScan: () -> Unit
) {
    val shortId by viewModel.shortId.collectAsState()
    val contacts by viewModel.contacts.collectAsState(emptyList())
    val peerCount by viewModel.peerCount.collectAsState(0)
    val batteryLevel by viewModel.batteryLevel.collectAsState(100)
    val batteryMode by viewModel.batteryMode.collectAsState("FULL")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RelayColors.Background)
            .systemBarsPadding()
    ) {

        // ── Header ──────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(RelayColors.Surface)
                .padding(RelayDimens.PaddingM)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "RELAY",
                        color = RelayColors.Green,
                        fontFamily = RelayType.Mono,
                        fontSize = RelayDimens.FontTitle,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 4.sp
                    )
                    Text(
                        shortId,
                        color = RelayColors.TextMuted,
                        fontFamily = RelayType.Mono,
                        fontSize = RelayDimens.FontXS
                    )
                }

                // Stats réseau
                Column(horizontalAlignment = Alignment.End) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val netColor = if (peerCount > 0) RelayColors.Green
                                      else RelayColors.TextMuted
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .background(netColor, RoundedCornerShape(4.dp))
                        )
                        Text(
                            if (peerCount == 0) "isolé"
                            else "$peerCount pair${if (peerCount > 1) "s" else ""}",
                            color = netColor,
                            fontFamily = RelayType.Mono,
                            fontSize = RelayDimens.FontXS
                        )
                    }

                    Spacer(Modifier.height(2.dp))

                    val batColor = when {
                        batteryLevel <= 15 -> RelayColors.Orange
                        batteryLevel <= 30 -> RelayColors.Yellow
                        else               -> RelayColors.TextMuted
                    }
                    Text(
                        "⬡ $batteryLevel%${if (batteryMode == "CRITICAL") " CRITIQUE" else ""}",
                        color = batColor,
                        fontFamily = RelayType.Mono,
                        fontSize = RelayDimens.FontXS
                    )
                }
            }

            // Avertissement mode critique
            if (batteryMode == "CRITICAL") {
                Spacer(Modifier.height(8.dp))
                Surface(
                    color = RelayColors.OrangeDim,
                    shape = RoundedCornerShape(RelayDimens.RadiusS)
                ) {
                    Text(
                        "Mode relais silencieux actif — interface suspendue partiellement",
                        modifier = Modifier.padding(
                            horizontal = 10.dp, vertical = 6.dp
                        ),
                        color = RelayColors.Orange,
                        fontFamily = RelayType.Mono,
                        fontSize = RelayDimens.FontXS
                    )
                }
            }
        }

        // ── Bouton QR ────────────────────────────────────────
        Surface(
            onClick = onQrScan,
            modifier = Modifier
                .fillMaxWidth()
                .padding(RelayDimens.PaddingM),
            color = RelayColors.GreenDim,
            shape = RoundedCornerShape(RelayDimens.RadiusM),
            border = androidx.compose.foundation.BorderStroke(
                1.dp, RelayColors.Green
            )
        ) {
            Row(
                modifier = Modifier.padding(RelayDimens.PaddingM),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    "[ ÉCHANGER UNE CLÉ PAR QR ]",
                    color = RelayColors.Green,
                    fontFamily = RelayType.Mono,
                    fontSize = RelayDimens.FontS,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        // ── Liste des contacts ───────────────────────────────
        if (contacts.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(RelayDimens.PaddingL),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Aucun contact",
                        color = RelayColors.TextMuted,
                        fontFamily = RelayType.Mono,
                        fontSize = RelayDimens.FontM
                    )
                    Text(
                        "Les pairs à portée apparaissent\nautomatiquement.\nÉchangez un QR pour la confiance maximale.",
                        color = RelayColors.TextMuted.copy(alpha = 0.5f),
                        fontFamily = RelayType.Mono,
                        fontSize = RelayDimens.FontXS
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = RelayDimens.PaddingM,
                    vertical = RelayDimens.PaddingS
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(contacts) { contact ->
                    ContactRow(
                        contact = contact,
                        onClick = { onContactSelected(contact.shortId) }
                    )
                }
            }
        }
    }
}

@Composable
fun ContactRow(contact: UiContact, onClick: () -> Unit) {
    val trustColor = when (contact.trustLevel) {
        "GREEN"  -> RelayColors.Green
        "YELLOW" -> RelayColors.Yellow
        else     -> RelayColors.Orange
    }

    Surface(
        onClick = onClick,
        color = RelayColors.Surface,
        shape = RoundedCornerShape(RelayDimens.RadiusM),
        border = androidx.compose.foundation.BorderStroke(
            0.5.dp, RelayColors.Border
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(RelayDimens.PaddingM),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Indicateur de confiance
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(trustColor, RoundedCornerShape(5.dp))
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    contact.displayName,
                    color = RelayColors.TextPrimary,
                    fontFamily = RelayType.Mono,
                    fontSize = RelayDimens.FontM,
                    fontWeight = FontWeight.Bold
                )
                if (contact.lastMessage != null) {
                    Text(
                        contact.lastMessage,
                        color = RelayColors.TextSecondary,
                        fontFamily = RelayType.Mono,
                        fontSize = RelayDimens.FontXS,
                        maxLines = 1
                    )
                }
            }

            // Badge "en ligne"
            if (contact.isOnline) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(
                            RelayColors.Green,
                            RoundedCornerShape(3.dp)
                        )
                )
            }
        }
    }
}

data class UiContact(
    val shortId: String,
    val displayName: String,
    val trustLevel: String,
    val lastMessage: String?,
    val isOnline: Boolean
)
package com.relay.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val timestamp: Long
)

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

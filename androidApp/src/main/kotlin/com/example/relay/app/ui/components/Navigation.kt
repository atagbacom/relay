package com.relay.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relay.app.ui.UiContact
import com.relay.app.ui.theme.RelayPalette

enum class Onglet(val libelle: String, val icone: ImageVector) {
    RESEAU("Réseau", Icones.Reseau),
    MESSAGES("Messages", Icones.Messages),
    VERIFIER("Vérifier", Icones.CodeQr),
    ALERTE("Alerte", Icones.Alerte),
}

/** Barre du bas : l'onglet actif est souligné d'or. */
@Composable
fun BarreNavigation(actif: Onglet, onChoix: (Onglet) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(72.dp)
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Onglet.values().forEach { o ->
                    val choisi = o == actif
                    val couleur by animateColorAsState(
                        when {
                            o == Onglet.ALERTE -> MaterialTheme.colorScheme.error
                            choisi -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        label = "couleur"
                    )
                    val trait by animateDpAsState(if (choisi) 18.dp else 0.dp, label = "trait")
                    Column(
                        Modifier
                            .widthIn(min = 72.dp)
                            .semantics { selected = choisi }
                            .clickable(role = Role.Tab) { onChoix(o) }
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(o.icone, contentDescription = null, tint = couleur, modifier = Modifier.size(24.dp))
                        Text(
                            o.libelle,
                            color = couleur,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (choisi) FontWeight.SemiBold else FontWeight.Medium
                        )
                        Box(Modifier.width(trait).height(3.dp).background(RelayPalette.Or, RoundedCornerShape(2.dp)))
                    }
                }
            }
        }
    }
}

/** Une conversation dans une liste : avatar, nom, dernier message, niveau de confiance. */
@Composable
fun LigneConversation(contact: UiContact, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val confiance = Confiance.depuis(contact.trustLevel)
    val estIdentifiant = contact.displayName == contact.shortId
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(
            texte = if (estIdentifiant) contact.shortId.takeLast(4).uppercase() else initiales(contact.displayName),
            confiance = confiance,
            identifiant = estIdentifiant
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                if (estIdentifiant) "Appareil ${contact.shortId.takeLast(4).uppercase()}" else contact.displayName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                contact.lastMessage ?: if (contact.isOnline) "À portée" else "Chiffré de bout en bout",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            BadgeConfiance(confiance)
        }
    }
}

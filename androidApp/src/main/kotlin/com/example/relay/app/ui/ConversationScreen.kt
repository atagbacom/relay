package com.relay.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.relay.app.ui.components.*
import com.relay.app.ui.theme.PlexMono
import com.relay.app.ui.theme.RelayPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ConversationScreen(
    contactId: String,
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onQrExchange: () -> Unit,
    onVoirFiche: () -> Unit = {}
) {
    val messages by viewModel.messagesForContact(contactId).collectAsState(emptyList())
    val contact by viewModel.getContact(contactId).collectAsState(null)
    val peers by viewModel.peers.collectAsState()
    val listState = rememberLazyListState()
    var saisie by remember { mutableStateOf("") }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    val confiance = Confiance.depuis(contact?.trustLevel?.name)
    val estIdentifiant = contact?.nomAffiche == null
    val nom = contact?.nomAffiche ?: "Appareil ${contactId.takeLast(4).uppercase()}"
    val aPortee = contactId in peers

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding()
    ) {
        // ── En-tête ────────────────────────────────────────────
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column {
                Row(
                    Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icones.Retour, contentDescription = "Retour", tint = MaterialTheme.colorScheme.primary)
                    }
                    Row(
                        Modifier
                            .weight(1f)
                            .clickable(onClickLabel = "Voir la fiche du contact", onClick = onVoirFiche)
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    Box {
                        Avatar(
                            texte = if (estIdentifiant) contactId.takeLast(4).uppercase() else initiales(nom),
                            confiance = confiance, taille = 42.dp, identifiant = estIdentifiant
                        )
                        if (aPortee) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(13.dp)
                                    .border(2.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                    .background(Color2.EnLigne, CircleShape)
                            )
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 6.dp)) {
                        Text(nom, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(confiance.icone, contentDescription = null, tint = confiance.couleurs.texte, modifier = Modifier.size(13.dp))
                            Text(
                                confiance.libelle + if (aPortee) " · à portée" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = confiance.couleurs.texte
                            )
                        }
                    }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // ── Messages ───────────────────────────────────────────
        LazyColumn(
            modifier = Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "chiffrement") {
                BandeauChiffrement(confiance, onQrExchange)
            }
            items(messages, key = { it.id }) { m ->
                Box(Modifier.fillMaxWidth().animateItem()) {
                    if (m.direction == "OUT") BulleEnvoyee(m, nom) else BulleRecue(m)
                }
            }
        }

        // ── Saisie ─────────────────────────────────────────────
        Composeur(
            valeur = saisie,
            onValeur = { saisie = it },
            onEnvoyer = {
                if (saisie.isNotBlank()) {
                    viewModel.sendMessage(contactId, saisie.trim())
                    saisie = ""
                }
            }
        )
    }
}

private object Color2 {
    val EnLigne = androidx.compose.ui.graphics.Color(0xFF2E9E47)
}

@Composable
private fun BandeauChiffrement(confiance: Confiance, onVerifier: () -> Unit) {
    val texte = when (confiance) {
        Confiance.VERIFIE_PERSONNE -> "Chiffré de bout en bout. Clé vérifiée en personne. Aucun serveur ne voit ces messages."
        Confiance.VERIFIE_RESEAU -> "Chiffré de bout en bout. Clé vérifiée par le réseau : vérifiez en personne pour une confiance maximale."
        Confiance.NON_VERIFIE -> "Chiffré de bout en bout, mais la clé n'est pas encore vérifiée. Vérifiez cette personne en face à face."
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .then(if (confiance != Confiance.VERIFIE_PERSONNE) Modifier.clickable(onClick = onVerifier) else Modifier)
            .padding(horizontal = 13.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Icon(Icones.Cadenas, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(texte, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (confiance != Confiance.VERIFIE_PERSONNE) {
            Text("Vérifier", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

private val formatHeure = SimpleDateFormat("HH:mm", Locale.FRANCE)

@Composable
private fun BulleRecue(m: UiMessage) {
    Row(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .widthIn(max = 290.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp))
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(m.text, style = MaterialTheme.typography.bodyLarge)
            Text(
                formatHeure.format(Date(m.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Message envoyé, avec son trajet : vous → réseau → destinataire.
 * En route, une étincelle parcourt la piste ; à l'accusé de réception,
 * la piste se remplit d'or et une double coche apparaît.
 */
@Composable
private fun BulleEnvoyee(m: UiMessage, destinataire: String) {
    val anime = animationsActivees()
    val remplissage by animateFloatAsState(if (m.delivered) 1f else 0.5f, tween(900, easing = FastOutSlowInEasing), label = "piste")
    val etincelle = if (anime && !m.delivered) {
        val t = rememberInfiniteTransition(label = "etincelle")
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing)), label = "pos").value
    } else -1f
    val couleurEtat by animateColorAsState(if (m.delivered) RelayPalette.Remis else RelayPalette.OrLumiere, label = "etat")
    val forme = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Column(
            Modifier
                .widthIn(min = 236.dp, max = 300.dp)
                .background(RelayPalette.BleuNuit, forme)
                .padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 12.dp)
                .semantics {
                    contentDescription = "Vous : ${m.text}. " + if (m.delivered) "Remis." else "En route."
                }
        ) {
            Text(m.text, style = MaterialTheme.typography.bodyLarge, color = RelayPalette.Blanc)
            Spacer(Modifier.height(12.dp))
            Canvas(Modifier.fillMaxWidth().height(12.dp).padding(horizontal = 6.dp)) {
                val y = size.height / 2f
                val w = size.width
                drawLine(RelayPalette.BleuBrume.copy(alpha = 0.25f), Offset(0f, y), Offset(w, y), 3.dp.toPx(), StrokeCap.Round)
                drawLine(RelayPalette.Or, Offset(0f, y), Offset(w * remplissage, y), 3.dp.toPx(), StrokeCap.Round)
                listOf(0f, 0.5f, 1f).forEach { x ->
                    val atteint = x <= remplissage + 0.001f
                    drawCircle(RelayPalette.BleuNuit, 6.5.dp.toPx(), Offset(w * x, y))
                    drawCircle(if (atteint) RelayPalette.Or else RelayPalette.BleuNuitClair, 4.5.dp.toPx(), Offset(w * x, y))
                }
                if (etincelle >= 0f) {
                    val pos = Offset(w * etincelle, y)
                    drawCircle(RelayPalette.Or.copy(alpha = 0.4f), 7.dp.toPx(), pos)
                    drawCircle(RelayPalette.OrPale, 3.5.dp.toPx(), pos)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("vous", "réseau", destinataire.substringBefore(" ").lowercase()).forEach {
                    Text(it, fontFamily = PlexMono, style = MaterialTheme.typography.labelSmall, color = RelayPalette.BleuCiel)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(formatHeure.format(Date(m.timestamp)), style = MaterialTheme.typography.labelSmall, color = RelayPalette.BleuBrume)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    AnimatedVisibility(m.delivered, enter = scaleIn(spring(dampingRatio = 0.4f))) {
                        Icon(Icones.DoubleCoche, contentDescription = null, tint = couleurEtat, modifier = Modifier.size(15.dp))
                    }
                    Text(if (m.delivered) "Remis" else "En route", style = MaterialTheme.typography.labelMedium, color = couleurEtat)
                }
            }
        }
    }
}

@Composable
private fun Composeur(valeur: String, onValeur: (String) -> Unit, onEnvoyer: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 14.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                BasicTextField(
                    value = valeur,
                    onValueChange = onValeur,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(RelayPalette.Or),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onEnvoyer() }),
                    maxLines = 5,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .background(MaterialTheme.colorScheme.background, RoundedCornerShape(24.dp))
                        .border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
                        .semantics { contentDescription = "Votre message" },
                    decorationBox = { champ ->
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                            if (valeur.isEmpty()) {
                                Text("Écrire un message chiffré…", style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            champ()
                        }
                    }
                )
                val interaction = remember { MutableInteractionSource() }
                val presse by interaction.collectIsPressedAsState()
                val echelle by animateFloatAsState(if (presse) 0.92f else 1f, spring(dampingRatio = 0.5f), label = "presse")
                FilledIconButton(
                    onClick = onEnvoyer,
                    enabled = valeur.isNotBlank(),
                    interactionSource = interaction,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = RelayPalette.Or, contentColor = RelayPalette.Encre,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    modifier = Modifier.size(48.dp).scale(echelle)
                ) {
                    Icon(Icones.Envoyer, contentDescription = "Envoyer", modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

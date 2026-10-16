package com.relay.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.relay.app.ui.components.Icones
import com.relay.app.ui.components.animationsActivees
import com.relay.app.ui.theme.RelayPalette
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private data class TypeAlerte(val nom: String, val detail: String)

private val TYPES = listOf(
    TypeAlerte("Besoin médical", "Blessé ou malade, besoin d'aide rapide"),
    TypeAlerte("Danger", "Prévenir les autres d'un risque"),
    TypeAlerte("Je suis en sécurité", "Rassurer ses proches et l'équipe"),
)

private const val DUREE_MAINTIEN_MS = 2000

/**
 * Alerte : maintenir le bouton deux secondes pour envoyer un message
 * chiffré à tous ses contacts, puis suivre la remise contact par contact.
 */
@Composable
fun AlerteScreen(viewModel: MainViewModel, onBack: () -> Unit) {
    val contacts by viewModel.contacts.collectAsState(emptyList())
    var type by remember { mutableStateOf(TYPES[0]) }
    var suivi by remember { mutableStateOf<SuiviAlerte?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .background(RelayPalette.AlerteFond)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icones.Retour, contentDescription = "Retour", tint = AlerteTexte.Doux)
            }
            Text("Alerte", style = MaterialTheme.typography.titleLarge, color = RelayPalette.Blanc)
        }
        AnimatedContent(
            targetState = suivi,
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
            label = "alerte"
        ) { s ->
            if (s == null) {
                Preparation(
                    type = type,
                    onType = { type = it },
                    nombreContacts = contacts.size,
                    onDiffuser = { suivi = viewModel.diffuserAlerte(type.nom) }
                )
            } else {
                Diffusee(
                    type = type.nom,
                    suivi = s,
                    remise = remember(s) { viewModel.remiseAlerte(s) }.collectAsState(emptyMap()).value,
                    noms = contacts.associate { it.shortId to it.displayName },
                    onNouvelle = { suivi = null },
                    onRetour = onBack
                )
            }
        }
    }
}

private object AlerteTexte {
    val Doux = androidx.compose.ui.graphics.Color(0xFFF3D6D2)
    val Discret = androidx.compose.ui.graphics.Color(0xFFD9B8B4)
}

@Composable
private fun Preparation(type: TypeAlerte, onType: (TypeAlerte) -> Unit, nombreContacts: Int, onDiffuser: () -> Unit) {
    val anime = animationsActivees()
    val haptique = LocalHapticFeedback.current
    val portee = rememberCoroutineScope()
    val progres = remember { Animatable(0f) }
    var maintien by remember { mutableStateOf<Job?>(null) }
    var confirmer by remember { mutableStateOf(false) }
    val possible = nombreContacts > 0
    val veille = if (anime && possible) rememberInfiniteTransition(label = "veille").animateFloat(
        1f, 1.035f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "v"
    ).value else 1f

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.padding(horizontal = 20.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TYPES.forEach { t ->
                val choisi = t == type
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(if (choisi) RelayPalette.AlerteCorail.copy(alpha = 0.14f) else RelayPalette.Blanc.copy(alpha = 0.04f), RoundedCornerShape(14.dp))
                        .border(1.5.dp, if (choisi) RelayPalette.AlerteCorail else RelayPalette.Blanc.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
                        .selectable(selected = choisi, role = Role.RadioButton) { onType(t) }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        Modifier.size(20.dp).border(2.dp, if (choisi) RelayPalette.AlerteCorail else RelayPalette.Blanc.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (choisi) Box(Modifier.size(9.dp).background(RelayPalette.AlerteCorail, CircleShape))
                    }
                    Column {
                        Text(t.nom, style = MaterialTheme.typography.titleMedium, color = RelayPalette.Blanc)
                        Text(t.detail, style = MaterialTheme.typography.bodySmall, color = AlerteTexte.Discret)
                    }
                }
            }
        }

        Box(Modifier.padding(top = 36.dp).size(224.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val trait = 6.dp.toPx()
                val r = size.minDimension / 2f - trait
                val c = Offset(size.width / 2f, size.height / 2f)
                drawCircle(RelayPalette.AlerteCorail.copy(alpha = 0.18f), radius = r, center = c, style = Stroke(trait))
                drawArc(
                    RelayPalette.AlerteCorail, -90f, 360f * progres.value, false,
                    topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                    style = Stroke(trait, cap = StrokeCap.Round)
                )
            }
            val restant = ((1f - progres.value) * DUREE_MAINTIEN_MS / 1000f)
            Box(
                Modifier
                    .size(184.dp)
                    .scale(if (maintien != null) 0.96f else veille)
                    .background(if (possible) RelayPalette.AlerteRouge else RelayPalette.AlerteRouge.copy(alpha = 0.4f), CircleShape)
                    .semantics {
                        contentDescription = "Alerte ${type.nom}. Maintenir deux secondes pour diffuser."
                        onClick(label = "Diffuser l'alerte") { if (possible) confirmer = true; possible }
                    }
                    .pointerInput(possible) {
                        if (!possible) return@pointerInput
                        detectTapGestures(onPress = {
                            maintien = portee.launch {
                                haptique.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                progres.animateTo(1f, tween(DUREE_MAINTIEN_MS, easing = LinearEasing))
                                haptique.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDiffuser()
                            }
                            val relache = tryAwaitRelease()
                            if (progres.value < 1f || !relache) {
                                maintien?.cancel()
                                portee.launch { progres.animateTo(0f, tween(250)) }
                            }
                            maintien = null
                        })
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icones.Alerte, contentDescription = null, tint = RelayPalette.Blanc, modifier = Modifier.size(40.dp))
                    Text(
                        if (progres.value > 0f) "%.1f s".format(restant) else "MAINTENIR",
                        style = MaterialTheme.typography.titleMedium,
                        color = RelayPalette.Blanc
                    )
                }
            }
        }
        Text(
            if (possible) "Maintenez 2 secondes. L'alerte part, chiffrée, vers vos $nombreContacts contact${if (nombreContacts > 1) "s" else ""}, même sans réseau."
            else "Aucun contact pour l'instant : l'alerte ne pourrait être envoyée à personne.",
            style = MaterialTheme.typography.bodyMedium,
            color = AlerteTexte.Discret,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 24.dp, bottom = 24.dp)
        )
    }

    if (confirmer) {
        AlertDialog(
            onDismissRequest = { confirmer = false },
            title = { Text("Diffuser l'alerte ?") },
            text = { Text("« ${type.nom} » sera envoyée à vos $nombreContacts contacts.") },
            confirmButton = { TextButton(onClick = { confirmer = false; onDiffuser() }) { Text("Diffuser") } },
            dismissButton = { TextButton(onClick = { confirmer = false }) { Text("Annuler") } }
        )
    }
}

@Composable
private fun Diffusee(
    type: String,
    suivi: SuiviAlerte,
    remise: Map<String, Boolean>,
    noms: Map<String, String>,
    onNouvelle: () -> Unit,
    onRetour: () -> Unit,
) {
    val anime = animationsActivees()
    val onde = if (anime) rememberInfiniteTransition(label = "ondes").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "o"
    ).value else 0.3f
    val recues = remise.count { it.value }

    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.padding(top = 16.dp).size(156.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2f, size.height / 2f)
                repeat(3) { i ->
                    val p = (onde + i / 3f) % 1f
                    drawCircle(
                        RelayPalette.AlerteCorail.copy(alpha = 0.85f * (1f - p)),
                        radius = size.minDimension * (0.28f + 0.5f * p), center = c, style = Stroke(2.dp.toPx())
                    )
                }
            }
            Box(Modifier.size(84.dp).background(RelayPalette.AlerteRouge, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icones.Alerte, contentDescription = null, tint = RelayPalette.Blanc, modifier = Modifier.size(34.dp))
            }
        }
        Text("Alerte diffusée", style = MaterialTheme.typography.headlineSmall, color = RelayPalette.Blanc, modifier = Modifier.padding(top = 22.dp))
        Text(
            "$type · reçue par $recues sur ${suivi.destinataires.size}, relayée de proche en proche",
            style = MaterialTheme.typography.bodyMedium, color = AlerteTexte.Discret, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
        Column(Modifier.padding(top = 20.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suivi.destinataires.forEach { id ->
                val recue = remise[id] == true
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(RelayPalette.Blanc.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AnimatedContent(recue, transitionSpec = { scaleIn(spring(dampingRatio = 0.45f)) togetherWith fadeOut() }, label = "statut") { ok ->
                        Icon(
                            if (ok) Icones.Coche else Icones.Horloge, contentDescription = null,
                            tint = if (ok) RelayPalette.Remis else RelayPalette.OrLumiere, modifier = Modifier.size(20.dp)
                        )
                    }
                    val nom = noms[id]?.takeIf { it != id } ?: "Appareil ${id.takeLast(4).uppercase()}"
                    Text(nom, style = MaterialTheme.typography.bodyLarge, color = RelayPalette.Blanc, modifier = Modifier.weight(1f))
                    Text(if (recue) "reçue" else "en route", style = MaterialTheme.typography.bodySmall, color = AlerteTexte.Discret)
                }
            }
        }
        OutlinedButton(
            onClick = onNouvelle,
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, RelayPalette.AlerteCorail.copy(alpha = 0.45f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RelayPalette.Blanc),
            modifier = Modifier.padding(top = 22.dp).fillMaxWidth().height(52.dp)
        ) { Text("Nouvelle alerte", style = MaterialTheme.typography.labelLarge) }
        Button(
            onClick = onRetour,
            colors = ButtonDefaults.buttonColors(containerColor = RelayPalette.Or, contentColor = RelayPalette.Encre),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.padding(top = 10.dp, bottom = 24.dp).fillMaxWidth().height(52.dp)
        ) { Text("Retour au réseau", style = MaterialTheme.typography.labelLarge) }
    }
}
